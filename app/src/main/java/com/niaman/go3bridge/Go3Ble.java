package com.niaman.go3bridge;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import java.util.*;
import java.util.function.Consumer;

public class Go3Ble {
    static final int REQ_BT=4703;

    private final Activity activity;
    private final Consumer<String> state;
    private final Consumer<String> log;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Map<String,BluetoothGatt> gatts=new HashMap<>();
    private BluetoothLeScanner scanner;
    private boolean scanning=false;

    Go3Ble(Activity activity, Consumer<String> log, Consumer<String> state) {
        this.activity=activity;
        this.log=log;
        this.state=state;
    }

    void start() {
        if(activity.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED ||
           activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){
            activity.requestPermissions(new String[]{
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            },REQ_BT);
            state.accept("GO3: צריך לאשר Bluetooth ואז הסריקה תתחיל");
            return;
        }

        BluetoothManager manager=activity.getSystemService(BluetoothManager.class);
        if(manager==null||manager.getAdapter()==null){
            state.accept("Bluetooth לא זמין בטלפון");
            return;
        }
        BluetoothAdapter adapter=manager.getAdapter();
        if(!adapter.isEnabled()){
            state.accept("הפעל Bluetooth ואז נסה שוב");
            return;
        }

        // First try a GO3 that Android already knows. On Samsung, an already-paired
        // GO3 may not advertise during a normal BLE scan, which is why nRF Connect
        // can show nothing while the glasses are still paired in Android settings.
        for(BluetoothDevice d: adapter.getBondedDevices()){
            String n=safeName(d);
            String u=n.toUpperCase(Locale.ROOT);
            if(u.contains("INMO") || u.contains("GO3")){
                state.accept("GO3 מזוהה בזיווג • מתחבר ישירות...");
                log.accept("Direct connect to bonded device: "+n+" ["+d.getAddress()+"]");
                BluetoothGatt g=d.connectGatt(activity,false,gattCallback,BluetoothDevice.TRANSPORT_LE);
                gatts.put(d.getAddress(),g);
                return;
            }
        }

        scanner=adapter.getBluetoothLeScanner();
        if(scanner==null){
            state.accept("BLE scanner לא זמין");
            return;
        }

        stopScan();
        gatts.clear();
        scanning=true;
        state.accept("לא נמצא GO3 בזיווג • סורק...");
        log.accept("No bonded GO3 found. Starting BLE scan for INMO GO3");
        scanner.startScan(scanCallback);
        handler.postDelayed(this::stopScan,15000);
    }

    void stop(){
        stopScan();
        for(BluetoothGatt g:gatts.values()){
            try{g.close();}catch(Exception ignored){}
        }
        gatts.clear();
    }

    private void stopScan(){
        if(scanning&&scanner!=null&&activity.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED){
            try{scanner.stopScan(scanCallback);}catch(Exception ignored){}
        }
        scanning=false;
    }

    private final ScanCallback scanCallback=new ScanCallback(){
        @Override public void onScanResult(int callbackType,ScanResult result){
            BluetoothDevice d=result.getDevice();
            if(d==null)return;
            String name=null;
            try{
                if(activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED)
                    name=d.getName();
            }catch(Exception ignored){}
            if(name==null&&result.getScanRecord()!=null)name=result.getScanRecord().getDeviceName();
            if(name==null)return;

            String upper=name.toUpperCase(Locale.ROOT);
            if(!upper.contains("INMO GO3"))return;

            String addr=d.getAddress();
            if(gatts.containsKey(addr))return;

            log.accept("Found "+name+" ["+addr+"] RSSI "+result.getRssi());
            state.accept("נמצא "+name+" • מתחבר...");

            if(activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)return;
            BluetoothGatt g=d.connectGatt(activity,false,gattCallback,BluetoothDevice.TRANSPORT_LE);
            gatts.put(addr,g);
        }

        @Override public void onScanFailed(int errorCode){
            scanning=false;
            state.accept("BLE scan failed: "+errorCode);
            log.accept("BLE scan failed code="+errorCode);
        }
    };

    private final BluetoothGattCallback gattCallback=new BluetoothGattCallback(){
        @Override public void onConnectionStateChange(BluetoothGatt gatt,int status,int newState){
            BluetoothDevice d=gatt.getDevice();
            String name=safeName(d);
            if(newState==BluetoothProfile.STATE_CONNECTED){
                state.accept(name+": מחובר • קורא שירותים...");
                log.accept(name+" connected, status="+status);
                if(activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED)
                    gatt.discoverServices();
            }else if(newState==BluetoothProfile.STATE_DISCONNECTED){
                state.accept(name+": נותק");
                log.accept(name+" disconnected, status="+status);
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt gatt,int status){
            String name=safeName(gatt.getDevice());
            log.accept(name+" services discovered status="+status);
            if(status!=BluetoothGatt.GATT_SUCCESS)return;

            int notifyCount=0;
            for(BluetoothGattService s:gatt.getServices()){
                log.accept(name+" SERVICE "+s.getUuid());
                for(BluetoothGattCharacteristic c:s.getCharacteristics()){
                    int p=c.getProperties();
                    log.accept("  CHAR "+c.getUuid()+" props=0x"+Integer.toHexString(p));
                    if((p&BluetoothGattCharacteristic.PROPERTY_NOTIFY)!=0 ||
                       (p&BluetoothGattCharacteristic.PROPERTY_INDICATE)!=0){
                        if(enableNotify(gatt,c))notifyCount++;
                    }
                }
            }
            state.accept(name+": GATT מוכן • notifications "+notifyCount);
            log.accept(name+" ready. Press the GO button now (single/double/long); incoming bytes will appear below.");
        }

        @Override public void onCharacteristicChanged(BluetoothGatt gatt,BluetoothGattCharacteristic c){
            byte[] v=c.getValue();
            log.accept(safeName(gatt.getDevice())+" RX "+c.getUuid()+" = "+hex(v));
        }

        @Override public void onCharacteristicRead(BluetoothGatt gatt,BluetoothGattCharacteristic c,int status){
            if(status==BluetoothGatt.GATT_SUCCESS)
                log.accept(safeName(gatt.getDevice())+" READ "+c.getUuid()+" = "+hex(c.getValue()));
        }
    };

    private boolean enableNotify(BluetoothGatt gatt,BluetoothGattCharacteristic c){
        if(activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)return false;
        try{
            if(!gatt.setCharacteristicNotification(c,true))return false;
            BluetoothGattDescriptor d=c.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
            if(d!=null){
                boolean indicate=(c.getProperties()&BluetoothGattCharacteristic.PROPERTY_INDICATE)!=0 &&
                                 (c.getProperties()&BluetoothGattCharacteristic.PROPERTY_NOTIFY)==0;
                d.setValue(indicate?BluetoothGattDescriptor.ENABLE_INDICATION_VALUE:BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                gatt.writeDescriptor(d);
            }
            return true;
        }catch(Exception e){
            log.accept("Notify error "+c.getUuid()+": "+e.getMessage());
            return false;
        }
    }

    private String safeName(BluetoothDevice d){
        if(d==null)return "BLE";
        if(activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED){
            try{
                String n=d.getName();
                if(n!=null)return n;
            }catch(Exception ignored){}
        }
        return "BLE "+d.getAddress();
    }

    private static String hex(byte[] b){
        if(b==null)return "<null>";
        StringBuilder s=new StringBuilder();
        for(byte x:b)s.append(String.format(Locale.US,"%02X ",x&0xff));
        return s.toString().trim();
    }
}
