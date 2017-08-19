package com.example.jalfredo.bluetoothobd;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.bluetooth.BluetoothAdapter;
import android.util.Log;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothDevice obdDevice = null;
    private static final UUID obdUUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private boolean btConnected = false;
    public String TAG = "ALFREDO";
    public boolean streaming = true;
    TextView msgView;
    TextView vinVal;
    TextView rpmVal;
    TextView spdVal;
    int rpm;
    int spd;
    byte[] dbuf;
    byte[] vinBuf;


    private final static int REQUEST_ENABLE_BT = 1;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        msgView = (TextView)findViewById(R.id.msgView);
        vinVal  = (TextView)findViewById(R.id.vinVal);
        rpmVal  = (TextView)findViewById(R.id.rpmVal);
        spdVal  = (TextView)findViewById(R.id.spdVal);


        dbuf = new byte[64];
        vinBuf = new byte[32];


        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (mBluetoothAdapter == null) {
            msgView.append("\nBluetooth not available");
            Log.d(TAG,"MainActivity.onCreate Bluetooth not available");
        }
        else {
            if (!mBluetoothAdapter.isEnabled()) {
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            }
            Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
            if (pairedDevices.size() > 0) {
                // There are paired devices. Get the name and address of each paired device.
                for (BluetoothDevice device : pairedDevices) {
                    String deviceName = device.getName();
                    String deviceHardwareAddress = device.getAddress(); // MAC address
                    msgView.append("\nBluetooth paired device "+deviceHardwareAddress+" "+deviceName);
                    if (deviceName.startsWith("OBDLink")) obdDevice = device;
                }
            }
        }
        if (obdDevice != null) {
            ConnectThread obdThread = new ConnectThread(obdDevice);
            obdThread.start();
        }
    }

    private class ConnectThread extends Thread {
        private BluetoothSocket mmSocket;
        private BluetoothDevice mmDevice;
        private InputStream mmInStream;
        private OutputStream mmOutStream;
        //private byte[] mmBuffer; // mmBuffer store for the stream



        public ConnectThread(BluetoothDevice dev) {
            BluetoothSocket sock = null;
            BluetoothSocket sockFallback = null;

            Log.d(TAG, "Starting Bluetooth connection..");
            try {
                sock = dev.createRfcommSocketToServiceRecord(obdUUID);
                sock.connect();
            } catch (Exception e1) {
                Log.e(TAG, "There was an error while establishing Bluetooth connection. Falling back..", e1);
                Class<?> clazz = sock.getRemoteDevice().getClass();
                Class<?>[] paramTypes = new Class<?>[]{Integer.TYPE};
                try {
                    Method m = clazz.getMethod("createRfcommSocket", paramTypes);
                    Object[] params = new Object[]{Integer.valueOf(1)};
                    sockFallback = (BluetoothSocket) m.invoke(sock.getRemoteDevice(), params);
                    sockFallback.connect();
                    sock = sockFallback;
                } catch (Exception e2) {
                    Log.e(TAG, "Couldn't fallback while establishing Bluetooth connection.", e2);
                    //throw new IOException(e2.getMessage());
                }
            }
            mmSocket = sock;
            btConnected = true;

            msgView.post(new Runnable() {
                 public void run() {
                     msgView.append("\nConnectThread::ConnectThread BluetoothSocket created");
                     msgView.append("\nBluetooth connected to device ");
                  }
            });
            Log.d(TAG,"ConnectThread::ConnectThread BluetoothSocket created");
        }

        public void run() {
            final byte[] vinCmd = "0902\r".getBytes();
            final byte[] ate0 = "ATE0\r".getBytes();
            final byte[] ats0 = "ATS0\r".getBytes();
            final byte[] atl0 = "ATL0\r".getBytes();
            final byte[] atl1 = "ATL1\r".getBytes();
            final byte[] rpmCmd = "010C\r".getBytes();
            final byte[] spdCmd = "010D\r".getBytes();

            setupStreams();

            writeELM(ate0);
            writeELM(ats0);
            vinBuf = writeELM(vinCmd);

            msgView.post(new Runnable() {
                public void run() {
                    msgView.append("\nConnectThread.run START streaming");
                    msgView.append("\nVIN="+vinBuf.toString());
                    vinVal.setText(new String(vinBuf));
                }
            });
            while (streaming){
                rpm = hex2val(0x0c,writeELM(rpmCmd));
                spd = hex2val(0x0d,writeELM(spdCmd));

                msgView.post(new Runnable() {
                    public void run() {
                        //msgView.append("\nrpm:"+rpm+" spd:"+spd);
                        rpmVal.setText(String.valueOf(rpm));
                        spdVal.setText(String.valueOf(spd));
                    }
                });
            }
            cancel();
        }

        public void setupStreams(){
            InputStream tmpIn = null;
            OutputStream tmpOut = null;
            try {
                tmpIn = mmSocket.getInputStream();
            } catch (IOException e) {
                Log.e(TAG, "Error occurred when creating input stream", e);
            }
            try {
                tmpOut = mmSocket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "Error occurred when creating output stream", e);
            }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;

            msgView.post(new Runnable() {
                public void run() {
                    msgView.append("\nConnectThread.setupStreams EoM");
                }
            });
        }

        byte[] writeELM(final byte[] cmd){
            byte nbyte;
            int s;
            int i = 0;
            byte[] rbuf = new byte[64];
            dbuf[0] = 0x00;
            boolean pbyte;
            String ch;
            byte[] b = new byte[2];


            try {
                mmOutStream.write(cmd);
             } catch (IOException e) {
                Log.e(TAG, "Error occurred when sending data", e);
                return dbuf;
            }
            dbuf[0] = 0x00;
            while (true) {
                try {
                    nbyte = (byte)(mmInStream.read());

                    //pbyte = (nbyte > 0x1f && nbyte < 0x80) ? true :  false;
                    //b[0] = nbyte;
                    //if (pbyte) ch = new String(b);
                    //else ch = new String(" ");
                    //if (cmd[1] == '9') Log.d("ALFREDO","writeELM VIN nbyte="+nbyte+" "+ch);
                    if (nbyte != -1) {
                        rbuf[i] = nbyte;
                        if (nbyte == 0x13) rbuf[i] = 0x00;
                        if (nbyte == 0x3e){ /* > dec62*/
                            s = 0;
                            if (cmd[0] != 'A'){
                                while (rbuf[s+4] != 0x0d){
                                    dbuf[s] = rbuf[s+4];
                                    s++;
                                    if (s == 16) break;
                                }
                                dbuf[s] = 0x00;
                                return dbuf;
                            }
                            break;
                        }
                        i++;
                        if (i == 64) break;
                    }
                    else break;
                } catch (IOException e) {
                    Log.d(TAG, "Input stream was disconnected", e);
                    break;
                }
            }
            return dbuf;
        }


        int hex2val(int pid,byte[] str){
            int val = -1;
            String obdStr = new String(str);

            try {
                switch (pid) {
                    case 0x0c:
                        val = Integer.parseInt(obdStr.substring(0,4), 16);
                        return (int) (val / 4);
                    case 0x0d:
                        val = Integer.parseInt(obdStr.substring(0,2), 16);
                        return (int) val;
                }
                return val;
            }
            catch(NumberFormatException nfe){
                Log.e("ALFRED", "parseInt NumberFormatException " +nfe.toString());
                for (int i = 0;i <  obdStr.length(); i++) {
                    Log.e("ALFRED",i + " obdStr:"+obdStr.charAt(i)+" "+ (int)obdStr.charAt(i));
                }
            }
            return 0;
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e("ALFRED", "Could not close the client socket", e);
            }
        }
    }
}
