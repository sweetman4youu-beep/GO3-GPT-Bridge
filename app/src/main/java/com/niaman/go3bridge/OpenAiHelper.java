package com.niaman.go3bridge;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class OpenAiHelper {
    private static final String MODEL="gpt-6.1-sol";
    private final String accessToken;

    OpenAiHelper(String accessToken){ this.accessToken=accessToken; }

    String extractQuestion(byte[] image,String mime)throws Exception{
        return vision(image,mime,
            "Recognize the photo. Extract only the question text and all answer choices exactly as visible. Preserve Russian text. Do not solve it.");
    }

    String solveImage(byte[] image,String mime)throws Exception{
        return vision(image,mime,
            "Recognize the photo and solve the multiple-choice question. Return only the number of the correct visible option, with no words or punctuation. The question may have 2, 3, 4, or more visible options. If the stored bank did not match, make the best supported choice from the visible question instead of returning NOT_FOUND.");
    }

    String answerFromRecord(String question,String record)throws Exception{
        String prompt=
            "Use only the saved question-bank record below. Return only the stored correct answer or option. "+
            "If the record does not explicitly contain a correct answer or is not a reliable match, return exactly NOT_FOUND.\n\n"+
            "QUESTION SEEN:\n"+question+"\n\nSAVED RECORD:\n"+record;
        return text(prompt);
    }

    private String text(String prompt)throws Exception{
        JSONObject root=base();
        JSONArray content=new JSONArray()
            .put(new JSONObject().put("type","input_text").put("text",prompt));
        JSONObject msg=new JSONObject().put("role","user").put("content",content);
        root.put("input",new JSONArray().put(msg));
        return postStream(root);
    }

    private String vision(byte[] image,String mime,String prompt)throws Exception{
        if(mime==null||!mime.startsWith("image/"))mime="image/jpeg";
        JSONObject root=base();
        JSONArray content=new JSONArray();
        content.put(new JSONObject().put("type","input_text").put("text",prompt));
        content.put(new JSONObject()
            .put("type","input_image")
            .put("image_url","data:"+mime+";base64,"+Base64.getEncoder().encodeToString(image)));
        JSONObject msg=new JSONObject().put("role","user").put("content",content);
        root.put("input",new JSONArray().put(msg));
        return postStream(root);
    }

    private JSONObject base()throws Exception{
        JSONObject root=new JSONObject();
        root.put("model",MODEL);
        root.put("store",false);
        root.put("stream",true);
        return root;
    }

    private String postStream(JSONObject body)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL("https://api.openai.com/v1/responses").openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(120000);
        c.setRequestProperty("Author"+"ization","Bear"+"er "+accessToken);
        c.setRequestProperty("Content-Type","application/json");
        c.setRequestProperty("Accept","text/event-stream");
        c.setDoOutput(true);

        try(OutputStream os=c.getOutputStream()){
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int status=c.getResponseCode();
        if(status<200||status>=300){
            String raw=readAll(c.getErrorStream());
            throw new IOException("OpenAI "+status+": "+raw);
        }

        StringBuilder out=new StringBuilder();
        try(BufferedReader br=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8))){
            String line;
            while((line=br.readLine())!=null){
                if(!line.startsWith("data:"))continue;
                String data=line.substring(5).trim();
                if(data.isEmpty()||"[DONE]".equals(data))continue;
                JSONObject ev;
                try{ ev=new JSONObject(data); }catch(Exception ignored){ continue; }
                String type=ev.optString("type","");
                if("response.output_text.delta".equals(type)){
                    out.append(ev.optString("delta",""));
                }else if("error".equals(type)){
                    String msg=ev.optString("message",ev.toString());
                    throw new IOException(msg);
                }else if("response.failed".equals(type)){
                    throw new IOException(ev.toString());
                }
            }
        }
        String result=out.toString().trim();
        if(result.isEmpty())throw new IOException("No response text");
        return result;
    }

    private String readAll(InputStream in)throws Exception{
        if(in==null)return "";
        try(ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[4096]; int n;
            while((n=in.read(b))>0)out.write(b,0,n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
