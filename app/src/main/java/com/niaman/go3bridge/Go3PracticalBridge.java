package com.niaman.go3bridge;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import java.io.*;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public class Go3PracticalBridge {
    public static final int REQ_PRACTICAL_PERMS=4917;
    private static final String CHANNEL="go3_answers";
    private final Activity activity;
    private final Consumer<String> log;

    public Go3PracticalBridge(Activity activity, Consumer<String> log){
        this.activity=activity;
        this.log=log;
        ensureChannel();
    }

    public boolean ensurePermissions(){
        java.util.ArrayList<String> need=new java.util.ArrayList<>();
        if(Build.VERSION.SDK_INT>=33){
            if(activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.POST_NOTIFICATIONS);
            if(activity.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)!=PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.READ_MEDIA_IMAGES);
        }
        if(!need.isEmpty()){
            activity.requestPermissions(need.toArray(new String[0]),REQ_PRACTICAL_PERMS);
            return false;
        }
        return true;
    }

    public boolean launchInmoGlobal(){
        try{
            Intent i=activity.getPackageManager().getLaunchIntentForPackage("com.inmo.app.googleplay");
            if(i==null){
                log.accept("PRACTICAL: INMO Global is not installed.");
                return false;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
            log.accept("PRACTICAL: opened INMO Global. Let it make the glasses READY, then return here with Back.");
            return true;
        }catch(Exception e){
            log.accept("PRACTICAL: cannot open INMO Global: "+e.getMessage());
            return false;
        }
    }

    public Uri findNewestImageSince(long sinceMs){
        long minSec=Math.max(0,(sinceMs/1000L)-3L);
        String[] projection={
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH
        };
        String selection=MediaStore.Images.Media.DATE_ADDED+" >= ?";
        String[] args={String.valueOf(minSec)};
        String sort=MediaStore.Images.Media.DATE_ADDED+" DESC";
        try(Cursor c=activity.getContentResolver().query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,projection,selection,args,sort)){
            if(c==null)return null;
            int idCol=c.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            int nameCol=c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME);
            int pathCol=c.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH);
            int count=0;
            while(c.moveToNext() && count++<25){
                long id=c.getLong(idCol);
                String name=nameCol>=0?c.getString(nameCol):"";
                String path=pathCol>=0?c.getString(pathCol):"";
                String marker=((name==null?"":name)+" "+(path==null?"":path)).toLowerCase(Locale.ROOT);
                if(marker.contains("inmo") || marker.contains("dcim") || marker.contains("camera")){
                    Uri u=ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,id);
                    log.accept("PRACTICAL: candidate synced image "+u+" name="+name+" path="+path);
                    return u;
                }
            }
        }catch(SecurityException e){
            log.accept("PRACTICAL: photo permission missing: "+e.getMessage());
        }catch(Exception e){
            log.accept("PRACTICAL: MediaStore query error: "+e.getMessage());
        }
        return null;
    }

    public void notifyAnswer(String answer){
        ensureChannel();
        String token=answer==null?"?":answer.trim();
        Notification.Builder b=Build.VERSION.SDK_INT>=26
            ? new Notification.Builder(activity,CHANNEL)
            : new Notification.Builder(activity);
        b.setSmallIcon(android.R.drawable.ic_dialog_info)
         .setContentTitle("GO3")
         .setContentText(token)
         .setStyle(new Notification.BigTextStyle().bigText(token))
         .setAutoCancel(true)
         .setOnlyAlertOnce(true);
        try{
            NotificationManager nm=activity.getSystemService(NotificationManager.class);
            if(nm!=null)nm.notify(7303,b.build());
            log.accept("PRACTICAL: answer notification posted for lens mirroring: "+token);
        }catch(SecurityException e){
            log.accept("PRACTICAL: notification permission missing.");
        }
    }

    private void ensureChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationManager nm=activity.getSystemService(NotificationManager.class);
            if(nm!=null && nm.getNotificationChannel(CHANNEL)==null){
                NotificationChannel ch=new NotificationChannel(CHANNEL,"GO3 answers",NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("Short written answers for GO3 notification mirroring");
                ch.setSound(null,null);
                ch.enableVibration(false);
                nm.createNotificationChannel(ch);
            }
        }
    }
}
