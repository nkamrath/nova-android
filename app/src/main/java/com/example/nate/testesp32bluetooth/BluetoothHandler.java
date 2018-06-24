package com.example.nate.testesp32bluetooth;

/**
 * Created by Nate on 12/18/2017.
 */

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;

import android.app.Service;

import android.os.Binder;
import android.os.IBinder;

import android.content.Context;
import android.content.Intent;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;

enum BluetoothHandlerState
{
    STOPPED, SEARCHING, CONNECTING, CONNECTED, DISCONNECTED
}

public class BluetoothHandler extends Service {
    private String desired_service_uuid = "0000561b-0000-1000-8000-00805f9b34fb";
    private String desired_char_uuid = "00000003-0000-1000-8000-00805f9b34fb";

    private BluetoothHandlerState mState;
    java.util.Timer mStopScanTimer = null;
    java.util.Timer mStartScanTimer = null;

    private BluetoothAdapter mBluetoothAdapter;
    private static final long SCAN_STOP_DELAY = 5000;   //ms we spend on searching until stop is called
    private static final long SCAN_START_DELAY = 100;  //ms we spend off between searches before start is called again
    private BluetoothLeScanner mLEScanner;
    private ScanSettings settings;
    private List<ScanFilter> filters;
    private BluetoothGatt mGatt;
    private Context mContext;

    private List<StateChangeCallback> mStateChangeCallbacks;    //callbacks to be called when bluetooth connectivity state changes

    private List<ScheduledRead> mScheduledReads;        //Scheduled read structures to be executed in order
    private Semaphore mScheduledReadsSemaphore;         //Semaphore of max count 1 to act as access mutex on mScheduledReads list

    private List<ScheduledWrite> mScheduledWrites;      //Scheduled write structures to be executed in order
    private Semaphore mScheduledWritesSemaphore;        //Semaphore of max count 1 to act as access mutex on mScheduledWrites list

    private final IBinder mBinder = new LocalBinder();  //Binder for this as it is a service

    //==============================================================================================
    //PUBLIC INTERFACES
    //==============================================================================================
    public interface ReadCallback{
        void onSuccess(String service_uuid, String characteristic_uuid, byte[] data);
    }

    public interface WriteCallback{
        void onSuccess(String service_uuid, String characteristic_uuid, int status);
    }

    public interface StateChangeCallback{
        void onChange(BluetoothHandlerState newState);
    }

    //==============================================================================================
    //PUBLIC CLASSES
    //==============================================================================================
    public static class ScheduledRead{
        ScheduledRead(String service_uuid, String characteristic_uuid, ReadCallback readCallback){
            mService_uuid = service_uuid;
            mCharacteristic_uuid = characteristic_uuid;
            mReadCallback = readCallback;
        }
        public ReadCallback mReadCallback;
        public String mService_uuid;
        public String mCharacteristic_uuid;
    }

    public static class ScheduledWrite{
        ScheduledWrite(String service_uuid, String characteristic_uuid, WriteCallback writeCallback, byte[] data){
            mService_uuid = service_uuid;
            mCharacteristic_uuid = characteristic_uuid;
            mWriteCallback = writeCallback;
            mData = data;
        }

        public WriteCallback mWriteCallback;
        public String mService_uuid;
        public String mCharacteristic_uuid;
        public byte[] mData;
    }

    //==============================================================================================
    //PUBLIC FUNCTIONS
    //==============================================================================================
    public BluetoothHandler(){
        mStateChangeCallbacks = new ArrayList<>();

        mScheduledReads = new ArrayList<>();
        mScheduledReadsSemaphore = new Semaphore(1);

        mScheduledWrites = new ArrayList<>();
        mScheduledWritesSemaphore = new Semaphore(1);
    }
    BluetoothHandler(Context context, BluetoothManager manager){
        mContext = context;

        mStateChangeCallbacks = new ArrayList<>();

        mScheduledReads = new ArrayList<>();
        mScheduledReadsSemaphore = new Semaphore(1);

        mScheduledWrites = new ArrayList<>();
        mScheduledWritesSemaphore = new Semaphore(1);
    }

    void Init(Context context, BluetoothManager manager){
        mContext = context;
        final BluetoothManager bluetoothManager = manager;
        mBluetoothAdapter = bluetoothManager.getAdapter();
        changeState(BluetoothHandlerState.STOPPED);
    }

    /**
     * Class used for the client Binder.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with IPC.
     */
    public class LocalBinder extends Binder {
        BluetoothHandler getService() {
            // Return this instance of LocalService so clients can call public methods
            return BluetoothHandler.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate(){

    }

    void destroy(){
        changeState(BluetoothHandlerState.STOPPED);
        mLEScanner.stopScan(mScanCallback);
        if (mGatt == null) {
            return;
        }
        mGatt.close();
        mGatt = null;
    }

    /*
    Start the bluetooth LE scan for a device which supports the desired
    service we are looking for by UUID
     */
    void start(){
        mLEScanner = mBluetoothAdapter.getBluetoothLeScanner();
        settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder().setDeviceName("Nova Control").build());

        scanLeDevice(true);
    }

    BluetoothAdapter getAdapter(){
        return mBluetoothAdapter;
    }

    public BluetoothHandlerState getState(){
        return mState;
    }

    /*
    Schedule a read from a bluetooth service/characteristic.
    Calls specified callback after read attempt with resulting data
     */
    public void scheduleRead(ScheduledRead read){
        try {
            mScheduledReadsSemaphore.acquire();
            try {
                mScheduledReads.add(read);
            } finally {
                mScheduledReadsSemaphore.release();
            }
        }
        catch (InterruptedException e){
            mScheduledReadsSemaphore.release();
            Log.i("CONCURRENCY ERROR",e.toString());
        }
        executeScheduledRead();
    }

    /*
    Schedule a write to a bluetooth service/characteristic.
    Calls specified callback after write attempt with resulting status of write
     */
    public void scheduleWrite(ScheduledWrite write){
        try {
            mScheduledWritesSemaphore.acquire();
            try {
                mScheduledWrites.add(write);
            } finally {
                mScheduledWritesSemaphore.release();
            }
        }
        catch (InterruptedException e){
            mScheduledWritesSemaphore.release();
            Log.i("CONCURRENCY ERROR",e.toString());
        }
        executeScheduledWrite();
    }

    void registerStateChangeCallback(StateChangeCallback callback){
        mStateChangeCallbacks.add(callback);
    }

    //==============================================================================================
    //PRIVATE FUNCTIONS
    //==============================================================================================
    private void scheduleDelayedStopScan(){
        if(mStopScanTimer == null) {
            mStopScanTimer = new java.util.Timer();
            mStopScanTimer.schedule(new java.util.TimerTask() {
                @Override
                public void run() {
                    mStopScanTimer = null;
                    mLEScanner.stopScan(mScanCallback);
                    Log.i("info", "stop scan");
                    if (mState != BluetoothHandlerState.CONNECTING && mState != BluetoothHandlerState.CONNECTED) {
                        scanLeDevice(false);
                    }
                }
            }, SCAN_STOP_DELAY);
        }
    }

    private void scheduleDelayedStartScan() {
        if (mStartScanTimer == null) {
            mStartScanTimer = new java.util.Timer();
            mStartScanTimer.schedule(new java.util.TimerTask() {
                @Override
                public void run() {
                    mStartScanTimer = null;
                    if (mState == BluetoothHandlerState.SEARCHING || mState == BluetoothHandlerState.DISCONNECTED) {
                        scanLeDevice(true);
                    }
                }
            }, SCAN_START_DELAY);
        }
    }

    private void scanLeDevice(final boolean enable) {
        if (enable) {
            mLEScanner.startScan(filters, settings, mScanCallback);
            changeState(BluetoothHandlerState.SEARCHING);
            Log.i("info", "start scan");
            //schedule a scan stop so we aren't always scanning if a device isn't present
            scheduleDelayedStopScan();
        } else if(!enable) {
            mLEScanner.stopScan(mScanCallback);
            if(mState == BluetoothHandlerState.SEARCHING){
                //if we are still searching, schedule a start search after a delay
                scheduleDelayedStartScan();
            }
        }
    }

    private void callStateChangeCallbacks(){
        for(int i =0; i < mStateChangeCallbacks.size(); i++){
            mStateChangeCallbacks.get(i).onChange(mState);
        }
    }

    private void changeState(BluetoothHandlerState newState){
        mState = newState;
        callStateChangeCallbacks();
    }

    private void connectToDevice(BluetoothDevice device) {
        mGatt = device.connectGatt(mContext, false, gattCallback);
        scanLeDevice(false);
    }

    private void executeScheduledRead(){
        ScheduledRead read = null;
        try {
            mScheduledReadsSemaphore.acquire();
            try {
                if (mScheduledReads.size() > 0) {
                    //start the read
                    read = mScheduledReads.get(0);
                }
            } finally {
                mScheduledReadsSemaphore.release();
            }
        }
        catch (InterruptedException e){
            mScheduledReadsSemaphore.release();
            Log.i("CONCURRENCY ERROR",e.toString());
        }

        if(read != null){
            readCharacteristic(read.mService_uuid, read.mCharacteristic_uuid);
        }
    }

    private boolean readCharacteristic(String service_uuid, String characteristic_uuid){
        if(mState == BluetoothHandlerState.CONNECTED){
            List<BluetoothGattService> services = mGatt.getServices();
            for(int i = 0; i < services.size(); i++) {
                if (services.get(i).getUuid().toString().equals(service_uuid)) {
                    for (int j = 0; j < services.get(i).getCharacteristics().size(); j++) {
                        if (services.get(i).getCharacteristics().get(j).getUuid().toString().equals(characteristic_uuid)) {
                            //read from the service
                            mGatt.readCharacteristic(services.get(i).getCharacteristics().get(j));
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private void executeScheduledWrite(){
        ScheduledWrite write = null;
        try {
            mScheduledWritesSemaphore.acquire();
            try {
                if (mScheduledWrites.size() > 0) {
                    //start the read
                    write = mScheduledWrites.get(0);
                }
            } finally {
                mScheduledWritesSemaphore.release();
            }
        }
        catch (InterruptedException e){
            mScheduledWritesSemaphore.release();
            Log.i("CONCURRENCY ERROR",e.toString());
        }

        if(write != null){
            writeCharacteristic(write.mService_uuid, write.mCharacteristic_uuid, write.mData);
        }
    }

    private boolean writeCharacteristic(String service_uuid, String characteristic_uuid, byte[] data){
        if(mState == BluetoothHandlerState.CONNECTED){
            List<BluetoothGattService> services = mGatt.getServices();
            for(int i = 0; i < services.size(); i++) {
                if (services.get(i).getUuid().toString().equals(service_uuid)) {
                    for (int j = 0; j < services.get(i).getCharacteristics().size(); j++) {
                        if (services.get(i).getCharacteristics().get(j).getUuid().toString().equals(characteristic_uuid)) {
                            //write to the service
                            services.get(i).getCharacteristics().get(j).setValue(data);
                            mGatt.writeCharacteristic(services.get(i).getCharacteristics().get(j));
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private BluetoothAdapter.LeScanCallback mLeScanCallback =
            new BluetoothAdapter.LeScanCallback() {
                @Override
                public void onLeScan(final BluetoothDevice device, int rssi,
                                     byte[] scanRecord) {
                    new java.util.Timer().schedule(new java.util.TimerTask() {
                        @Override
                        public void run() {
                            Log.i("onLeScan", device.toString());
                            connectToDevice(device);
                        }
                    }, 100);
                }
            };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            Log.i("onConnectionStateChange", "Status: " + status);
            switch (newState) {
                case BluetoothProfile.STATE_CONNECTED:
                    Log.i("gattCallback", "STATE_CONNECTED");
                    gatt.discoverServices();
                    changeState(BluetoothHandlerState.CONNECTING);
                    break;
                case BluetoothProfile.STATE_DISCONNECTED:
                    Log.e("gattCallback", "STATE_DISCONNECTED");
                    if(mState != BluetoothHandlerState.DISCONNECTED) {
                        changeState(BluetoothHandlerState.DISCONNECTED);
                        scanLeDevice(true);
                    }
                    break;
                default:
                    Log.e("gattCallback", "STATE_OTHER");
                    scanLeDevice(true);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            List<BluetoothGattService> services = gatt.getServices();
            Log.i("onServicesDiscovered", services.toString());

            for(int i = 0; i < services.size(); i++) {
                if (services.get(i).getUuid().toString().equals(desired_service_uuid)) {
                    for(int j = 0; j < services.get(i).getCharacteristics().size(); j++) {
                        if(services.get(i).getCharacteristics().get(j).getUuid().toString().equals(desired_char_uuid)) {
                            //found the right service
                            changeState(BluetoothHandlerState.CONNECTED);
                            //gatt.readCharacteristic(services.get(i).getCharacteristics().get(j));
                            return;
                        }

                    }
                }
            }
            gatt.disconnect(); //didn't find the right service, keep searching
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic
                                                 characteristic, int status) {
            byte[] data = characteristic.getValue();

            ScheduledRead read = null;
            try {
                mScheduledReadsSemaphore.acquire();
                try {
                    if (mScheduledReads.size() > 0) {
                        //start the read
                        read = mScheduledReads.remove(0);
                    }
                } finally {
                    mScheduledReadsSemaphore.release();
                }
            }
            catch (InterruptedException e){
                mScheduledReadsSemaphore.release();
                Log.i("CONCURRENCY ERROR",e.toString());
            }

            if(read != null){
                read.mReadCallback.onSuccess(read.mService_uuid, read.mCharacteristic_uuid, data);
            }

            executeScheduledRead();
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic
                                                  characteristic, int status){
            ScheduledWrite write = null;
            try {
                mScheduledWritesSemaphore.acquire();
                try {
                    if (mScheduledWrites.size() > 0) {
                        //start the read
                        write = mScheduledWrites.remove(0);
                    }
                } finally {
                    mScheduledWritesSemaphore.release();
                }
            }
            catch (InterruptedException e){
                mScheduledWritesSemaphore.release();
                Log.i("CONCURRENCY ERROR",e.toString());
            }

            if(write != null){
                write.mWriteCallback.onSuccess(write.mService_uuid, write.mCharacteristic_uuid, status);
            }

            executeScheduledWrite();
        }
    };

    private ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            Log.i("callbackType", String.valueOf(callbackType));
            Log.i("result", result.toString());
            BluetoothDevice btDevice = result.getDevice();
            connectToDevice(btDevice);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult sr : results) {
                Log.i("ScanResult - Results", sr.toString());
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e("Scan Failed", "Error Code: " + errorCode);
            //scanLeDevice(false);
            //changeState(BluetoothHandlerState.DISCONNECTED);

            //after a delay, restart the search
//            Handler handler = new Handler();
//            handler.postDelayed(new Runnable() {
//                @Override
//                public void run() {
//                    scanLeDevice(false);
//                    changeState(BluetoothHandlerState.DISCONNECTED);
//                    scanLeDevice(true);
//                }
//            }, 5000);
        }
    };
}