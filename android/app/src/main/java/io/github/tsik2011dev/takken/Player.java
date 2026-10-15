package io.github.tsik2011dev.takken;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Native playback engine. Lives in the app process independently of the WebView,
 * so playback (and moving on to the next item) keeps going when the screen is
 * closed or the task is swiped away. A foreground service (PlayerService) keeps
 * the process alive while playing.
 */
public final class Player implements MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener {
    public static final String SITE = "https://tsik2011-dev.github.io/takken-player/";
    private static Player I;

    public static synchronized Player get(Context c) {
        if (I == null) I = new Player(c.getApplicationContext());
        return I;
    }

    static final class Track { String id, title, chap, file; double dur; }

    final Context ctx;
    final Handler h = new Handler(Looper.getMainLooper());
    final SharedPreferences sp;
    final AudioManager am;
    final MediaSession session;
    final PowerManager.WakeLock bridgeLock;

    // configuration of the current player ("app")
    volatile String app;
    String metaUrl = "", baseUrl = "";
    final List<Track> tracks = new ArrayList<>();
    final List<Integer> queue = new ArrayList<>();
    volatile boolean repeat;

    // playback state
    private MediaPlayer mp;
    private boolean prepared;
    volatile int index = -1;
    volatile boolean playing, loading;
    volatile float speed;
    volatile long sleepAt;
    volatile boolean sleepEnd;
    volatile String lastError = "";
    private int pendingIndex = -1;
    private double pendingPos, savedPos;
    private boolean resumeOnFocus, noisyRegistered;
    private AudioFocusRequest focusReq;
    private Bitmap art;

    private Player(Context c) {
        ctx = c;
        sp = c.getSharedPreferences("player", Context.MODE_PRIVATE);
        am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        bridgeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "takken:next");
        bridgeLock.setReferenceCounted(false);
        speed = sp.getFloat("speed", 1f);
        loadApp(sp.getString("cur_app", "matome"));
        session = new MediaSession(c, "takken");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { doResume(); }
            @Override public void onPause() { doPause(); }
            @Override public void onSkipToNext() { doNext(); }
            @Override public void onSkipToPrevious() { doPrev(); }
            @Override public void onSeekTo(long pos) { doSeek(pos / 1000.0); }
            @Override public void onFastForward() { doSeek(position() + 15); }
            @Override public void onRewind() { doSeek(Math.max(0, position() - 15)); }
            @Override public void onStop() { doPause(); }
        }, h);
        session.setActive(true);
        updateSession();
    }

    /* ---------------- configuration ---------------- */

    private void loadApp(String a) {
        app = a;
        tracks.clear();
        queue.clear();
        metaUrl = "";
        baseUrl = "";
        try {
            JSONObject cfg = new JSONObject(sp.getString("cfg_" + a, "{}"));
            metaUrl = cfg.optString("meta", "");
            baseUrl = cfg.optString("base", "");
            JSONArray arr = cfg.optJSONArray("tracks");
            if (arr != null) for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Track t = new Track();
                t.id = o.getString("id");
                t.title = o.optString("title", t.id);
                t.chap = o.optString("chap", "");
                t.file = o.optString("file", t.id + ".bin");
                t.dur = o.optDouble("dur", 0);
                tracks.add(t);
            }
            JSONArray q = new JSONArray(sp.getString("queue_" + a, "[]"));
            for (int i = 0; i < q.length(); i++) queue.add(q.getInt(i));
        } catch (JSONException ignored) { }
        repeat = sp.getBoolean("repeat_" + a, false);
        index = sp.getInt("index_" + a, -1);
        if (index >= tracks.size()) index = -1;
        savedPos = sp.getFloat("pos_" + a, 0f);
    }

    Pack pack() { return Pack.of(ctx, app); }

    void configure(final String json) {
        h.post(new Runnable() { @Override public void run() {
            try {
                JSONObject cfg = new JSONObject(json);
                String a = cfg.getString("app");
                JSONObject store = new JSONObject();
                store.put("meta", cfg.getString("meta"));
                store.put("base", cfg.getString("base"));
                store.put("tracks", cfg.getJSONArray("tracks"));
                sp.edit().putString("cfg_" + a, store.toString()).putString("cur_app", a).apply();
                if (!a.equals(app)) { stopPlayback(); }
                loadApp(a);
                updateSession();
                if (pack().pass() != null) pack().ensure(metaUrl, baseUrl, tracks);
            } catch (JSONException ignored) { }
        }});
    }

    void unlock(final String pass) {
        h.post(new Runnable() { @Override public void run() {
            pack().setPass(pass);
            pack().ensure(metaUrl, baseUrl, tracks);
        }});
    }

    void download() {
        h.post(new Runnable() { @Override public void run() { pack().ensure(metaUrl, baseUrl, tracks); } });
    }

    void forget() {
        h.post(new Runnable() { @Override public void run() {
            stopPlayback();
            pack().clear();
            sp.edit().remove("index_" + app).remove("pos_" + app).apply();
            index = -1;
            savedPos = 0;
            updateSession();
            notifyService();
        }});
    }

    void setQueue(final String json, final boolean rep) {
        h.post(new Runnable() { @Override public void run() {
            queue.clear();
            try {
                JSONArray q = new JSONArray(json);
                for (int i = 0; i < q.length(); i++) queue.add(q.getInt(i));
            } catch (JSONException ignored) { }
            repeat = rep;
            sp.edit().putString("queue_" + app, new JSONArray(queue).toString()).putBoolean("repeat_" + app, rep).apply();
        }});
    }

    /* ---------------- commands (any thread) ---------------- */

    void play(final int i, final double pos) { h.post(new Runnable() { @Override public void run() { doPlay(i, pos); } }); }
    void toggle() { h.post(new Runnable() { @Override public void run() { if (playing || loading) doPause(); else doResume(); } }); }
    void resume() { h.post(new Runnable() { @Override public void run() { doResume(); } }); }
    void pause() { h.post(new Runnable() { @Override public void run() { doPause(); } }); }
    void next() { h.post(new Runnable() { @Override public void run() { doNext(); } }); }
    void prev() { h.post(new Runnable() { @Override public void run() { doPrev(); } }); }
    void seek(final double s) { h.post(new Runnable() { @Override public void run() { doSeek(s); } }); }

    void setSpeed(final float s) {
        h.post(new Runnable() { @Override public void run() {
            speed = s;
            sp.edit().putFloat("speed", s).apply();
            applySpeed();
            updateSession();
        }});
    }

    void setSleep(final int minutes) {
        h.post(new Runnable() { @Override public void run() {
            h.removeCallbacks(sleepRun);
            sleepAt = 0;
            sleepEnd = false;
            if (minutes > 0) {
                sleepAt = System.currentTimeMillis() + minutes * 60000L;
                h.postDelayed(sleepRun, minutes * 60000L);
            } else if (minutes < 0) {
                sleepEnd = true;
            }
        }});
    }

    private final Runnable sleepRun = new Runnable() { @Override public void run() { sleepAt = 0; doPause(); } };

    /* ---------------- engine (main thread) ---------------- */

    private void doPlay(int i, double pos) {
        if (i < 0 || i >= tracks.size()) return;
        index = i;
        pendingIndex = -1;
        Track t = tracks.get(i);
        releaseMp();
        Pack p = pack();
        File f = p.file(t.id);
        if (!p.has(t.id) || !f.exists()) {
            pendingIndex = i;
            pendingPos = pos;
            loading = true;
            playing = false;
            p.priority = t.id;
            p.ensure(metaUrl, baseUrl, tracks);
            save(pos);
            updateSession();
            notifyService();
            return;
        }
        loading = false;
        if (!requestFocus()) { playing = false; updateSession(); notifyService(); return; }
        try {
            mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            mp.setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK);
            mp.setDataSource(f.getAbsolutePath());
            mp.setOnCompletionListener(this);
            mp.setOnErrorListener(this);
            mp.prepare();
            prepared = true;
            if (pos > 0.3) mp.seekTo((int) (pos * 1000));
            mp.start();
            playing = true;
            applySpeed();
            lastError = "";
        } catch (Exception e) {
            lastError = "play";
            releaseMp();
            playing = false;
        }
        save(pos);
        registerNoisy(playing);
        updateSession();
        notifyService();
        startTick();
    }

    private void doResume() {
        if (mp != null && prepared) {
            if (!requestFocus()) return;
            try { mp.start(); } catch (Exception e) { doPlay(index, savedPos); return; }
            playing = true;
            applySpeed();
            registerNoisy(true);
            updateSession();
            notifyService();
            startTick();
        } else if (index >= 0) {
            doPlay(index, savedPos);
        } else if (!tracks.isEmpty()) {
            doPlay(queue.isEmpty() ? 0 : queue.get(0), 0);
        }
    }

    private void doPause() {
        loading = false;
        pendingIndex = -1;
        try { if (mp != null && prepared && mp.isPlaying()) mp.pause(); } catch (Exception ignored) { }
        playing = false;
        save(position());
        registerNoisy(false);
        updateSession();
        notifyService();
    }

    private void doNext() { if (index + 1 < tracks.size()) doPlay(index + 1, 0); }

    private void doPrev() {
        if (index < 0) return;
        if (position() > 3 || index == 0) doPlay(index, 0); else doPlay(index - 1, 0);
    }

    private void doSeek(double s) {
        s = Math.max(0, s);
        if (mp != null && prepared) {
            try { mp.seekTo((int) (s * 1000)); } catch (Exception ignored) { }
        }
        save(s);
        updateSession();
    }

    private void stopPlayback() {
        h.removeCallbacks(tick);
        loading = false;
        pendingIndex = -1;
        releaseMp();
        playing = false;
        registerNoisy(false);
    }

    double position() {
        try { MediaPlayer m = mp; if (m != null && prepared) return m.getCurrentPosition() / 1000.0; } catch (Exception ignored) { }
        return savedPos;
    }

    double duration() {
        try { MediaPlayer m = mp; if (m != null && prepared) return m.getDuration() / 1000.0; } catch (Exception ignored) { }
        return index >= 0 && index < tracks.size() ? tracks.get(index).dur : 0;
    }

    private void applySpeed() {
        if (mp != null && prepared && playing) {
            try { mp.setPlaybackParams(mp.getPlaybackParams().setSpeed(speed)); } catch (Exception ignored) { }
        }
    }

    int nextIndex(int i) {
        if (i < 0 || i >= tracks.size()) return -1;
        if (queue.isEmpty()) {
            if (i + 1 < tracks.size()) return i + 1;
            return repeat ? 0 : -1;
        }
        int at = queue.indexOf(i);
        if (at < 0) return -1;
        if (at + 1 < queue.size()) return queue.get(at + 1);
        return repeat ? queue.get(0) : -1;
    }

    @Override public void onCompletion(MediaPlayer m) {
        bridgeLock.acquire(10000);
        if (sleepEnd) {
            sleepEnd = false;
            releaseMp();
            playing = false;
            save(0);
            registerNoisy(false);
            updateSession();
            notifyService();
            return;
        }
        int n = nextIndex(index);
        if (n < 0) {
            releaseMp();
            playing = false;
            save(0);
            registerNoisy(false);
            updateSession();
            notifyService();
            return;
        }
        doPlay(n, 0);
    }

    @Override public boolean onError(MediaPlayer m, int what, int extra) {
        lastError = "play";
        releaseMp();
        playing = false;
        updateSession();
        notifyService();
        return true;
    }

    private void releaseMp() {
        if (mp != null) { try { mp.release(); } catch (Exception ignored) { } mp = null; }
        prepared = false;
    }

    void onFileReady(final String a, final String id) {
        h.post(new Runnable() { @Override public void run() {
            if (a.equals(app) && pendingIndex >= 0 && pendingIndex < tracks.size() && tracks.get(pendingIndex).id.equals(id)) {
                doPlay(pendingIndex, pendingPos);
            }
        }});
    }

    void onPackStopped(final String a) {
        h.post(new Runnable() { @Override public void run() {
            if (a.equals(app) && loading && pendingIndex >= 0 && pendingIndex < tracks.size() && !pack().has(tracks.get(pendingIndex).id)) {
                loading = false;
                pendingIndex = -1;
                lastError = pack().error;
                updateSession();
                notifyService();
            }
        }});
    }

    private void save(double pos) {
        savedPos = pos;
        sp.edit().putInt("index_" + app, index).putFloat("pos_" + app, (float) pos).apply();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (playing && mp != null && prepared) { save(position()); h.postDelayed(this, 5000); }
        }
    };

    private void startTick() { h.removeCallbacks(tick); if (playing) h.postDelayed(tick, 5000); }

    /* ---------------- audio focus / headphones ---------------- */

    private final AudioManager.OnAudioFocusChangeListener focusL = new AudioManager.OnAudioFocusChangeListener() {
        @Override public void onAudioFocusChange(int fc) {
            if (fc == AudioManager.AUDIOFOCUS_LOSS) { resumeOnFocus = false; doPause(); }
            else if (fc == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || fc == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                if (playing) { doPause(); resumeOnFocus = true; }
            } else if (fc == AudioManager.AUDIOFOCUS_GAIN) {
                if (resumeOnFocus) { resumeOnFocus = false; doResume(); }
            }
        }
    };

    private boolean requestFocus() {
        if (focusReq == null) {
            focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setOnAudioFocusChangeListener(focusL, h)
                    .setWillPauseWhenDucked(true)
                    .build();
        }
        return am.requestAudioFocus(focusReq) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(i.getAction())) doPause();
        }
    };

    private void registerNoisy(boolean on) {
        if (on && !noisyRegistered) {
            ctx.registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
            noisyRegistered = true;
        } else if (!on && noisyRegistered) {
            try { ctx.unregisterReceiver(noisy); } catch (Exception ignored) { }
            noisyRegistered = false;
        }
    }

    /* ---------------- system media controls ---------------- */

    Track current() { return index >= 0 && index < tracks.size() ? tracks.get(index) : null; }

    void updateSession() {
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO
                | PlaybackState.ACTION_STOP | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND;
        int state = loading ? PlaybackState.STATE_BUFFERING : playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        session.setPlaybackState(new PlaybackState.Builder().setActions(actions)
                .setState(state, (long) (position() * 1000), playing ? speed : 0f).build());
        Track t = current();
        if (t != null) {
            if (art == null) art = BitmapFactory.decodeResource(ctx.getResources(), R.mipmap.ic_launcher);
            MediaMetadata.Builder b = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, t.chap)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, "宅建 読み上げ")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, (long) (duration() * 1000));
            if (art != null) b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
            session.setMetadata(b.build());
        }
    }

    private void notifyService() {
        PlayerService s = PlayerService.S;
        if (s != null) { s.refresh(); return; }
        if (playing || loading) {
            try { ctx.startForegroundService(new Intent(ctx, PlayerService.class)); }
            catch (Exception e) { lastError = "service"; }
        }
    }

    /* ---------------- state for the web UI ---------------- */

    String stateJson() {
        try {
            JSONObject o = new JSONObject();
            Pack p = pack();
            o.put("app", app);
            o.put("index", index);
            o.put("pos", position());
            o.put("dur", duration());
            o.put("playing", playing);
            o.put("loading", loading);
            o.put("repeat", repeat);
            o.put("speed", (double) speed);
            o.put("sleepAt", sleepAt);
            o.put("sleepEnd", sleepEnd);
            o.put("hasPass", p.pass() != null);
            o.put("error", lastError);
            JSONObject d = new JSONObject();
            d.put("done", p.countDone(tracks));
            d.put("total", tracks.size());
            d.put("running", p.running);
            d.put("error", p.error);
            StringBuilder mask = new StringBuilder();
            for (Track t : tracks) mask.append(p.has(t.id) ? '1' : '0');
            d.put("mask", mask.toString());
            o.put("dl", d);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
