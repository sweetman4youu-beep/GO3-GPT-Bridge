package com.niaman.go3bridge;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.*;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Go3Ble {
    static final int REQ_BT=4703;

    interface PacketListener {
        void onPacket(UUID characteristic, byte[] data);
    }

    private final Activity activity;
    private final Consumer<String> state;
    private final Consumer<String> log;
    private final PacketListener packetListener;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Map<String,BluetoothGatt> gatts=new HashMap<>();
    private final Map<BluetoothGatt,Queue<BluetoothGattDescriptor>> descriptorQueues=new HashMap<>();
    private BluetoothLeScanner scanner;
    private boolean scanning=false;
    private int scanRound=0;
    private static final int MAX_SCAN_ROUNDS=3;
    private final Set<String> seen=new HashSet<>();
    private static final UUID GO3_SERVICE=UUID.fromString("00002020-0000-1000-8000-00805f9b34fb");
    private static final UUID GO3_WRITE=UUID.fromString("00002021-0000-1000-8000-00805f9b34fb");
    private static final UUID GO3_RX1=UUID.fromString("00002022-0000-1000-8000-00805f9b34fb");
    private static final UUID GO3_RX2=UUID.fromString("00002023-0000-1000-8000-00805f9b34fb");

    Go3Ble(Activity activity, Consumer<String> log, Consumer<String> state, PacketListener packetListener) {
        this.activity=activity;
        this.log=log;
        this.state=state;
        this.packetListener=packetListener;
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

        // The Android Settings entry for GO3 can be the Classic audio endpoint.
        // Do not mistake that address for the BLE control endpoint. Log it, then
        // independently scan all BLE advertisements for the GO3 control service.
        for(BluetoothDevice d: adapter.getBondedDevices()){
            String n=safeName(d);
            String u=n.toUpperCase(Locale.ROOT);
            if(u.contains("INMO") || u.contains("GO3")){
                log.accept("Paired Android endpoint: "+n+" ["+d.getAddress()+"] type="+deviceType(d)+
                    " — keeping it only as reference; searching separately for GO3 BLE control.");
            }
        }

        // First, try the last BLE address that connected successfully.
        SharedPreferences prefs=activity.getSharedPreferences("go3_bridge",Activity.MODE_PRIVATE);
        String lastBle=prefs.getString("go3_ble_addr","");
        if(lastBle!=null&&!lastBle.isEmpty()){
            try{
                BluetoothDevice last=adapter.getRemoteDevice(lastBle);
                state.accept("מנסה חיבור ישיר ל-GO3 האחרון...");
                log.accept("Trying cached GO3 BLE address "+lastBle);
                BluetoothGatt g=last.connectGatt(activity,false,gattCallback,BluetoothDevice.TRANSPORT_LE);
                gatts.put(lastBle,g);
                handler.postDelayed(()->{
                    if(gatts.containsKey(lastBle)){
                        log.accept("Cached GO3 BLE did not become ready quickly; starting discovery scan in parallel.");
                        startBleScan(adapter);
                    }
                },6000);
                return;
            }catch(Exception e){
                log.accept("Cached GO3 BLE connect failed immediately: "+e.getMessage());
            }
        }

        scanner=adapter.getBluetoothLeScanner();
        if(scanner==null){
            state.accept("BLE scanner לא זמין");
            return;
        }

        startBleScan(adapter);
    }

    private void startBleScan(BluetoothAdapter adapter){
        if(activity.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED)return;
        if(scanner==null)scanner=adapter.getBluetoothLeScanner();
        if(scanner==null){state.accept("BLE scanner לא זמין");return;}
        stopScan();
        seen.clear();
        scanning=true;
        scanRound++;
        state.accept("מחפש GO3 BLE ישיר • ניסיון "+scanRound+"/"+MAX_SCAN_ROUNDS);
        log.accept("Direct BLE scan round "+scanRound+" started. Target service="+GO3_SERVICE+"; name not required.");
        ScanSettings settings=new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        scanner.startScan(new ArrayList<>(),settings,scanCallback);
        handler.postDelayed(()->{
            stopScan();
            boolean ready=false;
            for(BluetoothGatt g:gatts.values()){
                try{
                    BluetoothGattService s=g.getService(GO3_SERVICE);
                    if(s!=null){ready=true;break;}
                }catch(Exception ignored){}
            }
            if(!ready && scanRound<MAX_SCAN_ROUNDS){
                log.accept("GO3 control not found yet. Restarting scan automatically; wake glasses or press GO once.");
                handler.postDelayed(()->startBleScan(adapter),1200);
            }else if(!ready){
                state.accept("לא נמצא GO3 BLE אחרי 3 ניסיונות");
                log.accept("No GO3 BLE control endpoint after 3 scan rounds. Keep glasses awake and try again.");
                scanRound=0;
            }else{
                scanRound=0;
            }
        },20000);
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
            ScanRecord rec=result.getScanRecord();
            String name=null;
            try{
                if(activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED)
                    name=d.getName();
            }catch(Exception ignored){}
            if(name==null&&rec!=null)name=rec.getDeviceName();

            List<android.os.ParcelUuid> advertised=rec==null?null:rec.getServiceUuids();
            boolean has2020=false;
            if(advertised!=null){
                for(android.os.ParcelUuid pu:advertised){
                    if(GO3_SERVICE.equals(pu.getUuid())){ has2020=true; break; }
                }
            }
            String upper=name==null?"":name.toUpperCase(Locale.ROOT);
            boolean nameLooksGo3=upper.contains("INMO") || upper.contains("GO3");

            String addr=d.getAddress();
            if((has2020||nameLooksGo3) && seen.add(addr)){
                log.accept("BLE candidate name="+(name==null?"<unnamed>":name)+
                    " addr="+addr+" RSSI="+result.getRssi()+
                    " services="+(advertised==null?"[]":advertised));
            }

            if(!has2020 && !nameLooksGo3)return;
            if(gatts.containsKey(addr))return;

            state.accept("נמצא מועמד GO3 BLE • מתחבר...");
            log.accept("Connecting BLE candidate "+addr+" (service2020="+has2020+")");
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
                if(activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED){
                    try{ gatt.requestMtu(517); }catch(Exception ignored){}
                    gatt.discoverServices();
                }
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
            boolean controlService=false;
            for(BluetoothGattService s:gatt.getServices()){
                log.accept(name+" SERVICE "+s.getUuid());
                if(GO3_SERVICE.equals(s.getUuid())) controlService=true;
                for(BluetoothGattCharacteristic c:s.getCharacteristics()){
                    int p=c.getProperties();
                    log.accept("  CHAR "+c.getUuid()+" props=0x"+Integer.toHexString(p));
                    boolean targetRx=GO3_RX1.equals(c.getUuid())||GO3_RX2.equals(c.getUuid());
                    if(targetRx || (p&BluetoothGattCharacteristic.PROPERTY_NOTIFY)!=0 ||
                       (p&BluetoothGattCharacteristic.PROPERTY_INDICATE)!=0){
                        if(enableNotify(gatt,c))notifyCount++;
                    }
                    if(GO3_WRITE.equals(c.getUuid())){
                        log.accept("GO3 write channel 0x2021 FOUND");
                    }
                }
            }
            if(controlService){
                stopScan();
                try{
                    activity.getSharedPreferences("go3_bridge",Activity.MODE_PRIVATE).edit()
                        .putString("go3_ble_addr",gatt.getDevice().getAddress()).apply();
                    log.accept("Saved working GO3 BLE address "+gatt.getDevice().getAddress());
                }catch(Exception ignored){}
                state.accept("GO3 BLE ישיר מחובר • שירות 0x2020 נמצא");
                log.accept("SUCCESS: GO3 control service 0x2020 found. Notifications="+notifyCount+
                    ". Press GO / take a photo now; raw inbound frames will be captured.");
            }else{
                state.accept(name+": מחובר אבל 0x2020 לא נמצא");
                log.accept("Connected candidate has no GO3 service 0x2020; continuing discovery.");
            }
        }

        @Override public void onCharacteristicChanged(BluetoothGatt gatt,BluetoothGattCharacteristic c){
            byte[] v=c.getValue();
            byte[] copy=v==null?new byte[0]:Arrays.copyOf(v,v.length);
            log.accept(safeName(gatt.getDevice())+" RX "+c.getUuid()+" len="+copy.length+" = "+hex(copy));
            if(packetListener!=null)packetListener.onPacket(c.getUuid(),copy);
        }

        @Override public void onMtuChanged(BluetoothGatt gatt,int mtu,int status){
            log.accept(safeName(gatt.getDevice())+" MTU="+mtu+" status="+status);
        }

        @Override public void onDescriptorWrite(BluetoothGatt gatt,BluetoothGattDescriptor descriptor,int status){
            Queue<BluetoothGattDescriptor> q=descriptorQueues.get(gatt);
            if(q!=null){
                q.poll();
                BluetoothGattDescriptor next=q.peek();
                if(next!=null && activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED){
                    try{gatt.writeDescriptor(next);}catch(Exception e){log.accept("Descriptor queue error: "+e.getMessage());}
                }
            }
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
                Queue<BluetoothGattDescriptor> q=descriptorQueues.computeIfAbsent(gatt,k->new ArrayDeque<>());
                boolean idle=q.isEmpty();
                q.add(d);
                if(idle)gatt.writeDescriptor(d);
            }
            return true;
        }catch(Exception e){
            log.accept("Notify error "+c.getUuid()+": "+e.getMessage());
            return false;
        }
    }

    private String deviceType(BluetoothDevice d){
        try{
            switch(d.getType()){
                case BluetoothDevice.DEVICE_TYPE_CLASSIC:return "CLASSIC";
                case BluetoothDevice.DEVICE_TYPE_LE:return "LE";
                case BluetoothDevice.DEVICE_TYPE_DUAL:return "DUAL";
                default:return "UNKNOWN";
            }
        }catch(Exception e){return "UNKNOWN";}
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
