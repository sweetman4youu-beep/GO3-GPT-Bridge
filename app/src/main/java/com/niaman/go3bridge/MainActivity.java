package com.niaman.go3bridge;

import android.app.Activity;
import android.content.*;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.*;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    static final int BANK=10, IMAGE=11;

    TextView authStatus, bankStatus, result, diag;
    Button authButton;
    String bank="";
    String knowledgeName="";
    QuestionBank questionBank=new QuestionBank();
    final ExecutorService worker=Executors.newSingleThreadExecutor();
    ChatGptAuth auth;
    Go3Ble go3Ble;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        PDFBoxResourceLoader.init(getApplicationContext());
        auth=new ChatGptAuth(this);
        restoreKnowledge();

        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24,24,24,24);

        TextView title=new TextView(this);
        title.setText("GO3 GPT Bridge");
        title.setTextSize(26);
        root.addView(title);

        TextView sub=new TextView(this);
        sub.setText("PDF מלא → זיהוי שאלה → חיפוש במאגר → GPT אם לא נמצא");
        sub.setTextSize(15);
        root.addView(sub);

        authButton=new Button(this);
        authButton.setText(auth.isSignedIn() ? "מחובר ל-ChatGPT" : "Continue with ChatGPT");
        root.addView(authButton);

        authStatus=new TextView(this);
        authStatus.setText(auth.isSignedIn() ? "ChatGPT: מחובר" : "ChatGPT: לא מחובר");
        authStatus.setTextSize(15);
        root.addView(authStatus);

        Button load=new Button(this);
        load.setText("1. טען PDF מלא / TXT");
        root.addView(load);

        bankStatus=new TextView(this);
        bankStatus.setText(bank.isEmpty() ? "מאגר: לא נטען" :
            "מאגר מוכן: "+knowledgeName+" • "+questionBank.size()+" שאלות");
        bankStatus.setTextSize(15);
        root.addView(bankStatus);

        Button solve=new Button(this);
        solve.setText("2. בחר צילום שאלה ופתור");
        root.addView(solve);

        Button go3=new Button(this);
        go3.setText("3. סרוק וחבר GO3");
        root.addView(go3);

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

        authButton.setOnClickListener(v->connectChatGpt());
        load.setOnClickListener(v->pickBank());
        solve.setOnClickListener(v->pickImage());
        go3.setOnClickListener(v->{
            if(go3Ble==null)go3Ble=new Go3Ble(this,this::log,s->runOnUiThread(()->log(s)));
            go3Ble.start();
        });
    }

    void connectChatGpt(){
        if(auth.isSignedIn()){
            Toast.makeText(this,"כבר מחובר ל-ChatGPT",Toast.LENGTH_SHORT).show();
            return;
        }
        authButton.setEnabled(false);
        auth.signIn(
            s->runOnUiThread(()->authStatus.setText(s)),
            ok->runOnUiThread(()->{
                authButton.setEnabled(true);
                authButton.setText(ok ? "מחובר ל-ChatGPT" : "Continue with ChatGPT");
                if(ok)authStatus.setText("ChatGPT: מחובר • שימוש בתוכנית ChatGPT");
            })
        );
    }

    void restoreKnowledge(){
        SharedPreferences p=getSharedPreferences("go3_bridge",MODE_PRIVATE);
        knowledgeName=p.getString("knowledge_name","");
        File file=new File(getFilesDir(),"knowledge.txt");
        if(file.exists()){
            try(FileInputStream in=new FileInputStream(file)){
                bank=new String(readAll(in),StandardCharsets.UTF_8);
                questionBank=QuestionBank.parse(bank);
            }catch(Exception ignored){ bank=""; questionBank=new QuestionBank(); }
        }
    }

    void persistKnowledge(String text,String name)throws Exception{
        try(FileOutputStream out=openFileOutput("knowledge.txt",MODE_PRIVATE)){
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        getSharedPreferences("go3_bridge",MODE_PRIVATE).edit()
            .putString("knowledge_name",name==null?"knowledge":name)
            .apply();
        knowledgeName=name==null?"knowledge":name;
    }

    void pickBank(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/pdf","text/plain"});
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i,BANK);
    }

    void pickImage(){
        if(!auth.isSignedIn()){
            Toast.makeText(this,"קודם לחץ Continue with ChatGPT",Toast.LENGTH_LONG).show();
            return;
        }
        if(bank.trim().isEmpty()){
            Toast.makeText(this,"קודם טען את ה-PDF המלא",Toast.LENGTH_LONG).show();
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
        if(requestCode==BANK)loadKnowledge(u);
        if(requestCode==IMAGE)solve(u);
    }

    void loadKnowledge(Uri u){
        String name=getName(u);
        String mime=getContentResolver().getType(u);
        boolean pdf="application/pdf".equalsIgnoreCase(mime) ||
            (name!=null&&name.toLowerCase(Locale.ROOT).endsWith(".pdf"));

        bankStatus.setText(pdf ? "קורא את ה-PDF המלא..." : "טוען TXT...");
        worker.execute(()->{
            try{
                String text=pdf ? extractPdf(u) : new String(readBytes(u),StandardCharsets.UTF_8);
                text=text.replace("\u0000","").trim();
                if(text.length()<300){
                    throw new IOException("לא נמצא מספיק טקסט ב-PDF. ייתכן שזה PDF סרוק כתמונות.");
                }
                bank=text;
                questionBank=QuestionBank.parse(bank);
                persistKnowledge(bank,name);
                String msg="מאגר מוכן: "+knowledgeName+" • "+questionBank.size()+" שאלות";
                runOnUiThread(()->bankStatus.setText(msg));
                log("Knowledge ready. Characters: "+bank.length()+", parsed questions: "+questionBank.size());
                if(questionBank.size()<700)log("Warning: expected about 800 PDD questions; parsed only "+questionBank.size());
            }catch(Exception e){
                bank="";
                runOnUiThread(()->bankStatus.setText("שגיאה בקריאת המאגר"));
                log("PDF/TXT error: "+e.getMessage());
            }
        });
    }

    String extractPdf(Uri u)throws Exception{
        try(InputStream in=getContentResolver().openInputStream(u)){
            if(in==null)throw new IOException("Cannot open PDF");
            try(PDDocument doc=PDDocument.load(in)){
                PDFTextStripper stripper=new PDFTextStripper();
                stripper.setSortByPosition(true);
                return stripper.getText(doc);
            }
        }
    }

    void solve(Uri u){
        result.setText("מזהה את השאלה...");
        String originalMime=getContentResolver().getType(u);

        worker.execute(()->{
            try{
                String token=auth.getAccessToken();
                byte[] image=normalizeImageToJpeg(u);
                String imageMime="image/jpeg";
                log("Image normalized: "+originalMime+" → image/jpeg, "+image.length+" bytes");
                OpenAiHelper ai=new OpenAiHelper(token);

                String q=ai.extractQuestion(image,imageMime);
                log("Recognized: "+q);

                QuestionBank.Match pm=questionBank.best(q);
                if(pm!=null&&pm.entry!=null){
                    log("PDD bank: ticket "+pm.entry.ticket+", question "+pm.entry.question+
                        ", score "+String.format(Locale.US,"%.3f",pm.score)+
                        ", second "+String.format(Locale.US,"%.3f",pm.secondScore)+
                        ", stored answer "+pm.entry.correctIndex);
                    if(pm.reliable()){
                        String a=pm.entry.answer();
                        show("מאגר PDF • כרטיס "+pm.entry.ticket+" / שאלה "+pm.entry.question+
                            "\nתשובה: "+a);
                        return;
                    }
                }

                BankMatcher.Match m=BankMatcher.best(q,bank);
                if(m!=null)log("Fallback text score: "+String.format(Locale.US,"%.3f",m.score));

                String related="";
                if(pm!=null&&pm.entry!=null){
                    related="Ticket "+pm.entry.ticket+", Question "+pm.entry.question+
                        "\nStored correct answer: "+pm.entry.answer()+
                        "\nQuestion record:\n"+pm.entry.block;
                }else if(m!=null){
                    related=m.block;
                }

                show("לא נמצאה התאמה בטוחה • GPT מנתח ומכריע...");
                String solved=ai.solveImageWithContext(image,imageMime,related);
                show("GPT • ניתוח\n"+solved);
            }catch(Exception e){
                show("שגיאה: "+e.getMessage());
                log("Solve error: "+e.getMessage());
            }
        });
    }

    byte[] normalizeImageToJpeg(Uri u)throws Exception{
        ImageDecoder.Source source=ImageDecoder.createSource(getContentResolver(),u);
        Bitmap bitmap=ImageDecoder.decodeBitmap(source,(decoder,info,src)->{
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            int w=info.getSize().getWidth();
            int h=info.getSize().getHeight();
            int max=Math.max(w,h);
            if(max>2048){
                float scale=2048f/max;
                decoder.setTargetSize(Math.max(1,Math.round(w*scale)),Math.max(1,Math.round(h*scale)));
            }
        });
        if(bitmap==null)throw new IOException("לא ניתן לפענח את התמונה");
        try(ByteArrayOutputStream out=new ByteArrayOutputStream()){
            if(!bitmap.compress(Bitmap.CompressFormat.JPEG,92,out))
                throw new IOException("לא ניתן להמיר את התמונה ל-JPEG");
            return out.toByteArray();
        }finally{
            bitmap.recycle();
        }
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
        return "knowledge";
    }

    byte[] readBytes(Uri u)throws Exception{
        try(InputStream in=getContentResolver().openInputStream(u)){
            if(in==null)throw new IOException("Cannot open file");
            return readAll(in);
        }
    }

    byte[] readAll(InputStream in)throws Exception{
        try(ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192]; int n;
            while((n=in.read(b))>0)out.write(b,0,n);
            return out.toByteArray();
        }
    }

    void show(String s){runOnUiThread(()->result.setText(s));}
    void log(String s){runOnUiThread(()->diag.setText((diag.getText()+"\n"+s).trim()));}

    @Override protected void onResume(){
        super.onResume();
        if(auth!=null&&authButton!=null&&authStatus!=null){
            boolean signed=auth.isSignedIn();
            authButton.setEnabled(true);
            authButton.setText(signed ? "מחובר ל-ChatGPT" : "Continue with ChatGPT");
            authStatus.setText(signed ? "ChatGPT: מחובר • שימוש בתוכנית ChatGPT" : "ChatGPT: לא מחובר");
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if(requestCode==Go3Ble.REQ_BT){
            boolean ok=true;
            for(int r:grantResults)if(r!=android.content.pm.PackageManager.PERMISSION_GRANTED)ok=false;
            if(ok){
                if(go3Ble==null)go3Ble=new Go3Ble(this,this::log,s->runOnUiThread(()->log(s)));
                go3Ble.start();
            }else{
                log("Bluetooth permission denied");
            }
        }
    }

    @Override protected void onDestroy(){
        if(go3Ble!=null)go3Ble.stop();
        super.onDestroy();
        worker.shutdownNow();
    }
}
