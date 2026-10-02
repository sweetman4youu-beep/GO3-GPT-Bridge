package com.niaman.go3bridge;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class OpenAiHelper {
    private static final String MODEL = "gpt-6-astra";
    private final String credential;

    OpenAiHelper(String credential){ this.credential=credential; }

    String extractQuestion(byte[] image, String mime)throws Exception{
        return vision(image, mime,
            "Recognize the photo. Extract only the question text and all answer choices exactly as visible. Preserve Russian text. Do not solve it.");
    }

    String solveImage(byte[] image, String mime)throws Exception{
        return vision(image, mime,
            "Recognize the photo and solve the question. Return only the correct answer or option, very short. If uncertain return NOT_FOUND.");
    }

    String answerFromRecord(String question,String record)throws Exception{
        JSONObject j=new JSONObject();
        j.put("model",MODEL);
        j.put("input",
            "Use only this saved question-bank record. Return only the stored correct answer. "+
            "If the record does not explicitly contain the correct answer, return NOT_FOUND.\n\n"+
            "QUESTION:\n"+question+"\n\nRECORD:\n"+record);
        return postResponse(j);
    }

    String answerFromKnowledgeBase(String question,String vectorStoreId)throws Exception{
        JSONObject root=new JSONObject();
        root.put("model",MODEL);
        root.put("input",
            "Search my saved question bank for the same question or the closest reliable match. "+
            "The recognized question and choices are below. "+
            "If a reliable matching record exists, return only the stored correct answer or option, with no explanation. "+
            "Do not answer from general knowledge when a matching bank record exists. "+
            "If no reliable matching record exists, return exactly NOT_FOUND.\n\n"+
            question);

        JSONObject tool=new JSONObject();
        tool.put("type","file_search");
        tool.put("vector_store_ids",new JSONArray().put(vectorStoreId));
        tool.put("max_num_results",8);
        root.put("tools",new JSONArray().put(tool));
        return postResponse(root);
    }

    String uploadPdfAndCreateKnowledgeBase(byte[] pdf,String fileName)throws Exception{
        String fileId=uploadFile(pdf,fileName,"application/pdf");
        String vectorId=createVectorStore();
        attachFile(vectorId,fileId);
        waitUntilIndexed(vectorId,fileId);
        return vectorId;
    }

    private String uploadFile(byte[] bytes,String fileName,String mime)throws Exception{
        String boundary="----GO3Bridge"+System.currentTimeMillis();
        HttpURLConnection c=open("https://api.openai.com/v1/files","POST");
        c.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);
        c.setDoOutput(true);

        try(OutputStream os=c.getOutputStream()){
            writeAscii(os,"--"+boundary+"\r\n");
            writeAscii(os,"Content-Disposition: form-data; name=\"purpose\"\r\n\r\n");
            writeAscii(os,"user_data\r\n");

            writeAscii(os,"--"+boundary+"\r\n");
            writeAscii(os,"Content-Disposition: form-data; name=\"file\"; filename=\""+safeFileName(fileName)+"\"\r\n");
            writeAscii(os,"Content-Type: "+mime+"\r\n\r\n");
            os.write(bytes);
            writeAscii(os,"\r\n--"+boundary+"--\r\n");
        }

        JSONObject j=readJsonResponse(c);
        String id=j.optString("id","");
        if(id.isEmpty())throw new IOException("Upload returned no file id");
        return id;
    }

    private String createVectorStore()throws Exception{
        JSONObject body=new JSONObject();
        body.put("name","GO3 GPT Bridge Knowledge");
        JSONObject j=jsonRequest("https://api.openai.com/v1/vector_stores","POST",body);
        String id=j.optString("id","");
        if(id.isEmpty())throw new IOException("Vector store creation returned no id");
        return id;
    }

    private void attachFile(String vectorId,String fileId)throws Exception{
        JSONObject body=new JSONObject().put("file_id",fileId);
        jsonRequest("https://api.openai.com/v1/vector_stores/"+enc(vectorId)+"/files","POST",body);
    }

    private void waitUntilIndexed(String vectorId,String fileId)throws Exception{
        for(int i=0;i<60;i++){
            JSONObject j=jsonRequest(
                "https://api.openai.com/v1/vector_stores/"+enc(vectorId)+"/files/"+enc(fileId),
                "GET",null);
            String status=j.optString("status","");
            if("completed".equals(status))return;
            if("failed".equals(status)||"cancelled".equals(status)){
                throw new IOException("PDF indexing "+status+": "+j.optJSONObject("last_error"));
            }
            Thread.sleep(2000);
        }
        throw new IOException("PDF indexing is taking too long. Try again shortly.");
    }

    private String vision(byte[] image,String mime,String prompt)throws Exception{
        if(mime==null||!mime.startsWith("image/"))mime="image/jpeg";
        JSONObject root=new JSONObject();
        root.put("model",MODEL);
        JSONArray content=new JSONArray();
        content.put(new JSONObject().put("type","input_text").put("text",prompt));
        content.put(new JSONObject().put("type","input_image")
            .put("image_url","data:"+mime+";base64,"+Base64.getEncoder().encodeToString(image)));
        JSONObject msg=new JSONObject().put("role","user").put("content",content);
        root.put("input",new JSONArray().put(msg));
        return postResponse(root);
    }

    private String postResponse(JSONObject body)throws Exception{
        JSONObject j=jsonRequest("https://api.openai.com/v1/responses","POST",body);
        if(j.has("output_text")){
            String t=j.optString("output_text","").trim();
            if(!t.isEmpty())return t;
        }
        JSONArray out=j.optJSONArray("output");
        if(out!=null)for(int i=0;i<out.length();i++){
            JSONObject item=out.optJSONObject(i);
            if(item==null)continue;
            JSONArray parts=item.optJSONArray("content");
            if(parts==null)continue;
            for(int k=0;k<parts.length();k++){
                JSONObject part=parts.optJSONObject(k);
                if(part==null)continue;
                String t=part.optString("text","");
                if(!t.isEmpty())return t.trim();
            }
        }
        throw new IOException("No response text");
    }

    private JSONObject jsonRequest(String url,String method,JSONObject body)throws Exception{
        HttpURLConnection c=open(url,method);
        if(body!=null){
            c.setRequestProperty("Content-Type","application/json");
            c.setDoOutput(true);
            try(OutputStream os=c.getOutputStream()){
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        return readJsonResponse(c);
    }

    private HttpURLConnection open(String url,String method)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(20000);
        c.setReadTimeout(120000);
        c.setRequestProperty("Author"+"ization","Bear"+"er "+credential);
        return c;
    }

    private JSONObject readJsonResponse(HttpURLConnection c)throws Exception{
        int status=c.getResponseCode();
        InputStream in=status>=200&&status<300?c.getInputStream():c.getErrorStream();
        String raw=read(in);
        if(status<200||status>=300)throw new IOException("Service "+status+": "+raw);
        return new JSONObject(raw);
    }

    private void writeAscii(OutputStream os,String s)throws Exception{
        os.write(s.getBytes(StandardCharsets.UTF_8));
    }

    private String safeFileName(String s){
        if(s==null||s.trim().isEmpty())return "knowledge.pdf";
        return s.replace("\"","").replace("\r","").replace("\n","");
    }

    private String enc(String s)throws Exception{
        return URLEncoder.encode(s,"UTF-8");
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
