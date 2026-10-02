package com.niaman.go3bridge;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    static final int BANK=10, IMAGE=11;
    EditText key;
    TextView status, result, diag;
    String bank="";
    final ExecutorService worker=Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24,24,24,24);

        TextView title=new TextView(this);
        title.setText("GO3 GPT Bridge");
        title.setTextSize(26);
        root.addView(title);

        key=new EditText(this);
        key.setHint("OpenAI API key");
        root.addView(key,new LinearLayout.LayoutParams(-1,-2));

        Button load=new Button(this);
        load.setText("1. טען מאגר שאלות");
        root.addView(load);

        Button solve=new Button(this);
        solve.setText("2. בחר צילום שאלה ופתור");
        root.addView(solve);

        Button go3=new Button(this);
        go3.setText("3. סרוק וחבר GO3");
        root.addView(go3);

        status=new TextView(this);
        status.setText("מאגר: לא נטען | GO3: לא מחובר");
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

    void pickBank(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("text/*");
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
        if(requestCode==BANK) loadBank(u);
        if(requestCode==IMAGE) solve(u);
    }

    void loadBank(Uri u){
        worker.execute(()->{
            try{
                bank=readText(u);
                runOnUiThread(()->status.setText("מאגר נטען: "+bank.length()+" תווים"));
            }catch(Exception e){ log("Bank error: "+e.getMessage()); }
        });
    }

    void solve(Uri u){
        result.setText("מעבד...");
        String k=key.getText().toString().trim();
        worker.execute(()->{
            try{
                byte[] image=readBytes(u);
                OpenAiHelper ai=new OpenAiHelper(k);
                String q=ai.extractQuestion(image);
                log("Recognized: "+q);
                BankMatcher.Match m=BankMatcher.best(q,bank);
                if(m!=null&&m.score>=0.38){
                    String a=ai.answerFromRecord(q,m.block);
                    if(!a.toUpperCase().contains("NOT_FOUND")){
                        show("מאגר "+String.format(java.util.Locale.US,"%.2f",m.score)+"\n"+a);
                        return;
                    }
                }
                show("GPT\n"+ai.solveImage(image));
            }catch(Exception e){ show("שגיאה: "+e.getMessage()); }
        });
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
