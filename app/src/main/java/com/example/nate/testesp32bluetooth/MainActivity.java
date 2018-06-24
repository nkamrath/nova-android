package com.example.nate.testesp32bluetooth;

import android.annotation.TargetApi;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v7.app.AppCompatActivity;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import java.math.BigInteger;


public class MainActivity extends AppCompatActivity {

    private BluetoothHandler mBluetoothHandler;
    private final static int REQUEST_ENABLE_BT = 1;

    private TextView mConnectionStateTextView;
    private CheckBox mLedCheckBox;
    private CheckBox mMainPowerCheckBox;

    private long mAuthCounter = 0;

    private String mNovaServiceUuid = "0000561b-0000-1000-8000-00805f9b34fb";

    private String mAuthCounterCharUuid = "00000003-0000-1000-8000-00805f9b34fb";
    private String mIoControllerCharUuid = "00000004-0000-1000-8000-00805f9b34fb";

    private BigInteger ByteSwapBigInt64(byte[] data) {
        byte temp = data[0];
        data[0] = data[7];
        data[7] = temp;

        temp = data[1];
        data[1] = data[6];
        data[6] = temp;

        temp = data[2];
        data[2] = data[5];
        data[5] = temp;

        temp = data[3];
        data[3] = data[4];
        data[4] = temp;

        BigInteger bi = new BigInteger(data);
        return bi;
    }

    private void BluetoothHandlerStateChange(BluetoothHandlerState newState){
        String newText = "Unknown";
        switch(newState){
            case DISCONNECTED:
                newText = "Disconnected";
                break;
            case CONNECTING:
                newText = "Connecting";
                break;
            case CONNECTED:
                newText = "Connected";

                //read data
                BluetoothHandler.ReadCallback readCallback = new BluetoothHandler.ReadCallback() {
                    @Override
                    public void onSuccess(final String service_uuid, final String characteristic_uuid, final byte[] data) {
                        mConnectionStateTextView.post(new Runnable() {
                            @Override
                            public void run() {
                                mAuthCounter = ByteSwapBigInt64(data).longValue();
                                Toast.makeText(getApplicationContext(), "Auth Counter: " + Long.toString(mAuthCounter),
                                        Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                };

                BluetoothHandler.ScheduledRead read = new BluetoothHandler.ScheduledRead(mNovaServiceUuid,
                        mAuthCounterCharUuid, readCallback);

                mBluetoothHandler.scheduleRead(read);

                break;
            case SEARCHING:
                newText = "Searching";
                break;
            default:
                newText = "Default";
        }
        mConnectionStateTextView.setText(newText);
    }

    boolean mBound = false;
    /** Defines callbacks for service binding, passed to bindService() */
    private ServiceConnection mConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance
            BluetoothHandler.LocalBinder binder = (BluetoothHandler.LocalBinder) service;
            mBluetoothHandler = binder.getService();
            mBound = true;
            InitBluetoothHandler();
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            mBound = false;
        }
    };

    public static int byteArrayToInt(byte[] b)
    {
        return   b[0] & 0xFF |
                (b[1] & 0xFF) << 8 |
                (b[2] & 0xFF) << 16 |
                (b[3] & 0xFF) << 24;
    }

    private void SendBluetoothCommand(int type, byte[] data) {
        BluetoothHandler.WriteCallback writeCallback = new BluetoothHandler.WriteCallback() {
            @Override
            public void onSuccess(final String service_uuid, final String characteristic_uuid, int status) {

                MainActivity.this.runOnUiThread(new Runnable() {
                    public void run() {
                        Toast.makeText(getApplicationContext(), "Command Sent.",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        };

        BluetoothCommand command = new BluetoothCommand(type, data, "", mAuthCounter);
        mAuthCounter += 1;

        byte[] command_bytes = command.getBytes();

        BluetoothHandler.ScheduledWrite write = new BluetoothHandler.ScheduledWrite(mNovaServiceUuid,
                mIoControllerCharUuid, writeCallback, command_bytes);
        mBluetoothHandler.scheduleWrite(write);
    }

    private void IoControllerWrite(int address, int data) {
        BluetoothHandler.WriteCallback writeCallback = new BluetoothHandler.WriteCallback() {
            @Override
            public void onSuccess(final String service_uuid, final String characteristic_uuid, int status) {

                MainActivity.this.runOnUiThread(new Runnable() {
                    public void run() {
                        Toast.makeText(getApplicationContext(), "IO Controller Write Success.",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        };

        byte[] write_data = new byte[3];
        write_data[0] = (byte)address;
        write_data[1] = (byte)data;
        write_data[2] = ((byte)(data>>8));

        BluetoothCommand command = new BluetoothCommand(1, write_data, "", mAuthCounter);
        mAuthCounter += 1;

        byte[] command_bytes = command.getBytes();

        BluetoothHandler.ScheduledWrite write = new BluetoothHandler.ScheduledWrite(mNovaServiceUuid,
                mIoControllerCharUuid, writeCallback, command_bytes);
        mBluetoothHandler.scheduleWrite(write);
    }

    public void onReadAuthCounter(android.view.View view){
        //to send a command, first we need to read the auth counter from the device
        BluetoothHandler.ReadCallback readCallback = new BluetoothHandler.ReadCallback() {
            @Override
            public void onSuccess(final String service_uuid, final String characteristic_uuid, final byte[] data) {
                mConnectionStateTextView.post(new Runnable() {
                    @Override
                    public void run() {
                        mAuthCounter = ByteSwapBigInt64(data).longValue();
                        Toast.makeText(getApplicationContext(), "Auth Counter: " + Long.toString(mAuthCounter),
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        };

        BluetoothHandler.ScheduledRead read = new BluetoothHandler.ScheduledRead(mNovaServiceUuid,
                mAuthCounterCharUuid, readCallback);
        mBluetoothHandler.scheduleRead(read);
    }

    public void onLedOnOffClicked(android.view.View view) {
        int ledState = 0;
        if(mLedCheckBox.isChecked()) {
            ledState = 1;
        }

        //IoControllerWrite(1, ledState);
        IoControllerWrite(1, ((50) | (ledState<<12)));
    }

    public void onMainPowerOnOffClicked(android.view.View view) {
        int mainPowerState = 0;
        byte[] command_data = new byte[1];
        command_data[0] = 0;
        if(mMainPowerCheckBox.isChecked()) {
            mainPowerState = 1;
            SendBluetoothCommand(0x03, command_data);
        }
        else {
            SendBluetoothCommand(0x04, command_data);
        }
    }

    private void InitBluetoothHandler(){
        final BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);

        BluetoothAdapter adapter = mBluetoothHandler.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }

        mBluetoothHandler.Init(this, bluetoothManager);

        //setup state change callback
        BluetoothHandler.StateChangeCallback stateChangeCallback = new BluetoothHandler.StateChangeCallback() {
            @Override
            public void onChange(final BluetoothHandlerState newState) {
                mConnectionStateTextView.post(new Runnable() {
                    @Override
                    public void run() {
                        BluetoothHandlerStateChange(newState);
                    }
                });
            }
        };
        mBluetoothHandler.registerStateChangeCallback(stateChangeCallback);
        mBluetoothHandler.start();
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //setup ui
        mConnectionStateTextView = (TextView)findViewById(R.id.connection_state_text_view);
        mLedCheckBox = findViewById(R.id.led_on_off_check_box);
        mMainPowerCheckBox = findViewById(R.id.main_power_on_off_check_box);

        Intent intent = new Intent(this, BluetoothHandler.class);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStart(){
        super.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
//        BluetoothAdapter adapter = mBluetoothHandler.getAdapter();
//        if (adapter == null || !adapter.isEnabled()) {
//            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
//            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
//        } else {
//            mBluetoothHandler.start();
//        }
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mBluetoothHandler.destroy();
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}
