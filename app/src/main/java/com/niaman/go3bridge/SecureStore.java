package com.niaman.go3bridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class SecureStore {
    private static final String ALIAS="go3_bridge_secure";
    private static final String PREFS="go3_bridge_secure_prefs";
    private final SharedPreferences prefs;

    public SecureStore(Context context){
        prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
    }

    private SecretKey key() throws Exception{
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if(ks.containsAlias(ALIAS)){
            return ((KeyStore.SecretKeyEntry)ks.getEntry(ALIAS,null)).getSecretKey();
        }
        KeyGenerator kg=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
         .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
         .build());
        return kg.generateKey();
    }

    public void put(String name,String value) throws Exception{
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE,key());
        byte[] enc=c.doFinal(value.getBytes(StandardCharsets.UTF_8));
        String iv=Base64.encodeToString(c.getIV(),Base64.NO_WRAP);
        String data=Base64.encodeToString(enc,Base64.NO_WRAP);
        prefs.edit().putString(name+"_iv",iv).putString(name+"_data",data).apply();
    }

    public String get(String name){
        try{
            String iv=prefs.getString(name+"_iv",null);
            String data=prefs.getString(name+"_data",null);
            if(iv==null||data==null)return "";
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE,key(),
                new GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)));
            byte[] out=c.doFinal(Base64.decode(data,Base64.NO_WRAP));
            return new String(out,StandardCharsets.UTF_8);
        }catch(Exception e){
            return "";
        }
    }

    public void remove(String name){
        prefs.edit().remove(name+"_iv").remove(name+"_data").apply();
    }
}
