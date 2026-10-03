package com.niaman.go3bridge;

import android.content.Context;
import android.net.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import org.json.*;

public class Go3MediaProbe {
    public interface ImageListener { void onImage(byte[] data, String source); }

    private final Context context;
    private final Consumer<String> log;
    private final ImageListener imageListener;

    Go3MediaProbe(Context context, Consumer<String> log, ImageListener imageListener){
        this.context=context;
        this.log=log;
        this.imageListener=imageListener;
    }

    public void probeAfterSnapshot(){
        try{
            ConnectivityManager cm=context.getSystemService(ConnectivityManager.class);
            if(cm==null){log.accept("MEDIA: ConnectivityManager unavailable");return;}

            List<Network> wifi=new ArrayList<>();
            for(Network n:cm.getAllNetworks()){
                NetworkCapabilities nc=cm.getNetworkCapabilities(n);
                if(nc!=null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && !nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)){
                    wifi.add(n);
                }
            }
            if(wifi.isEmpty()){
                log.accept("MEDIA: no non-VPN Wi-Fi network yet; GO3 SoftAP may not be active.");
                return;
            }

            for(Network n:wifi){
                LinkProperties lp=cm.getLinkProperties(n);
                if(lp==null)continue;
                String gw=defaultGateway(lp);
                log.accept("MEDIA: Wi-Fi candidate "+n+" if="+lp.getInterfaceName()+" gateway="+gw);
                if(gw==null)continue;
                if(tryHost(n,gw))return;
            }
            log.accept("MEDIA: no GO3 image found on current Wi-Fi candidates.");
        }catch(Exception e){
            log.accept("MEDIA probe error: "+e.getMessage());
        }
    }

    private String defaultGateway(LinkProperties lp){
        for(RouteInfo r:lp.getRoutes()){
            if(r.isDefaultRoute() && r.getGateway() instanceof java.net.Inet4Address){
                return r.getGateway().getHostAddress();
            }
        }
        return null;
    }

    private boolean tryHost(Network n,String host){
        int[] ports={10000,8080};
        String[] paths={
            "/media/thumbnails/image",
            "/media/thumbnails/image/",
            "/media/list",
            "/media",
            "/"
        };
        for(int port:ports){
            for(String path:paths){
                try{
                    URL url=new URL("http://"+host+":"+port+path);
                    HttpURLConnection c=(HttpURLConnection)n.openConnection(url);
                    c.setConnectTimeout(1800);
                    c.setReadTimeout(2500);
                    c.setInstanceFollowRedirects(false);
                    c.setRequestMethod("GET");
                    int code=c.getResponseCode();
                    String type=c.getContentType();
                    byte[] body=readLimited(code>=200&&code<400?c.getInputStream():c.getErrorStream(),8*1024*1024);
                    log.accept("MEDIA GET "+host+":"+port+path+" -> "+code+" type="+type+" bytes="+body.length);
                    if(code>=200&&code<300){
                        if(isImage(type,body)){
                            imageListener.onImage(body,"http://"+host+":"+port+path);
                            return true;
                        }
                        String txt=new String(body,StandardCharsets.UTF_8);
                        List<String> candidates=extractMediaPaths(txt);
                        for(String p:candidates){
                            if(fetchCandidate(n,host,port,p))return true;
                        }
                    }
                }catch(Exception e){
                    // Keep probing; only log concise failures for endpoint discovery.
                }
            }
        }
        return false;
    }

    private boolean fetchCandidate(Network n,String host,int port,String p){
        try{
            String path=p.startsWith("http://")?p:(p.startsWith("/")?p:"/"+p);
            URL url=path.startsWith("http://")?new URL(path):new URL("http://"+host+":"+port+path);
            if(!host.equalsIgnoreCase(url.getHost()))return false;
            HttpURLConnection c=(HttpURLConnection)n.openConnection(url);
            c.setConnectTimeout(1800); c.setReadTimeout(4000);
            int code=c.getResponseCode();
            byte[] body=readLimited(code>=200&&code<400?c.getInputStream():c.getErrorStream(),12*1024*1024);
            String type=c.getContentType();
            log.accept("MEDIA candidate "+url.getPath()+" -> "+code+" type="+type+" bytes="+body.length);
            if(code>=200&&code<300 && isImage(type,body)){
                imageListener.onImage(body,url.toString());
                return true;
            }
        }catch(Exception ignored){}
        return false;
    }

    private List<String> extractMediaPaths(String s){
        LinkedHashSet<String> out=new LinkedHashSet<>();
        if(s==null)return new ArrayList<>();
        try{
            Object root=new JSONTokener(s).nextValue();
            collectJson(root,out);
        }catch(Exception ignored){}
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?i)(/[^\\s\"']+\\.(?:jpg|jpeg|png|webp))").matcher(s);
        while(m.find() && out.size()<30)out.add(m.group(1));
        return new ArrayList<>(out);
    }

    private void collectJson(Object o,Set<String> out){
        if(o==null||out.size()>=30)return;
        if(o instanceof JSONObject){
            JSONObject j=(JSONObject)o;
            Iterator<String> it=j.keys();
            while(it.hasNext())collectJson(j.opt(it.next()),out);
        }else if(o instanceof JSONArray){
            JSONArray a=(JSONArray)o;
            for(int i=0;i<a.length()&&out.size()<30;i++)collectJson(a.opt(i),out);
        }else if(o instanceof String){
            String s=(String)o;
            String l=s.toLowerCase(Locale.ROOT);
            if((l.endsWith(".jpg")||l.endsWith(".jpeg")||l.endsWith(".png")||l.endsWith(".webp")) && s.length()<512)out.add(s);
        }
    }

    private boolean isImage(String type,byte[] b){
        if(type!=null && type.toLowerCase(Locale.ROOT).startsWith("image/"))return true;
        if(b.length>=3 && (b[0]&255)==0xff && (b[1]&255)==0xd8 && (b[2]&255)==0xff)return true;
        if(b.length>=8 && b[0]==(byte)0x89 && b[1]==0x50 && b[2]==0x4e && b[3]==0x47)return true;
        return false;
    }

    private byte[] readLimited(InputStream in,int max)throws IOException{
        if(in==null)return new byte[0];
        try(InputStream x=in; ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buf=new byte[8192]; int n,total=0;
            while((n=x.read(buf))>0){
                if(total+n>max)n=max-total;
                if(n<=0)break;
                out.write(buf,0,n); total+=n;
                if(total>=max)break;
            }
            return out.toByteArray();
        }
    }
}
