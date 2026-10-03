package com.niaman.go3bridge;

import android.app.Activity;
import android.content.*;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.LinkProperties;
import android.net.RouteInfo;
import java.net.NetworkInterface;
import java.net.InetAddress;
import java.util.Enumeration;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.*;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    static final int BANK=10, IMAGE=11, SAVE_REPORT=12;

    TextView authStatus, bankStatus, result, diag;
    final StringBuilder diagBuffer=new StringBuilder();
    final ByteArrayOutputStream rawGo3Capture=new ByteArrayOutputStream();
    ByteArrayOutputStream jpegCapture=null;
    int previousGo3Byte=-1;
    final Object captureLock=new Object();
    Button authButton;
    String bank="";
    String knowledgeName="";
    QuestionBank questionBank=new QuestionBank();
    final ExecutorService worker=Executors.newSingleThreadExecutor();
    ChatGptAuth auth;
    Go3Ble go3Ble;
    Go3MediaProbe mediaProbe;
    Go3CompanionAssociation companionAssociation;
    Go3PracticalBridge practicalBridge;
    volatile long lastSnapshotEventMs=0;
    volatile boolean practicalMode=false;
    volatile long practicalOpenedAt=0;
    volatile String lastSyncedImageUri="";

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        PDFBoxResourceLoader.init(getApplicationContext());
        auth=new ChatGptAuth(this);
        companionAssociation=new Go3CompanionAssociation(this,this::log);
        practicalBridge=new Go3PracticalBridge(this,this::log);
        mediaProbe=new Go3MediaProbe(this,this::log,(bytes,source)->{
            log("MEDIA IMAGE FOUND: "+source+" bytes="+bytes.length);
            handleDirectGo3Image(bytes);
        });
        restoreKnowledge();

        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24,24,24,24);

        TextView title=new TextView(this);
        title.setText("GO3 GPT Bridge v1.7.1 SAFE");
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

        Button practical=new Button(this);
        practical.setText("3. מצב מעשי: INMO לחיבור + Bridge לפתרון");
        root.addView(practical);

        Button companion=new Button(this);
        companion.setText("4. רשום את האפליקציה כ-GO3 Companion");
        root.addView(companion);

        Button go3=new Button(this);
        go3.setText("5. חבר GO3 ישירות (BLE 0x2020)");
        root.addView(go3);

        Button net=new Button(this);
        net.setText("6. המתן לצילום GO3 ופתור אוטומטית");
        root.addView(net);

        Button saveReport=new Button(this);
        saveReport.setText("7. שמור דוח אבחון כ-TXT");
        root.addView(saveReport);

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
        practical.setOnClickListener(v->startPracticalMode());
        companion.setOnClickListener(v->{
            companionAssociation.associate();
            companionAssociation.reportAssociations();
        });
        go3.setOnClickListener(v->{
            if(go3Ble==null)go3Ble=new Go3Ble(this,this::log,s->runOnUiThread(()->log(s)),this::onGo3Packet);
            go3Ble.start();
        });
        net.setOnClickListener(v->{
            if(go3Ble==null)go3Ble=new Go3Ble(this,this::log,s->runOnUiThread(()->log(s)),this::onGo3Packet);
            log("Direct capture armed: waiting for GO3 image/event on 0x2022/0x2023.");
            show("ממתין לצילום ישיר מה-GO3...");
            go3Ble.start();
        });
        saveReport.setOnClickListener(v->saveDiagnosticReport());
    }

    void startPracticalMode(){
        practicalMode=true;
        if(!practicalBridge.ensurePermissions()){
            log("PRACTICAL: permissions requested. Press Practical Mode again after granting them.");
            return;
        }
        show("פותח INMO Global רק לצורך Session. אחרי שהמשקפיים Ready חזור עם Back.");
        practicalOpenedAt=System.currentTimeMillis();
        practicalBridge.launchInmoGlobal();
    }

    void resumePracticalBridge(){
        if(!practicalMode)return;
        log("PRACTICAL SAFE: INMO Global remains the only BLE owner. Bridge will NOT open a second GATT connection.");
        show("מצב בטוח פעיל • INMO Global שומר על חיבור המשקפיים");
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

        if(requestCode==Go3CompanionAssociation.REQ_ASSOC){
            companionAssociation.reportAssociations();
            log("COMPANION: chooser returned resultCode="+resultCode+"; starting GO3 BLE connection automatically.");
            if(go3Ble==null)go3Ble=new Go3Ble(this,this::log,s->runOnUiThread(()->log(s)),this::onGo3Packet);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(()->go3Ble.start(),1000);
            return;
        }

        if(resultCode!=RESULT_OK||data==null||data.getData()==null)return;
        Uri u=data.getData();
        if(requestCode==BANK)loadKnowledge(u);
        if(requestCode==IMAGE)solve(u);
        if(requestCode==SAVE_REPORT)writeDiagnosticReport(u);
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

    void onGo3Packet(UUID characteristic, byte[] data){
        if(data==null||data.length==0)return;

        String printable=printableAscii(data);
        if(printable.contains("SnapShot") || printable.contains("Snapshot")){
            long now=System.currentTimeMillis();
            if(now-lastSnapshotEventMs>3000){
                lastSnapshotEventMs=now;
                log("SNAPSHOT EVENT detected on "+characteristic+" -> starting direct SoftAP/media retrieval");
                show("זוהה צילום GO3 • מחפש את קובץ התמונה ישירות...");
                worker.execute(()->{
                    try{Thread.sleep(700);}catch(Exception ignored){}
                    mediaProbe.probeAfterSnapshot();
                    pollSyncedPhoto(now,12);
                    try{Thread.sleep(1800);}catch(Exception ignored){}
                    mediaProbe.probeAfterSnapshot();
                    try{Thread.sleep(3200);}catch(Exception ignored){}
                    mediaProbe.probeAfterSnapshot();
                });
            }
        }

        synchronized(captureLock){
            try{
                if(rawGo3Capture.size()<4*1024*1024) rawGo3Capture.write(data);

                for(int i=0;i<data.length;i++){
                    int b=data[i]&0xff;
                    if(jpegCapture==null){
                        if(previousGo3Byte==0xff && b==0xd8){
                            jpegCapture=new ByteArrayOutputStream();
                            jpegCapture.write(0xff);
                            jpegCapture.write(0xd8);
                            log("JPEG start detected in direct GO3 stream on "+characteristic);
                        }
                    }else{
                        jpegCapture.write(b);
                        byte[] current=jpegCapture.toByteArray();
                        int n=current.length;
                        if(n>=2 && (current[n-2]&0xff)==0xff && (current[n-1]&0xff)==0xd9){
                            byte[] jpg=current;
                            jpegCapture=null;
                            previousGo3Byte=-1;
                            log("JPEG complete from GO3: "+jpg.length+" bytes");
                            handleDirectGo3Image(jpg);
                            return;
                        }
                        if(jpegCapture.size()>16*1024*1024){
                            log("Direct JPEG exceeded 16MB; reset capture");
                            jpegCapture=null;
                        }
                    }
                    previousGo3Byte=b;
                }
            }catch(Exception e){log("Direct capture error: "+e.getMessage());}
        }
    }

    void pollSyncedPhoto(long sinceMs,int attempts){
        if(!practicalMode)return;
        for(int i=0;i<attempts;i++){
            try{
                Uri u=practicalBridge.findNewestImageSince(sinceMs);
                if(u!=null && !u.toString().equals(lastSyncedImageUri)){
                    lastSyncedImageUri=u.toString();
                    log("PRACTICAL: synced GO3 photo found in MediaStore -> "+u);
                    byte[] jpg=normalizeImageToJpeg(u);
                    handleDirectGo3Image(jpg);
                    return;
                }
                Thread.sleep(1000);
            }catch(Exception e){
                log("PRACTICAL: photo polling error: "+e.getMessage());
            }
        }
        log("PRACTICAL: no synced photo appeared within polling window.");
    }

    String extractAnswerNumber(String s){
        if(s==null)return "?";
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?<!\\d)([1-4])(?!\\d)").matcher(s);
        return m.find()?m.group(1):s.trim();
    }

    void deliverAnswer(String answer){
        String token=extractAnswerNumber(answer);
        show("תשובה: "+token);
        if(practicalMode)practicalBridge.notifyAnswer(token);
    }

    String printableAscii(byte[] data){
        StringBuilder s=new StringBuilder();
        for(byte x:data){
            int v=x&0xff;
            if(v>=32&&v<=126)s.append((char)v); else s.append(' ');
        }
        return s.toString();
    }

    void handleDirectGo3Image(byte[] jpg){
        worker.execute(()->{
            try{
                File f=new File(getCacheDir(),"go3-direct-capture.jpg");
                try(FileOutputStream out=new FileOutputStream(f)){out.write(jpg);}
                log("GO3 image saved locally: "+f.getAbsolutePath());
                if(!auth.isSignedIn()){
                    show("התמונה התקבלה מה-GO3 • ChatGPT לא מחובר");
                    return;
                }
                if(bank.trim().isEmpty()){
                    show("התמונה התקבלה מה-GO3 • טען קודם מאגר PDF/TXT");
                    return;
                }
                solveJpegBytes(jpg);
            }catch(Exception e){log("Direct image handling error: "+e.getMessage());}
        });
    }

    void solveJpegBytes(byte[] image){
        try{
            show("צילום GO3 התקבל • מזהה שאלה ותשובות...");
            String token=auth.getAccessToken();
            OpenAiHelper ai=new OpenAiHelper(token);

            String q=ai.extractQuestion(image,"image/jpeg");
            log("Direct GO3 recognized question/options: "+q);

            QuestionBank.Match pm=questionBank.best(q);
            if(pm!=null&&pm.entry!=null){
                log("Direct bank match: ticket "+pm.entry.ticket+", question "+pm.entry.question+
                    ", score "+String.format(Locale.US,"%.3f",pm.score)+
                    ", second "+String.format(Locale.US,"%.3f",pm.secondScore)+
                    ", stored answer "+pm.entry.correctIndex);
                if(pm.reliable()){
                    String a=pm.entry.answer();
                    deliverAnswer(a);
                    return;
                }
            }

            BankMatcher.Match m=BankMatcher.best(q,bank);
            String related="";
            if(pm!=null&&pm.entry!=null){
                related="Ticket "+pm.entry.ticket+", Question "+pm.entry.question+
                    "\nStored correct answer: "+pm.entry.answer()+
                    "\nQuestion record:\n"+pm.entry.block;
            }else if(m!=null){
                related=m.block;
            }
            String solved=ai.solveImageWithContext(image,"image/jpeg",related);
            deliverAnswer(solved);
        }catch(Exception e){
            show("שגיאה בניתוח צילום GO3: "+e.getMessage());
            log("Direct solve error: "+e.getMessage());
        }
    }

    void logLocalNetworks(){
        worker.execute(()->{
            log("=== LOCAL NETWORK DIAGNOSTIC ===");
            try{
                ConnectivityManager cm=getSystemService(ConnectivityManager.class);
                if(cm!=null){
                    for(Network n:cm.getAllNetworks()){
                        NetworkCapabilities nc=cm.getNetworkCapabilities(n);
                        LinkProperties lp=cm.getLinkProperties(n);
                        StringBuilder b=new StringBuilder();
                        b.append("NET ").append(n);
                        if(nc!=null){
                            b.append(" transports=");
                            if(nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))b.append("WIFI ");
                            if(nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))b.append("CELL ");
                            if(nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN))b.append("VPN ");
                            if(nc.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH))b.append("BT ");
                        }
                        if(lp!=null){
                            b.append(" if=").append(lp.getInterfaceName());
                            b.append(" addrs=").append(lp.getLinkAddresses());
                            b.append(" dns=").append(lp.getDnsServers());
                            for(RouteInfo r:lp.getRoutes()) b.append(" route[").append(r).append("]");
                        }
                        log(b.toString());
                    }
                }
                Enumeration<NetworkInterface> en=NetworkInterface.getNetworkInterfaces();
                while(en!=null&&en.hasMoreElements()){
                    NetworkInterface ni=en.nextElement();
                    StringBuilder b=new StringBuilder("IF ");
                    b.append(ni.getName()).append(" up=").append(ni.isUp()).append(" ");
                    Enumeration<InetAddress> ia=ni.getInetAddresses();
                    while(ia.hasMoreElements()) b.append(ia.nextElement().getHostAddress()).append(" ");
                    log(b.toString().trim());
                }
                log("=== END NETWORK DIAGNOSTIC ===");
            }catch(Exception e){ log("Network diagnostic error: "+e.getMessage()); }
        });
    }

    void saveDiagnosticReport(){
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TITLE,"GO3-network-diagnostic.txt");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i,SAVE_REPORT);
    }

    void writeDiagnosticReport(Uri u){
        worker.execute(()->{
            try(OutputStream out=getContentResolver().openOutputStream(u)){
                if(out==null)throw new IOException("Cannot open report file");
                String txt;
                synchronized(diagBuffer){txt=diagBuffer.toString();}
                out.write(txt.getBytes(StandardCharsets.UTF_8));
                runOnUiThread(()->Toast.makeText(this,"הדוח נשמר. עכשיו אפשר להעלות אותו לצ'אט.",Toast.LENGTH_LONG).show());
            }catch(Exception e){log("Save report error: "+e.getMessage());}
        });
    }

    void show(String s){runOnUiThread(()->result.setText(s));}
    void log(String s){
        synchronized(diagBuffer){
            if(diagBuffer.length()>0)diagBuffer.append('\n');
            diagBuffer.append(s);
        }
        runOnUiThread(()->diag.setText(diagBuffer.toString()));
    }

    @Override protected void onResume(){
        super.onResume();
        if(auth!=null&&authButton!=null&&authStatus!=null){
            boolean signed=auth.isSignedIn();
            authButton.setEnabled(true);
            authButton.setText(signed ? "מחובר ל-ChatGPT" : "Continue with ChatGPT");
            authStatus.setText(signed ? "ChatGPT: מחובר • שימוש בתוכנית ChatGPT" : "ChatGPT: לא מחובר");
        }
        if(practicalMode && practicalOpenedAt>0 && System.currentTimeMillis()-practicalOpenedAt>2500){
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::resumePracticalBridge,800);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if(requestCode==Go3PracticalBridge.REQ_PRACTICAL_PERMS){
            boolean ok=true;
            for(int r:grantResults)if(r!=android.content.pm.PackageManager.PERMISSION_GRANTED)ok=false;
            log("PRACTICAL: permission result="+ok);
            if(ok)startPracticalMode();
            return;
        }
        if(requestCode==Go3Ble.REQ_BT){
            boolean ok=true;
            for(int r:grantResults)if(r!=android.content.pm.PackageManager.PERMISSION_GRANTED)ok=false;
            if(ok){
                if(go3Ble==null)go3Ble=new Go3Ble(this,this::log,s->runOnUiThread(()->log(s)),this::onGo3Packet);
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
