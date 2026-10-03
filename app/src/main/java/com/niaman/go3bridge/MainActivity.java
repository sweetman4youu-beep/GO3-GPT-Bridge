package com.niaman.go3bridge;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.MediaStore;
import android.widget.*;
import android.view.WindowManager;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    static final int BANK=10, IMAGE=11, IMAGES_PERMISSION=13;

    TextView authStatus, bankStatus, result, diag, autoStatus;
    Button authButton, autoButton;
    boolean autoMode=false;
    long autoStartedAt=0L;
    String lastAutoUri="";
    ContentObserver imageObserver;
    final Handler mainHandler=new Handler(Looper.getMainLooper());
    String bank="";
    String knowledgeName="";
    QuestionBank questionBank=new QuestionBank();
    final ExecutorService worker=Executors.newSingleThreadExecutor();
    ChatGptAuth auth;

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

        Button notifyTest=new Button(this);
        notifyTest.setText("3. בדוק כתיבה שקטה דרך INMO");
        root.addView(notifyTest);

        autoButton=new Button(this);
        autoButton.setText("4. הפעל מצב אוטומטי GO3");
        root.addView(autoButton);

        autoStatus=new TextView(this);
        autoStatus.setText("מצב אוטומטי: כבוי");
        autoStatus.setTextSize(15);
        root.addView(autoStatus);

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
        notifyTest.setOnClickListener(v->sendToInmoText("2"));
        autoButton.setOnClickListener(v->toggleAutoMode());
        handleIncomingIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    void handleIncomingIntent(Intent intent){
        if(intent==null)return;
        String action=intent.getAction();
        String type=intent.getType();
        if(Intent.ACTION_SEND.equals(action) && type!=null && type.startsWith("image/")){
            Uri u=intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if(u!=null){
                log("Image received through Android share: "+u);
                if(auth.isSignedIn()&&!bank.trim().isEmpty())solve(u);
                else Toast.makeText(this,"קודם התחבר ל-ChatGPT וטען את המאגר",Toast.LENGTH_LONG).show();
            }
        }
    }

    void toggleAutoMode(){
        if(autoMode){
            disableAutoMode();
            return;
        }
        if(!auth.isSignedIn()){
            Toast.makeText(this,"קודם התחבר ל-ChatGPT",Toast.LENGTH_LONG).show();
            return;
        }
        if(bank.trim().isEmpty()){
            Toast.makeText(this,"קודם טען את ה-PDF המלא",Toast.LENGTH_LONG).show();
            return;
        }
        if(!hasImagePermission()){
            requestImagePermission();
            return;
        }
        enableAutoMode();
    }

    boolean hasImagePermission(){
        if(Build.VERSION.SDK_INT>=33)
            return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)==PackageManager.PERMISSION_GRANTED;
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED;
    }

    void requestImagePermission(){
        if(Build.VERSION.SDK_INT>=33)
            requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES},IMAGES_PERMISSION);
        else
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},IMAGES_PERMISSION);
    }

    void enableAutoMode(){
        if(autoMode)return;
        autoMode=true;
        autoStartedAt=System.currentTimeMillis()-3000L;
        lastAutoUri="";
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        imageObserver=new ContentObserver(mainHandler){
            @Override public void onChange(boolean selfChange,Uri uri){
                super.onChange(selfChange,uri);
                if(!autoMode)return;
                mainHandler.postDelayed(()->processMediaChange(uri),1200L);
            }
        };
        getContentResolver().registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,true,imageObserver);

        autoButton.setText("4. עצור מצב אוטומטי");
        autoStatus.setText("מצב אוטומטי: פעיל • מחכה לתמונה חדשה");
        log("Auto Input enabled. Watching Android MediaStore for new images.");
    }

    void disableAutoMode(){
        autoMode=false;
        if(imageObserver!=null){
            try{getContentResolver().unregisterContentObserver(imageObserver);}catch(Exception ignored){}
            imageObserver=null;
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if(autoButton!=null)autoButton.setText("4. הפעל מצב אוטומטי GO3");
        if(autoStatus!=null)autoStatus.setText("מצב אוטומטי: כבוי");
        log("Auto Input stopped.");
    }

    void processMediaChange(Uri changed){
        if(!autoMode)return;
        try{
            Uri u=resolveNewestImage(changed);
            if(u==null)return;
            String key=u.toString();
            if(key.equals(lastAutoUri))return;
            long added=imageDateAddedMs(u);
            if(added>0 && added<autoStartedAt)return;
            lastAutoUri=key;
            autoStatus.setText("מצב אוטומטי: נמצאה תמונה חדשה • מנתח...");
            log("Auto Input image: "+u);
            solve(u);
        }catch(Exception e){
            log("Auto Input error: "+e.getMessage());
        }
    }

    Uri resolveNewestImage(Uri changed){
        Uri collection=MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        if(changed!=null){
            String last=changed.getLastPathSegment();
            if(last!=null&&last.matches("\\d+"))return changed;
        }
        String[] projection={MediaStore.Images.Media._ID};
        try(Cursor c=getContentResolver().query(
            collection,projection,null,null,MediaStore.Images.Media.DATE_ADDED+" DESC")){
            if(c!=null&&c.moveToFirst()){
                long id=c.getLong(0);
                return ContentUris.withAppendedId(collection,id);
            }
        }
        return null;
    }

    long imageDateAddedMs(Uri u){
        String[] projection={MediaStore.Images.Media.DATE_ADDED};
        try(Cursor c=getContentResolver().query(u,projection,null,null,null)){
            if(c!=null&&c.moveToFirst())return c.getLong(0)*1000L;
        }catch(Exception ignored){}
        return 0L;
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if(requestCode==IMAGES_PERMISSION){
            if(grantResults.length>0&&grantResults[0]==PackageManager.PERMISSION_GRANTED)enableAutoMode();
            else Toast.makeText(this,"נדרשת הרשאה לתמונות כדי לזהות צילום חדש אוטומטית",Toast.LENGTH_LONG).show();
        }
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
                        sendToInmoText(String.valueOf(pm.entry.correctIndex));
                        return;
                    }
                }

                BankMatcher.Match m=BankMatcher.best(q,bank);
                if(m!=null)log("Fallback text score: "+String.format(Locale.US,"%.3f",m.score));

                show("לא נמצאה התאמה בטוחה במאגר • GPT פותר...");
                String solved=ai.solveImage(image,imageMime);
                show("GPT\n"+solved);
                sendToInmoText(extractAnswerNumber(solved));
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

    String extractAnswerNumber(String text){
        if(text==null)return "?";
        java.util.regex.Matcher m=java.util.regex.Pattern
            .compile("(?<!\\d)([1-9][0-9]*)(?!\\d)")
            .matcher(text);
        return m.find()?m.group(1):"?";
    }

    void sendToInmoText(String number){
        runOnUiThread(()->{
            String compact=number==null?"?":number.trim();
            if(!compact.matches("[1-9][0-9]*"))compact=extractAnswerNumber(compact);

            ClipboardManager clip=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            if(clip!=null)clip.setPrimaryClip(ClipData.newPlainText("GO3 answer",compact));

            Intent share=new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_TEXT,compact);
            share.putExtra(Intent.EXTRA_SUBJECT,"");
            share.setPackage("com.inmo.app.googleplay");
            share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);

            try{
                if(share.resolveActivity(getPackageManager())!=null){
                    startActivity(share);
                    log("INMO text route opened with number: "+compact);
                    return;
                }
            }catch(Exception e){
                log("INMO text share route failed: "+e.getMessage());
            }

            try{
                Intent view=new Intent(Intent.ACTION_VIEW,
                    Uri.parse("data:text/plain;charset=utf-8,"+Uri.encode(compact)));
                view.setPackage("com.inmo.app.googleplay");
                view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
                if(view.resolveActivity(getPackageManager())!=null){
                    startActivity(view);
                    log("INMO text view route opened with number: "+compact);
                    return;
                }
            }catch(Exception e){
                log("INMO text view route failed: "+e.getMessage());
            }

            Intent launch=getPackageManager().getLaunchIntentForPackage("com.inmo.app.googleplay");
            if(launch!=null){
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(launch);
                Toast.makeText(this,"המספר "+compact+" הועתק. INMO Global נפתח.",Toast.LENGTH_LONG).show();
                log("No exported INMO text handler; number copied: "+compact);
            }else{
                Toast.makeText(this,"INMO Global לא נמצא בטלפון",Toast.LENGTH_LONG).show();
                log("INMO Global package not found");
            }
        });
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

    @Override protected void onDestroy(){
        disableAutoMode();
        super.onDestroy();
        worker.shutdownNow();
    }
}
