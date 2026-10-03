package com.niaman.go3bridge;

import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import java.util.*;

public final class InmoBridgeProbe {
    private static final String PKG="com.inmo.app.googleplay";

    private InmoBridgeProbe(){}

    public static String inspect(Context ctx){
        StringBuilder out=new StringBuilder();
        PackageManager pm=ctx.getPackageManager();
        out.append("INMO package: ").append(PKG).append("\n");

        try{
            PackageInfo pi=pm.getPackageInfo(PKG,
                PackageManager.GET_ACTIVITIES|
                PackageManager.GET_SERVICES|
                PackageManager.GET_RECEIVERS|
                PackageManager.GET_PROVIDERS|
                PackageManager.MATCH_DISABLED_COMPONENTS);

            out.append("version: ").append(pi.versionName).append(" (").append(pi.getLongVersionCode()).append(")\n\n");

            appendActivities(out,pi.activities);
            appendServices(out,pi.services);
            appendReceivers(out,pi.receivers);
            appendProviders(out,pi.providers);

            out.append("\nIntent handlers\n");
            probeIntent(pm,out,new Intent(Intent.ACTION_SEND).setType("image/*").setPackage(PKG),"SEND image/*");
            probeIntent(pm,out,new Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(PKG),"SEND text/plain");
            probeIntent(pm,out,new Intent(Intent.ACTION_VIEW, Uri.parse("inmo://")).setPackage(PKG),"VIEW inmo://");
            probeIntent(pm,out,new Intent(Intent.ACTION_VIEW, Uri.parse("inmogo://")).setPackage(PKG),"VIEW inmogo://");
            probeIntent(pm,out,new Intent(Intent.ACTION_VIEW, Uri.parse("go3://")).setPackage(PKG),"VIEW go3://");

            Intent launch=pm.getLaunchIntentForPackage(PKG);
            out.append("launcher: ").append(launch==null?"none":String.valueOf(launch.getComponent())).append("\n");
        }catch(Exception e){
            out.append("ERROR: ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append("\n");
        }
        return out.toString();
    }

    private static boolean interesting(String n){
        if(n==null)return false;
        String s=n.toLowerCase(Locale.ROOT);
        return s.contains("ai")||s.contains("camera")||s.contains("photo")||
               s.contains("image")||s.contains("vision")||s.contains("recogn")||
               s.contains("translate")||s.contains("chat")||s.contains("glass")||
               s.contains("go3")||s.contains("super");
    }

    private static void appendActivities(StringBuilder out,ActivityInfo[] xs){
        out.append("Exported activities (AI/camera candidates)\n");
        int n=0;
        if(xs!=null)for(ActivityInfo x:xs)if(x.exported&&interesting(x.name)){
            out.append(" A ").append(x.name).append("\n"); n++;
        }
        if(n==0)out.append(" none\n");
    }

    private static void appendServices(StringBuilder out,ServiceInfo[] xs){
        out.append("Exported services (candidates)\n");
        int n=0;
        if(xs!=null)for(ServiceInfo x:xs)if(x.exported&&interesting(x.name)){
            out.append(" S ").append(x.name).append("\n"); n++;
        }
        if(n==0)out.append(" none\n");
    }

    private static void appendReceivers(StringBuilder out,ActivityInfo[] xs){
        out.append("Exported receivers (candidates)\n");
        int n=0;
        if(xs!=null)for(ActivityInfo x:xs)if(x.exported&&interesting(x.name)){
            out.append(" R ").append(x.name).append("\n"); n++;
        }
        if(n==0)out.append(" none\n");
    }

    private static void appendProviders(StringBuilder out,ProviderInfo[] xs){
        out.append("Exported providers (candidates)\n");
        int n=0;
        if(xs!=null)for(ProviderInfo x:xs)if(x.exported&&interesting(x.name)){
            out.append(" P ").append(x.name);
            if(x.authority!=null)out.append(" authority=").append(x.authority);
            out.append("\n"); n++;
        }
        if(n==0)out.append(" none\n");
    }

    private static void probeIntent(PackageManager pm,StringBuilder out,Intent i,String label){
        try{
            List<ResolveInfo> rs=pm.queryIntentActivities(i,PackageManager.MATCH_DEFAULT_ONLY);
            out.append(label).append(": ");
            if(rs==null||rs.isEmpty()){out.append("none\n");return;}
            for(int k=0;k<rs.size();k++){
                if(k>0)out.append(", ");
                ActivityInfo a=rs.get(k).activityInfo;
                out.append(a==null?"?":a.name);
            }
            out.append("\n");
        }catch(Exception e){
            out.append(label).append(": error ").append(e.getMessage()).append("\n");
        }
    }
}
