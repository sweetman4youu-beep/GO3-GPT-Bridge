package com.niaman.go3bridge;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;

import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class AuthCallbackService extends Service {
    public static final String ACTION_RESULT="com.niaman.go3bridge.AUTH_RESULT";
    public static final String EXTRA_OK="ok";
    public static final String EXTRA_MESSAGE="message";

    private static final String TOKEN="https://auth.openai.com/api/accounts/oauth/token";
    private static final String RESOURCE="https://api.openai.com/v1";
    private static final int NOTIF_ID=73;
    private volatile ServerSocket server;

    @Override public void onCreate(){
        super.onCreate();
        ensureChannel();
        Notification n=new Notification.Builder(this,"go3_auth")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("GO3 GPT Bridge")
            .setContentText("Completing ChatGPT sign-in…")
            .setOngoing(true)
            .build();
        if(Build.VERSION.SDK_INT>=34)startForeground(NOTIF_ID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);
        else startForeground(NOTIF_ID,n);
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null)return START_NOT_STICKY;
        final int port=intent.getIntExtra("port",1455);
        final String state=intent.getStringExtra("state");
        final String verifier=intent.getStringExtra("verifier");
        final String requestedClient=intent.getStringExtra("client_id");
        final boolean first=intent.getBooleanExtra("first",false);
        new Thread(()->runFlow(port,state,verifier,requestedClient,first),"go3-auth-listener").start();
        return START_NOT_STICKY;
    }

    private void runFlow(int port,String state,String verifier,String requestedClient,boolean first){
        try{
            server=new ServerSocket(port,1,InetAddress.getByName("127.0.0.1"));
            server.setSoTimeout(180000);

            Socket s=server.accept();
            s.setSoTimeout(15000);
            BufferedReader br=new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.UTF_8));
            String request=br.readLine();
            if(request==null||!request.startsWith("GET "))throw new IOException("Invalid callback request");
            String[] parts=request.split(" ");
            if(parts.length<2)throw new IOException("Invalid callback request");
            Uri cb=Uri.parse("http://127.0.0.1"+parts[1]);

            String returnedState=cb.getQueryParameter("state");
            String err=cb.getQueryParameter("error");
            if(state==null||!state.equals(returnedState))throw new IOException("OAuth state mismatch");
            if(err!=null&&!err.isEmpty()){
                writePage(s,false,"ההתחברות בוטלה: "+err);
                throw new IOException("Sign-in cancelled: "+err);
            }

            String code=cb.getQueryParameter("code");
            String issued=cb.getQueryParameter("client_id");
            if(code==null||code.isEmpty()){
                writePage(s,false,"לא התקבל קוד התחברות.");
                throw new IOException("No authorization code");
            }

            String client=requestedClient;
            if(first){
                if(issued==null||issued.isEmpty()){
                    writePage(s,false,"הרישום לא הושלם.");
                    throw new IOException("No issued client id");
                }
                client=issued;
            }else if(issued!=null&&!issued.isEmpty()&&!issued.equals(client)){
                writePage(s,false,"זוהה client_id שונה מהצפוי.");
                throw new IOException("Unexpected client id");
            }

            writePage(s,true,"ההרשאה התקבלה. אפשר לחזור לאפליקציה.");
            try{s.close();}catch(Exception ignored){}

            String redirect="http://127.0.0.1:"+port+"/auth/callback";
            JSONObject tok=exchange(code,client,verifier,redirect);
            String scopes=tok.optString("scope","");
            if(!scopes.contains("chatgpt.tokens.use.direct"))
                throw new IOException("ChatGPT plan permission was not granted");

            String access=tok.optString("access_token","");
            String refresh=tok.optString("refresh_token","");
            if(access.isEmpty())throw new IOException("No access token");

            SecureStore secure=new SecureStore(this);
            secure.put("access_token",access);
            if(!refresh.isEmpty())secure.put("refresh_token",refresh);
            String idt=tok.optString("id_token","");
            if(!idt.isEmpty())secure.put("id_token",idt);

            getSharedPreferences("go3_auth",MODE_PRIVATE).edit()
                .putString("client_id",client)
                .putString("scope",scopes)
                .putLong("expires_at",System.currentTimeMillis()+Math.max(60,tok.optLong("expires_in",3600))*1000L)
                .apply();

            sendResult(true,"מחובר ל-ChatGPT");
        }catch(SocketTimeoutException e){
            sendResult(false,"פג זמן ההתחברות. נסה שוב.");
        }catch(Exception e){
            sendResult(false,"שגיאת התחברות: "+e.getMessage());
        }finally{
            if(server!=null)try{server.close();}catch(Exception ignored){}
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private JSONObject exchange(String code,String client,String verifier,String redirect)throws Exception{
        String form="grant_type=authorization_code"+
            "&client_id="+enc(client)+
            "&code="+enc(code)+
            "&code_verifier="+enc(verifier)+
            "&redirect_uri="+enc(redirect)+
            "&resource="+enc(RESOURCE);

        HttpURLConnection c=(HttpURLConnection)new URL(TOKEN).openConnection();
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

    private void writePage(Socket s,boolean ok,String message){
        try{
            String color=ok?"#188038":"#b3261e";
            String html="<html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'></head>"+
                "<body style='font-family:sans-serif;padding:32px;text-align:center'>"+
                "<h2 style='color:"+color+"'>GO3 GPT Bridge</h2><p>"+escape(message)+"</p>"+
                "<p><a href='go3bridge090://auth-done' style='font-size:20px'>חזרה לאפליקציה</a></p></body></html>";
            byte[] body=html.getBytes(StandardCharsets.UTF_8);
            OutputStream os=s.getOutputStream();
            String hdr="HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "+body.length+"\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n";
            os.write(hdr.getBytes(StandardCharsets.US_ASCII));
            os.write(body);
            os.flush();
        }catch(Exception ignored){}
    }

    private void sendResult(boolean ok,String msg){
        Intent i=new Intent(ACTION_RESULT);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_OK,ok);
        i.putExtra(EXTRA_MESSAGE,msg);
        sendBroadcast(i);
    }

    private void ensureChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel ch=new NotificationChannel("go3_auth","ChatGPT sign-in",NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Keeps the secure ChatGPT sign-in callback active");
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
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

    private String escape(String s){
        if(s==null)return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
    }

    @Override public void onDestroy(){
        if(server!=null)try{server.close();}catch(Exception ignored){}
        super.onDestroy();
    }

    @Override public android.os.IBinder onBind(Intent intent){ return null; }
}
