package io.github.tsik2011dev.takken;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Downloads the encrypted audio files of one player ("app"), decrypts them and keeps plain MP3s in private storage. */
final class Pack {
    private static final Map<String, Pack> PACKS = new HashMap<>();

    static synchronized Pack of(Context c, String app) {
        Pack p = PACKS.get(app);
        if (p == null) { p = new Pack(c.getApplicationContext(), app); PACKS.put(app, p); }
        return p;
    }

    final Context ctx;
    final String app;
    final File dir;
    private final Set<String> have = Collections.synchronizedSet(new HashSet<String>());
    private SecretKey key;
    volatile boolean running;
    volatile String error = "";
    volatile String priority;

    private Pack(Context c, String app) {
        this.ctx = c;
        this.app = app;
        this.dir = new File(new File(c.getFilesDir(), "audio"), app.replaceAll("[^A-Za-z0-9_-]", "_"));
        if (!dir.exists()) dir.mkdirs();
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) {
            String n = f.getName();
            if (n.endsWith(".mp3")) have.add(n.substring(0, n.length() - 4));
            else if (n.endsWith(".part")) f.delete();
        }
    }

    private SharedPreferences prefs() { return ctx.getSharedPreferences("player", Context.MODE_PRIVATE); }

    String pass() { return prefs().getString("pass_" + app, null); }

    void setPass(String p) {
        prefs().edit().putString("pass_" + app, p).apply();
        synchronized (this) { key = null; error = ""; }
    }

    File file(String id) { return new File(dir, id.replaceAll("[^A-Za-z0-9_.-]", "_") + ".mp3"); }

    boolean has(String id) { return have.contains(id); }

    int countDone(List<Player.Track> tracks) {
        int n = 0;
        for (Player.Track t : tracks) if (have.contains(t.id)) n++;
        return n;
    }

    void clear() {
        prefs().edit().remove("pass_" + app).apply();
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        have.clear();
        synchronized (this) { key = null; error = ""; }
    }

    /** Start (or keep) the background download of every missing file. */
    synchronized void ensure(final String metaUrl, final String baseUrl, final List<Player.Track> tracksIn) {
        if (running || tracksIn.isEmpty()) return;
        final List<Player.Track> tracks = new ArrayList<>(tracksIn);
        running = true;
        error = "";
        new Thread(new Runnable() {
            @Override public void run() {
                try { download(metaUrl, baseUrl, tracks); }
                finally { running = false; Player.get(ctx).onPackStopped(app); }
            }
        }, "pack-" + app).start();
    }

    private void download(String metaUrl, String baseUrl, List<Player.Track> tracks) {
        try {
            String pass = pass();
            if (pass == null) { error = "nopass"; return; }
            SecretKey k;
            synchronized (this) { k = key; }
            if (k == null) {
                JSONObject meta = new JSONObject(new String(get(metaUrl), "UTF-8"));
                byte[] salt = Base64.decode(meta.getString("salt"), Base64.DEFAULT);
                SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
                byte[] raw = f.generateSecret(new PBEKeySpec(pass.toCharArray(), salt, meta.getInt("iter"), 256)).getEncoded();
                k = new SecretKeySpec(raw, "AES");
                synchronized (this) { key = k; }
            }
            while (true) {
                Player.Track next = null;
                String pr = priority;
                if (pr != null && !has(pr)) for (Player.Track t : tracks) if (t.id.equals(pr)) { next = t; break; }
                if (next == null) for (Player.Track t : tracks) if (!has(t.id)) { next = t; break; }
                if (next == null) break;
                byte[] enc = get(baseUrl + next.file);
                Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
                ci.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, enc, 0, 12));
                byte[] plain = ci.doFinal(enc, 12, enc.length - 12);
                File tmp = new File(dir, next.id + ".part");
                FileOutputStream o = new FileOutputStream(tmp);
                try { o.write(plain); } finally { o.close(); }
                File dst = file(next.id);
                if (!tmp.renameTo(dst)) throw new IOException("rename failed");
                have.add(next.id);
                if (next.id.equals(priority)) priority = null;
                Player.get(ctx).onFileReady(app, next.id);
            }
            error = "";
        } catch (AEADBadTagException e) {
            error = "badpass";
            synchronized (this) { key = null; }
        } catch (Exception e) {
            error = "net";
        }
    }

    private static byte[] get(String u) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(u).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(30000);
        con.setUseCaches(false);
        try {
            int code = con.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            InputStream in = new BufferedInputStream(con.getInputStream());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            } finally { in.close(); }
            return out.toByteArray();
        } finally {
            con.disconnect();
        }
    }
}
