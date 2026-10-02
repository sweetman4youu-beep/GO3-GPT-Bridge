package com.niaman.go3bridge;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.Consumer;

public class ChatGptAuth {
    private static final String AUTH="https://auth.openai.com/api/accounts/authorize";
    private static final String TOKEN="https://auth.openai.com/api/accounts/oauth/token";
    private static final String RESOURCE="https://api.openai.com/v1";
    private static final String SCOPES="openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";

    private final Activity activity;
    private final SecureStore secure;
    private final SharedPreferences prefs;

    public ChatGptAuth(Activity activity){
        this.activity=activity;
        this.secure=new SecureStore(activity);
        this.prefs=activity.getSharedPreferences("go3_auth",Context.MODE_PRIVATE);
        if(prefs.getString("host_id","").isEmpty()){
            prefs.edit().putString("host_id","urn:uuid:"+UUID.randomUUID()).apply();
        }
    }

    public boolean isSignedIn(){
        return !secure.get("access_token").isEmpty() && !prefs.getString("client_id","").isEmpty();
    }

    public void signIn(Consumer<String> status,Consumer<Boolean> done){
        BroadcastReceiver receiver=new BroadcastReceiver(){
            @Override public void onReceive(Context context,Intent intent){
                if(!AuthCallbackService.ACTION_RESULT.equals(intent.getAction()))return;
                boolean ok=intent.getBooleanExtra(AuthCallbackService.EXTRA_OK,false);
                String msg=intent.getStringExtra(AuthCallbackService.EXTRA_MESSAGE);
                try{ activity.unregisterReceiver(this); }catch(Exception ignored){}
                status.accept(msg==null?(ok?"מחובר ל-ChatGPT":"שגיאת התחברות"):msg);
                done.accept(ok);
            }
        };

        IntentFilter filter=new IntentFilter(AuthCallbackService.ACTION_RESULT);
        if(Build.VERSION.SDK_INT>=33)activity.registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED);
        else activity.registerReceiver(receiver,filter);

        try{
            status.accept("מכין התחברות מאובטחת ל-ChatGPT...");
            int port=findFreePort();
            String redirect="http://127.0.0.1:"+port+"/auth/callback";

            String state=randomUrl(24);
            String nonce=randomUrl(24);
            String verifier=randomUrl(48);
            String challenge=base64Url(MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII)));

            String savedClient=prefs.getString("client_id","");
            boolean first=savedClient.isEmpty();
            String client=first?"dynamic_agent_client":savedClient;

            Uri.Builder b=Uri.parse(AUTH).buildUpon()
                .appendQueryParameter("client_id",client)
                .appendQueryParameter("ext_agent_host_id",prefs.getString("host_id",""))
                .appendQueryParameter("response_type","code")
                .appendQueryParameter("redirect_uri",redirect)
                .appendQueryParameter("scope",SCOPES)
                .appendQueryParameter("resource",RESOURCE)
                .appendQueryParameter("state",state)
                .appendQueryParameter("nonce",nonce)
                .appendQueryParameter("code_challenge_method","S256")
                .appendQueryParameter("code_challenge",challenge);
            if(first)b.appendQueryParameter("agent_name_hint","GO3 GPT Bridge");

            Intent service=new Intent(activity,AuthCallbackService.class);
            service.putExtra("port",port);
            service.putExtra("state",state);
            service.putExtra("verifier",verifier);
            service.putExtra("client_id",client);
            service.putExtra("first",first);
            if(Build.VERSION.SDK_INT>=26)activity.startForegroundService(service);
            else activity.startService(service);

            Uri authorizeUrl=b.build();
            new Handler(Looper.getMainLooper()).postDelayed(()->{
                try{
                    status.accept("השלם את ההרשאה בדפדפן...");
                    Intent browser=new Intent(Intent.ACTION_VIEW,authorizeUrl);
                    activity.startActivity(browser);
                }catch(Exception e){
                    status.accept("לא ניתן לפתוח את הדפדפן: "+e.getMessage());
                    done.accept(false);
                }
            },350);

        }catch(Exception e){
            try{ activity.unregisterReceiver(receiver); }catch(Exception ignored){}
            status.accept("שגיאת התחברות: "+e.getMessage());
            done.accept(false);
        }
    }

    private int findFreePort()throws Exception{
        for(int p=1455;p<=1475;p++){
            try(ServerSocket test=new ServerSocket(p,1,InetAddress.getByName("127.0.0.1"))){
                return p;
            }catch(IOException ignored){}
        }
        try(ServerSocket test=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
            return test.getLocalPort();
        }
    }

    public String getAccessToken()throws Exception{
        String access=secure.get("access_token");
        long exp=prefs.getLong("expires_at",0);
        if(!access.isEmpty()&&System.currentTimeMillis()<exp-60000)return access;

        String refresh=secure.get("refresh_token");
        String client=prefs.getString("client_id","");
        if(refresh.isEmpty()||client.isEmpty())throw new IOException("נדרשת התחברות ל-ChatGPT");

        String form="grant_type=refresh_token"+
            "&client_id="+enc(client)+
            "&refresh_token="+enc(refresh)+
            "&resource="+enc(RESOURCE);

        JSONObject tok=postForm(TOKEN,form);
        access=tok.optString("access_token","");
        if(access.isEmpty())throw new IOException("Token refresh failed");

        secure.put("access_token",access);
        String newRefresh=tok.optString("refresh_token","");
        if(!newRefresh.isEmpty())secure.put("refresh_token",newRefresh);
        String idt=tok.optString("id_token","");
        if(!idt.isEmpty())secure.put("id_token",idt);

        prefs.edit()
            .putLong("expires_at",System.currentTimeMillis()+Math.max(60,tok.optLong("expires_in",3600))*1000L)
            .apply();
        return access;
    }

    public void signOut(){
        secure.remove("access_token");
        secure.remove("refresh_token");
        secure.remove("id_token");
        prefs.edit().remove("client_id").remove("scope").remove("expires_at").apply();
    }

    private JSONObject postForm(String url,String form)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("Content-Type","application/x-www-form-urlencoded");
        c.setDoOutput(true);
        try(OutputStream os=c.getOutputStream()){
            os.write(form.getBytes(StandardCharsets.UTF_8));
        }
        int status=c.getResponseCode();
        InputStream in=status>=200&&status<300?c.getInputStream():c.getErrorStream();
        String raw=read(in);
        if(status<200||status>=300)throw new IOException("OAuth "+status+": "+raw);
        return new JSONObject(raw);
    }

    private String randomUrl(int bytes){
        byte[] b=new byte[bytes];
        new SecureRandom().nextBytes(b);
        return base64Url(b);
    }

    private String base64Url(byte[] b){
        return android.util.Base64.encodeToString(
            b,
            android.util.Base64.URL_SAFE|android.util.Base64.NO_WRAP|android.util.Base64.NO_PADDING
        );
    }

    private String enc(String s)throws Exception{
        return URLEncoder.encode(s==null?"":s,"UTF-8");
    }

    private String read(InputStream in)throws Exception{
        if(in==null)return "";
        try(ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[4096]; int n;
            while((n=in.read(b))>0)out.write(b,0,n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
