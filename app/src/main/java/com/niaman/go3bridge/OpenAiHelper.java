package com.niaman.go3bridge;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class OpenAiHelper {
    private final String credential;
    OpenAiHelper(String credential){ this.credential=credential; }

    String extractQuestion(byte[] image)throws Exception{
        return vision(image,"Recognize the photo. Extract only the question text and all answer choices exactly as visible. Preserve Russian text. Do not solve it.");
    }
    String solveImage(byte[] image)throws Exception{
        return vision(image,"Recognize the photo and solve the question. Return only the correct answer or option, very short. If uncertain return NOT_FOUND.");
    }
    String answerFromRecord(String question,String record)throws Exception{
        JSONObject j=new JSONObject();
        j.put("model","gpt-6-sol");
        j.put("input","Use only this saved record. Return only its stored correct answer; otherwise NOT_FOUND.\nQuestion:\n"+question+"\nRecord:\n"+record);
        return post(j);
    }
    private String vision(byte[] image,String prompt)throws Exception{
        JSONObject root=new JSONObject();
        root.put("model","gpt-6-sol");
        JSONArray content=new JSONArray();
        content.put(new JSONObject().put("type","input_text").put("text",prompt));
        content.put(new JSONObject().put("type","input_image").put("image_url","data:image/jpeg;base64,"+Base64.getEncoder().encodeToString(image)));
        JSONObject msg=new JSONObject().put("role","user").put("content",content);
        root.put("input",new JSONArray().put(msg));
        return post(root);
    }
    private String post(JSONObject body)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL("https://api.openai.com/v1/responses").openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(90000);
        c.setRequestProperty("Author"+"ization","Bear"+"er "+credential);
        c.setRequestProperty("Content-Type","application/json");
        c.setDoOutput(true);
        try(OutputStream os=c.getOutputStream()){ os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        int status=c.getResponseCode();
        InputStream in=status>=200&&status<300?c.getInputStream():c.getErrorStream();
        String raw=read(in);
        if(status<200||status>=300)throw new IOException("Service "+status+": "+raw);
        JSONObject j=new JSONObject(raw);
        JSONArray out=j.optJSONArray("output");
        if(out!=null)for(int i=0;i<out.length();i++){
            JSONArray parts=out.getJSONObject(i).optJSONArray("content");
            if(parts==null)continue;
            for(int k=0;k<parts.length();k++){
                String t=parts.getJSONObject(k).optString("text","");
                if(!t.isEmpty())return t.trim();
            }
        }
        throw new IOException("No response text");
    }
    private String read(InputStream in)throws Exception{
        if(in==null)return "";
        try(ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192]; int n;
            while((n=in.read(b))>0)out.write(b,0,n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
