package ru0xdc.externalgps.usb;

import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.location.Location;
import android.os.Bundle;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.concurrent.GuardedBy;

import proguard.annotation.Keep;
import ru0xdc.externalgps.BuildConfig;
import ru0xdc.externalgps.DataLoggerConfiguration;
import ru0xdc.externalgps.MockLocationProvider;
import ru0xdc.externalgps.StatsNative;
import ru0xdc.externalgps.UsbGpsConverter;

import static junit.framework.Assert.assertTrue;

/**
* Created by alexey on 03.03.14.
*/
public class UsbServiceThread extends Thread {

    private static final boolean DBG = BuildConfig.DEBUG & true;
    private static final String TAG = UsbServiceThread.class.getSimpleName();

    /* mObject is used by native code, do not remove or rename */
    @Keep
    protected volatile long mObject;

    @GuardedBy("this")
    private volatile UsbGpsConverter.TransportState mConnectionState;

    private final AtomicBoolean mCancelRequested = new AtomicBoolean();
    private final AtomicBoolean mFirstValidLocationReceived = new AtomicBoolean();

    @GuardedBy("this")
    private volatile AutobaudTask mAutobaudThread;

    private final Location mReportedLocation = new Location("");
    private final Bundle mReportedLocationBundle = new Bundle(1);

    public interface Callbacks {

        public Context getContext();

        public UsbDevice getUsbDevice();

        public SerialLineConfiguration getSerialLineConfiguration();

        public void onStateConnected();
        public void onDisconnected();

        public void onLocationUnknown();
        public void onLocationReceived(Location location);
        public void onFirstLocationReceived(Location location);

        public void onAutoconfStarted();
        public void onAutobaoudCompleted(int newBaudrate);
        public void onAutobaoudFailed();
    }

    private final Callbacks mCallbacks;

    @GuardedBy("this")
    private UsbSerialController mUsbController;

    public UsbServiceThread(Callbacks callbacks) {
        mConnectionState = UsbGpsConverter.TransportState.IDLE;
        mAutobaudThread = null;
        mCallbacks = callbacks;
        native_create();
    }

    public synchronized void cancel() {
        mCancelRequested.set(true);
        if (mUsbController != null) {
            mUsbController.detach();
            mUsbController = null;
        }
    }

    /**
     * Write to the connected OutStream.
     * @param buffer  The bytes to write
     */
    public void write(byte[] buffer, int offset, int count) throws IOException {
        OutputStream os;
        synchronized(this) {
            if (mConnectionState != UsbGpsConverter.TransportState.CONNECTED) {
                Log.e(TAG, "write() error: not connected");
                return;
            }
            if (mUsbController == null) {
                Log.e(TAG, "write() error: no usb controller");
            }
            os = mUsbController.getOutputStream();
        }
        os.write(buffer, offset, count);
    }

    public StatsNative getStats() {
        StatsNative dst = new StatsNative();
        native_get_stats(dst);
        return dst;
    }

    @Override
    public void run() {
        Log.i(TAG, "BEGIN UsbToLocalSocket-USB");
        setName("UsbToLocalSocket-USB");
        try {
            setState(UsbGpsConverter.TransportState.CONNECTING);
            throwIfCancelRequested();
            connectLoop();

            mCallbacks.onStateConnected();
            mCallbacks.onAutoconfStarted();
            startInitBaudrate();

            final UsbSerialController.UsbSerialInputStream is;
            final UsbSerialController.UsbSerialOutputStream os;
            synchronized (this) {
                is = mUsbController.getInputStream();
                os = mUsbController.getOutputStream();
            }

            native_read_loop(is, os);
            throwIfCancelRequested();

            synchronized (this) {
                if (mUsbController != null) {
                    mUsbController.detach();
                    mUsbController = null;
                }
            }

            setState(UsbGpsConverter.TransportState.RECONNECTING);
            mCallbacks.onDisconnected();
        } catch (CancelRequestedException cre) {
            if (DBG) Log.v(TAG, "cancel requested");
        }finally {
            synchronized(this) {
                if (mAutobaudThread != null) {
                    mAutobaudThread.interrupt();
                }
            }
        }
    }

    @Override
    protected void finalize() throws Throwable {
        native_destroy();
        super.finalize();
    }

    // Called from native code
    @Keep
    synchronized void reportLocation(
            long time,
            double latitude,
            double longitude,
            double altitude,
            float accuracy,
            float bearing,
            float speed,
            int satellites,
            boolean isValid,
            boolean hasAccuracy,
            boolean hasAltitude,
            boolean hasBearing,
            boolean hasSpeed
            ) {

        try {
            if (!isValid) {
                if (DBG) Log.v(TAG, "loc: null");
                mCallbacks.onLocationUnknown();
                return;
            }

            mReportedLocation.reset();
            mReportedLocation.setTime(time);
            mReportedLocation.setLatitude(latitude);
            mReportedLocation.setLongitude(longitude);
            if (hasAltitude) {
                mReportedLocation.setAltitude(altitude);
            }
            if (hasAccuracy) {
                mReportedLocation.setAccuracy(accuracy);
            }
            if (hasBearing) {
                mReportedLocation.setBearing(bearing);
            }
            if (hasSpeed) {
                mReportedLocation.setSpeed(speed);
            }

            if (satellites > 0) {
                mReportedLocationBundle.putInt("satellites", satellites);
                mReportedLocation.setExtras(mReportedLocationBundle);
            }

            mCallbacks.onLocationReceived(mReportedLocation);

            if (mFirstValidLocationReceived.getAndSet(true)) {
                mCallbacks.onFirstLocationReceived(mReportedLocation);
            }

            if (DBG) Log.v(TAG, "loc: " + mReportedLocation);
        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    // Called from native code
    @Keep
    void onGpsMessageReceived(java.nio.ByteBuffer buf, int start, int size, int type) {
        if (DBG) Log.v(TAG, "msg " + type + " start/size: " + start + " " + size);
        synchronized(this) {
            if (mAutobaudThread != null) {
                mAutobaudThread.onGpsMessageReceived(buf, start, size, type);
            }
        }
    }

    private synchronized void setState(UsbGpsConverter.TransportState state) {
        UsbGpsConverter.TransportState oldState = mConnectionState;
        mConnectionState = state;
        if (DBG) Log.d(TAG, "setState() " + oldState + " -> " + state);
    }

    private void throwIfCancelRequested() throws CancelRequestedException {
        if (mCancelRequested.get()) throw new CancelRequestedException();
    }

    private void startInitBaudrate() {
        synchronized(UsbServiceThread.this) {
            if (mCallbacks.getSerialLineConfiguration().isAutoBaudrateDetectionEnabled()) {
                // refreshDataLoggerCofiguration(new DataLoggerConfiguration().setEnabled(false));
                mAutobaudThread = new AutobaudTask(mCallbacks.getContext(), mAutobaudThreadCallbacks);
                mAutobaudThread.setName("AutobaudThread");
                mAutobaudThread.start();
            }else {
                mAutobaudThreadCallbacks.onAutobaudCompleted(mCallbacks.getSerialLineConfiguration().getBaudrate());
            }
        }
    }

    private final AutobaudTask.Callbacks mAutobaudThreadCallbacks = new AutobaudTask.Callbacks() {
        @Override
        public SerialLineConfiguration getSerialLineConfiguration() {
            return mCallbacks.getSerialLineConfiguration();
        }

        @Override
        public void setSerialLineConfiguration(SerialLineConfiguration conf) {
            synchronized (UsbServiceThread.this) {
                if (mUsbController != null) mUsbController.setSerialLineConfiguration(conf);
            }
        }

        @Override
        public void onAutobaudCompleted(int baudrate) {
            synchronized (UsbServiceThread.this) {
                if (DBG) Log.v(TAG, "onAutobaudCompleted() baudrate: " + baudrate);
                mAutobaudThread = null;
                native_msg_rcvd_cb(false);
                mCallbacks.onAutobaoudCompleted(baudrate);
                // usbReceiver.mDataLoggerConfiguration.createStorageDir();
                // refreshDataLoggerCofiguration();
                // native_datalogger_start();
            }
        }

        @Override
        public void onAutobaudFailed() {
            synchronized(UsbServiceThread.this) {
                if (DBG) Log.v(TAG, "onAutobaudFailed() ");
                mAutobaudThread = null;
                native_msg_rcvd_cb(false);
                mCallbacks.onAutobaoudFailed();
            }
        }

    };

    private void connect() throws UsbSerialController.UsbControllerException, CancelRequestedException {
        UsbManager um = (UsbManager) mCallbacks.getContext().getSystemService(Context.USB_SERVICE);
        synchronized(this) {
            throwIfCancelRequested();
            mUsbController = UsbUtils.probeDevice(um, mCallbacks.getUsbDevice());
            if (mUsbController == null) {
                throw new UsbSerialController.UsbControllerException("probeDevice() failed");
            }
            mUsbController.setSerialLineConfiguration(mCallbacks.getSerialLineConfiguration());
            mUsbController.attach();
            setState(UsbGpsConverter.TransportState.CONNECTED);
        }
    }

    private void connectLoop() throws CancelRequestedException {

        if (DBG) Log.v(TAG, "connectLoop()");

        while(true) {
            try {
                connect();
                return;
            }catch (UsbSerialController.UsbControllerException e) {
                synchronized(this) {
                    throwIfCancelRequested();
                    setState(UsbGpsConverter.TransportState.RECONNECTING);
                    try {
                        wait(UsbGpsConverter.RECONNECT_TIMEOUT_MS);
                    } catch(InterruptedException ie) {
                        throwIfCancelRequested();
                    }
                }
            }
        }
    }

    private class CancelRequestedException extends Exception {
        private static final long serialVersionUID = 1L;
    }

    static {
        System.loadLibrary("usbconverter");
    }

    private native void native_create();
    private native void native_read_loop(UsbSerialController.UsbSerialInputStream inputStream, UsbSerialController.UsbSerialOutputStream outputStream);
    private native void native_destroy();
    private native void native_get_stats(StatsNative dst);
    private native synchronized void native_msg_rcvd_cb(boolean activate);

    // TODO: notify user on errors
    native void native_datalogger_configure(boolean enabled, int format, String tracksDir, String filePrefix);
    private native void native_datalogger_start();
    private native void native_datalogger_stop();


}
