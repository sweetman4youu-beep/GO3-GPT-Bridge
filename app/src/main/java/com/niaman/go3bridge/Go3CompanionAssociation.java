package com.niaman.go3bridge;

import android.app.Activity;
import android.app.PendingIntent;
import android.companion.AssociationRequest;
import android.companion.BluetoothLeDeviceFilter;
import android.companion.CompanionDeviceManager;
import android.content.*;
import android.os.*;
import android.os.ParcelUuid;
import java.util.UUID;
import java.util.function.Consumer;
import android.bluetooth.le.ScanFilter;

public class Go3CompanionAssociation {
    public static final int REQ_ASSOC=4816;
    private static final UUID GO3_SERVICE=UUID.fromString("00002020-0000-1000-8000-00805f9b34fb");
    private final Activity activity;
    private final Consumer<String> log;

    public Go3CompanionAssociation(Activity activity, Consumer<String> log){
        this.activity=activity;
        this.log=log;
    }

    public void associate(){
        try{
            CompanionDeviceManager cdm=activity.getSystemService(CompanionDeviceManager.class);
            if(cdm==null){log.accept("COMPANION: Android CompanionDeviceManager unavailable");return;}

            ScanFilter sf=new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(GO3_SERVICE))
                .build();
            BluetoothLeDeviceFilter filter=new BluetoothLeDeviceFilter.Builder()
                .setScanFilter(sf)
                .build();
            AssociationRequest request=new AssociationRequest.Builder()
                .addDeviceFilter(filter)
                .setSingleDevice(false)
                .build();

            log.accept("COMPANION: requesting Android association for GO3 service 0x2020");
            cdm.associate(request,new CompanionDeviceManager.Callback(){
                @Override public void onAssociationPending(IntentSender chooserLauncher){
                    try{
                        activity.startIntentSenderForResult(chooserLauncher,REQ_ASSOC,null,0,0,0);
                        log.accept("COMPANION: Android chooser opened; select INMO GO3");
                    }catch(IntentSender.SendIntentException e){
                        log.accept("COMPANION chooser error: "+e.getMessage());
                    }
                }

                @Override public void onAssociationCreated(android.companion.AssociationInfo associationInfo){
                    log.accept("COMPANION: association created id="+associationInfo.getId()+
                        " device="+associationInfo.getDeviceMacAddress());
                }

                @Override public void onFailure(CharSequence error){
                    log.accept("COMPANION association failed: "+error);
                }
            },null);
        }catch(Exception e){
            log.accept("COMPANION error: "+e.getMessage());
        }
    }

    public void reportAssociations(){
        try{
            CompanionDeviceManager cdm=activity.getSystemService(CompanionDeviceManager.class);
            if(cdm==null)return;
            if(Build.VERSION.SDK_INT>=33){
                java.util.List<android.companion.AssociationInfo> infos=cdm.getMyAssociations();
                log.accept("COMPANION: Android associations="+infos.size());
                for(android.companion.AssociationInfo i:infos){
                    log.accept("COMPANION: id="+i.getId()+" mac="+i.getDeviceMacAddress()+
                        " selfManaged="+i.isSelfManaged());
                }
            }else{
                log.accept("COMPANION: associations="+cdm.getAssociations());
            }
        }catch(Exception e){log.accept("COMPANION association list error: "+e.getMessage());}
    }
}
