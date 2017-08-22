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
    String vinBuf;


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
            //final byte[] vinCmd = "0902\r".getBytes();
            final byte[] ate0 = "ATE0\r".getBytes();
            final byte[] ats0 = "ATS0\r".getBytes();
            final byte[] atl0 = "ATL0\r".getBytes();
            final byte[] atl1 = "ATL1\r".getBytes();
            final byte[] rpmCmd = "010C\r".getBytes();
            final byte[] spdCmd = "010D\r".getBytes();

            setupStreams();

            writeELM(ate0);
            writeELM(ats0);
            vinBuf = readVIN();

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

        String readVIN(){
            final byte[] vinCmd = "0902\r".getBytes();
            int[][] vInts = {{7,8},{9,10},{11,12},{16,17},{18,19},{20,21},{22,23},{24,25},{26,27},{28,29},{33,34},{35,36},{37,38},{39,40},{41,42},{43,44},{45,46}};
            byte vbuf[] = new byte[64];
            byte cbuf[] = new byte[4];
            byte nbyte;
            char[] cs = new char[17];
            int i = 0;
            int startFound = 0;

            String s;
            char c;

            try {
                mmOutStream.write(vinCmd);
            } catch (IOException e) {
                Log.e(TAG, "Error occurred when sending data", e);
                return new String("");
            }
            vbuf[0] = 0x00;
            while (true) {
                try {
                    nbyte = (byte)(mmInStream.read());
                    if (nbyte != -1) {
                        if (startFound != 2) {
                            if (nbyte == 0x30){
                                startFound = 1;
                                continue;
                            }
                            if (startFound == 1 && nbyte == 0x3a){
                                startFound = 2;
                            }
                            else continue;
                        }
                        vbuf[i] = nbyte;

                        if (nbyte == 0x3e) break; /* > dec62*/
                        i++;
                        if (i == 64) break;
                    }
                    else break;
                } catch (IOException e) {
                    Log.d(TAG, "Input stream was disconnected", e);
                    break;
                }
            }
            for (i=0;i<17;i++){
                cbuf[0] = vbuf[vInts[i][0]];
                cbuf[1] = vbuf[vInts[i][1]];
                cbuf[2] = 0x0;
                cs[i] =  (char)Integer.parseInt(new String(cbuf).substring(0,2), 16);
            }
            return new String(cs);
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
