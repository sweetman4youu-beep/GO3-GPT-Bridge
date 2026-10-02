package com.niaman.go3bridge;

import android.app.Activity;
import android.content.*;
import android.net.Uri;

import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class ChatGptAuth {
    private static final String AUTH="https://auth.openai.com/api/accounts/authorize";
    private static final String TOKEN="https://auth.openai.com/api/accounts/oauth/token";
    private static final String RESOURCE="https://api.openai.com/v1";
    private static final String SCOPES="openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";

    private final Activity activity;
    private final SecureStore secure;
    private final SharedPreferences prefs;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();

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

    public void signIn(Consumer<String> status, Consumer<Boolean> done){
        worker.execute(()->{
            ServerSocket server=null;
            try{
                status.accept("פותח התחברות ל-ChatGPT...");
                server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));
                server.setSoTimeout(180000);
                int port=server.getLocalPort();
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

                Intent intent=new Intent(Intent.ACTION_VIEW,b.build());
                activity.runOnUiThread(()->activity.startActivity(intent));

                Socket s=server.accept();
                BufferedReader br=new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.UTF_8));
                String request=br.readLine();
                String path=(request!=null&&request.startsWith("GET "))?request.split(" ")[1]:"";
                Uri cb=Uri.parse("http://127.0.0.1"+path);

                String returnedState=cb.getQueryParameter("state");
                String err=cb.getQueryParameter("error");
                if(!state.equals(returnedState))throw new IOException("OAuth state mismatch");
                if(err!=null&&!err.isEmpty())throw new IOException("Sign-in cancelled: "+err);

                String code=cb.getQueryParameter("code");
                String issued=cb.getQueryParameter("client_id");
                if(code==null||code.isEmpty())throw new IOException("No authorization code");
                if(first){
                    if(issued==null||issued.isEmpty())throw new IOException("No issued client id");
                    client=issued;
                }else if(issued!=null&&!issued.isEmpty()&&!issued.equals(client)){
                    throw new IOException("Unexpected client id");
                }

                String html="<html><body style='font-family:sans-serif'><h2>GO3 GPT Bridge</h2><p>ההתחברות הצליחה. אפשר לחזור לאפליקציה.</p></body></html>";
                byte[] body=html.getBytes(StandardCharsets.UTF_8);
                OutputStream os=s.getOutputStream();
                String hdr="HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n";
                os.write(hdr.getBytes(StandardCharsets.US_ASCII));
                os.write(body); os.flush(); s.close();

                JSONObject tok=exchange(code,client,verifier,redirect);
                String scopes=tok.optString("scope","");
                if(!scopes.contains("chatgpt.tokens.use.direct")){
                    throw new IOException("ChatGPT plan permission was not granted");
                }
                String access=tok.optString("access_token","");
                String refresh=tok.optString("refresh_token","");
                if(access.isEmpty())throw new IOException("No access token");

                secure.put("access_token",access);
                if(!refresh.isEmpty())secure.put("refresh_token",refresh);
                String idt=tok.optString("id_token","");
                if(!idt.isEmpty())secure.put("id_token",idt);
                prefs.edit()
                    .putString("client_id",client)
                    .putString("scope",scopes)
                    .putLong("expires_at",System.currentTimeMillis()+Math.max(60,tok.optLong("expires_in",3600))*1000L)
                    .apply();

                status.accept("מחובר ל-ChatGPT");
                done.accept(true);
            }catch(Exception e){
                status.accept("שגיאת התחברות: "+e.getMessage());
                done.accept(false);
            }finally{
                if(server!=null)try{server.close();}catch(Exception ignored){}
            }
        });
    }

    public String getAccessToken() throws Exception{
        String access=secure.get("access_token");
        long exp=prefs.getLong("expires_at",0);
        if(!access.isEmpty() && System.currentTimeMillis()<exp-60000)return access;
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
        prefs.edit().putLong("expires_at",
            System.currentTimeMillis()+Math.max(60,tok.optLong("expires_in",3600))*1000L).apply();
        return access;
    }

    public void signOut(){
        secure.remove("access_token");
        secure.remove("refresh_token");
        secure.remove("id_token");
        prefs.edit().remove("client_id").remove("scope").remove("expires_at").apply();
    }

    private JSONObject exchange(String code,String client,String verifier,String redirect)throws Exception{
        String form="grant_type=authorization_code"+
            "&client_id="+enc(client)+
            "&code="+enc(code)+
            "&code_verifier="+enc(verifier)+
            "&redirect_uri="+enc(redirect)+
            "&resource="+enc(RESOURCE);
        return postForm(TOKEN,form);
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
        return android.util.Base64.encodeToString(b,android.util.Base64.URL_SAFE|android.util.Base64.NO_WRAP|android.util.Base64.NO_PADDING);
    }

    private String enc(String s)throws Exception{
        return URLEncoder.encode(s,"UTF-8");
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
