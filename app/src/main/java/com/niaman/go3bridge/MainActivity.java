package com.niaman.go3bridge;

import android.app.Activity;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    static final int BANK=10, IMAGE=11;

    EditText key;
    TextView status, result, diag;
    String bank="";
    String vectorStoreId="";
    String knowledgeName="";
    final ExecutorService worker=Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        restoreKnowledge();

        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24,24,24,24);

        TextView title=new TextView(this);
        title.setText("GO3 GPT Bridge");
        title.setTextSize(26);
        root.addView(title);

        TextView sub=new TextView(this);
        sub.setText("PDF מלא / מאגר שאלות → זיהוי צילום → תשובה מהמאגר → GPT אם לא נמצא");
        sub.setTextSize(15);
        root.addView(sub);

        key=new EditText(this);
        key.setHint("OpenAI API key");
        key.setSingleLine(true);
        root.addView(key,new LinearLayout.LayoutParams(-1,-2));

        Button load=new Button(this);
        load.setText("1. טען PDF מלא / TXT");
        root.addView(load);

        Button solve=new Button(this);
        solve.setText("2. בחר צילום שאלה ופתור");
        root.addView(solve);

        Button go3=new Button(this);
        go3.setText("3. סרוק וחבר GO3");
        root.addView(go3);

        status=new TextView(this);
        status.setText(vectorStoreId.isEmpty() ? "מאגר: לא נטען | GO3: לא מחובר" :
            "PDF מוכן: "+knowledgeName+" | GO3: לא מחובר");
        status.setTextSize(16);
        root.addView(status);

        result=new TextView(this);
        result.setTextSize(20);
        result.setPadding(12,20,12,20);
        result.setText("תשובה: —");
        root.addView(result,new LinearLayout.LayoutParams(-1,-2));

        diag=new TextView(this);
        diag.setTextSize(12);
        root.addView(diag,new LinearLayout.LayoutParams(-1,-2));

        ScrollView sc=new ScrollView(this);
        sc.addView(root);
        setContentView(sc);

        load.setOnClickListener(v->pickBank());
        solve.setOnClickListener(v->pickImage());
        go3.setOnClickListener(v->new Go3Ble(this, this::log, s->runOnUiThread(()->status.setText(s))).start());
    }

    void restoreKnowledge(){
        SharedPreferences p=getSharedPreferences("go3_bridge",MODE_PRIVATE);
        vectorStoreId=p.getString("vector_store_id","");
        knowledgeName=p.getString("knowledge_name","");
    }

    void pickBank(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/pdf","text/plain"});
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i,BANK);
    }

    void pickImage(){
        if(key.getText().toString().trim().isEmpty()){
            Toast.makeText(this,"הכנס OpenAI API key",Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i,IMAGE);
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(resultCode!=RESULT_OK||data==null||data.getData()==null)return;
        Uri u=data.getData();
        if(requestCode==BANK) loadKnowledge(u);
        if(requestCode==IMAGE) solve(u);
    }

    void loadKnowledge(Uri u){
        String mime=getContentResolver().getType(u);
        String name=getName(u);
        boolean pdf="application/pdf".equalsIgnoreCase(mime) ||
            (name!=null&&name.toLowerCase(Locale.ROOT).endsWith(".pdf"));

        if(pdf){
            String k=key.getText().toString().trim();
            if(k.isEmpty()){
                Toast.makeText(this,"כדי להעלות PDF מלא יש להכניס OpenAI API key",Toast.LENGTH_LONG).show();
                return;
            }
            status.setText("מעלה PDF ומכין מאגר חיפוש...");
            worker.execute(()->{
                try{
                    long size=getLength(u);
                    if(size>50L*1024L*1024L)
                        throw new IOException("PDF גדול מ-50MB");
                    byte[] bytes=readBytes(u);
                    OpenAiHelper ai=new OpenAiHelper(k);
                    String vs=ai.uploadPdfAndCreateKnowledgeBase(bytes,name==null?"knowledge.pdf":name);
                    vectorStoreId=vs;
                    knowledgeName=name==null?"knowledge.pdf":name;
                    getSharedPreferences("go3_bridge",MODE_PRIVATE).edit()
                        .putString("vector_store_id",vectorStoreId)
                        .putString("knowledge_name",knowledgeName)
                        .apply();
                    bank="";
                    runOnUiThread(()->status.setText("PDF מוכן לחיפוש: "+knowledgeName));
                    log("Vector store ready: "+vectorStoreId);
                }catch(Exception e){
                    runOnUiThread(()->status.setText("שגיאה בהעלאת PDF"));
                    log("PDF error: "+e.getMessage());
                }
            });
        }else{
            worker.execute(()->{
                try{
                    bank=readText(u);
                    vectorStoreId="";
                    knowledgeName=name==null?"TXT":name;
                    getSharedPreferences("go3_bridge",MODE_PRIVATE).edit()
                        .remove("vector_store_id")
                        .putString("knowledge_name",knowledgeName)
                        .apply();
                    runOnUiThread(()->status.setText("TXT נטען: "+bank.length()+" תווים"));
                }catch(Exception e){ log("Bank error: "+e.getMessage()); }
            });
        }
    }

    void solve(Uri u){
        result.setText("מעבד...");
        String k=key.getText().toString().trim();
        String imageMime=getContentResolver().getType(u);

        worker.execute(()->{
            try{
                byte[] image=readBytes(u);
                OpenAiHelper ai=new OpenAiHelper(k);
                String q=ai.extractQuestion(image,imageMime);
                log("Recognized: "+q);

                if(!vectorStoreId.isEmpty()){
                    show("מחפש ב-PDF...");
                    String a=ai.answerFromKnowledgeBase(q,vectorStoreId);
                    if(a!=null&&!a.toUpperCase(Locale.ROOT).contains("NOT_FOUND")){
                        show("PDF\n"+a);
                        return;
                    }
                    log("No reliable PDF match; using GPT.");
                }

                if(bank!=null&&!bank.trim().isEmpty()){
                    BankMatcher.Match m=BankMatcher.best(q,bank);
                    if(m!=null&&m.score>=0.38){
                        String a=ai.answerFromRecord(q,m.block);
                        if(a!=null&&!a.toUpperCase(Locale.ROOT).contains("NOT_FOUND")){
                            show("מאגר "+String.format(Locale.US,"%.2f",m.score)+"\n"+a);
                            return;
                        }
                    }
                }

                show("GPT\n"+ai.solveImage(image,imageMime));
            }catch(Exception e){
                show("שגיאה: "+e.getMessage());
                log("Solve error: "+e.getMessage());
            }
        });
    }

    String getName(Uri u){
        Cursor c=null;
        try{
            c=getContentResolver().query(u,null,null,null,null);
            if(c!=null&&c.moveToFirst()){
                int i=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if(i>=0)return c.getString(i);
            }
        }finally{
            if(c!=null)c.close();
        }
        return "knowledge.pdf";
    }

    long getLength(Uri u){
        Cursor c=null;
        try{
            c=getContentResolver().query(u,null,null,null,null);
            if(c!=null&&c.moveToFirst()){
                int i=c.getColumnIndex(OpenableColumns.SIZE);
                if(i>=0&&!c.isNull(i))return c.getLong(i);
            }
        }finally{
            if(c!=null)c.close();
        }
        return -1;
    }

    String readText(Uri u)throws Exception{
        return new String(readBytes(u),StandardCharsets.UTF_8);
    }

    byte[] readBytes(Uri u)throws Exception{
        try(InputStream in=getContentResolver().openInputStream(u);
            ByteArrayOutputStream out=new ByteArrayOutputStream()){
            if(in==null)throw new IOException("Cannot open file");
            byte[] b=new byte[8192]; int n;
            while((n=in.read(b))>0)out.write(b,0,n);
            return out.toByteArray();
        }
    }

    void show(String s){ runOnUiThread(()->result.setText(s)); }
    void log(String s){ runOnUiThread(()->diag.setText((diag.getText()+"\n"+s).trim())); }

    @Override protected void onDestroy(){
        super.onDestroy();
        worker.shutdownNow();
    }
}
