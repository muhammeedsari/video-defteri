package app.sesdefteri;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Native playback with a queue, shuffle/repeat, audio focus, lock-screen and notification controls. */
final class Player implements MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener {
    interface Listener { void onPlayerEvent(String json); }

    static final Player I = new Player();

    private Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaPlayer mp;
    private JSONArray queue = new JSONArray();
    private List<Integer> order = new ArrayList<>();
    private int pos = -1;              // position in order
    private boolean prepared, wantPlay, playing;
    private long pendingSeek = -1;
    private float rate = 1f;
    private boolean shuffle;
    private int repeat;                // 0 off, 1 all, 2 one
    private String error = "";
    private MediaSession session;
    private Bitmap art;
    private Listener listener;
    private AudioFocusRequest focusReq;
    private boolean noisyRegistered;

    /* ---------- 2.1: level matching, EQ, crossfade / gapless, A-B loop, skip silence, sleep timer ---------- */
    private final java.util.HashMap<String, Double> gains = new java.util.HashMap<>();   // id → dB (level matching)
    private double[] eqDb;                       // 5 bands (100, 300, 1k, 3k, 8k) or null
    private int xfadeMs;                         // 0 = off
    private boolean gapless = true;
    private long loopA = -1, loopB = -1;
    private String skipsId = "";
    private long[] skips = new long[0];          // [s0, e0, s1, e1, …] in ms
    private long sleepAt;                        // wall clock, 0 = off
    private boolean sleepEnd;                    // stop when this track ends
    private MediaPlayer nmp;                     // the next track, prepared ahead
    private int npos = -1;
    private boolean fading;
    private long fadeStart, fadeLen;
    private final java.util.IdentityHashMap<MediaPlayer, Object[]> fx = new java.util.IdentityHashMap<>();   // keeps EQ / enhancer alive

    private double gainOf(JSONObject it) {
        if (it == null) return 0;
        Double g = gains.get(it.optString("id"));
        return g != null ? g : it.optDouble("gain", 0);
    }

    private static float lin(double db) { return (float) Math.min(1, Math.pow(10, db / 20)); }

    /** EQ and quiet-track boost hang off the player's audio session */
    private void attachFx(MediaPlayer p, JSONObject it) {
        detachFx(p);
        Object[] o = new Object[2];
        try {
            if (eqDb != null) {
                android.media.audiofx.Equalizer e = new android.media.audiofx.Equalizer(1000, p.getAudioSessionId());   // high priority: the phone's sound packs must not take it over
                double[] fc = { 100, 300, 1000, 3000, 8000 };
                short n = e.getNumberOfBands();
                short[] range = e.getBandLevelRange();
                for (short b = 0; b < n; b++) {
                    double f = e.getCenterFreq(b) / 1000.0, lf = Math.log(Math.max(20, f)), g;
                    if (f <= fc[0]) g = eqDb[0];
                    else if (f >= fc[4]) g = eqDb[4];
                    else {
                        int k = 0; while (k < 3 && f > fc[k + 1]) k++;
                        double t = (lf - Math.log(fc[k])) / (Math.log(fc[k + 1]) - Math.log(fc[k]));
                        g = eqDb[k] + (eqDb[k + 1] - eqDb[k]) * t;
                    }
                    e.setBandLevel(b, (short) Math.max(range[0], Math.min(range[1], Math.round(g * 100))));
                }
                e.setEnabled(true);
                o[0] = e;
            }
        } catch (Exception ignored) { }
        try {
            double g = gainOf(it);
            if (g > 0.5 && Build.VERSION.SDK_INT >= 19) {
                android.media.audiofx.LoudnessEnhancer le = new android.media.audiofx.LoudnessEnhancer(p.getAudioSessionId());
                le.setTargetGain((int) Math.round(Math.min(12, g) * 100));
                le.setEnabled(true);
                o[1] = le;
            }
        } catch (Exception ignored) { }
        fx.put(p, o);
    }

    private void detachFx(MediaPlayer p) {
        Object[] o = fx.remove(p);
        if (o == null) return;
        for (Object x : o) if (x instanceof android.media.audiofx.AudioEffect) try { ((android.media.audiofx.AudioEffect) x).release(); } catch (Exception ignored) { }
    }

    private void setVol(MediaPlayer p, JSONObject it, float f) {
        if (p == null) return;
        float v = lin(Math.min(0, gainOf(it))) * f;
        try { p.setVolume(v, v); } catch (Exception ignored) { }
    }

    private int nextPos() {
        if (repeat == 2 || order.isEmpty()) return -1;
        if (pos + 1 < order.size()) return pos + 1;
        return repeat == 1 ? 0 : -1;
    }

    private MediaPlayer newPlayer(JSONObject it) throws Exception {
        MediaPlayer p = new MediaPlayer();
        p.setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
        p.setWakeMode(app, PowerManager.PARTIAL_WAKE_LOCK);
        p.setOnPreparedListener(this);
        p.setOnCompletionListener(this);
        p.setOnErrorListener(this);
        JSONObject src = it.optJSONObject("src");
        Uri u = src != null ? MediaLib.srcUri(src) : null;
        File f = src != null ? MediaLib.srcFile(src) : null;
        if (u != null) p.setDataSource(app, u);
        else if (f != null) p.setDataSource(f.getAbsolutePath());
        else throw new IllegalStateException("no source");
        return p;
    }

    /** prepares the following track (synchronously; local files are quick) */
    private void prepareNext() {
        if (nmp != null || mp == null) return;
        int np = nextPos();
        if (np < 0 || np == pos) return;
        JSONObject it = queue.optJSONObject(order.get(np));
        if (it == null) return;
        try {
            MediaPlayer p = newPlayer(it);
            p.setOnPreparedListener(null);
            p.prepare();
            attachFx(p, it);
            setVol(p, it, xfadeMs > 0 ? 0 : 1);
            nmp = p; npos = np;
            if (xfadeMs == 0 && gapless) { try { mp.setNextMediaPlayer(p); } catch (Exception e) { dropNext(); } }
        } catch (Exception e) {
            nmp = null; npos = -1;
        }
    }

    private void dropNext() {
        fading = false;
        if (mp != null) try { mp.setNextMediaPlayer(null); } catch (Exception ignored) { }
        if (nmp != null) { detachFx(nmp); try { nmp.release(); } catch (Exception ignored) { } }
        nmp = null; npos = -1;
    }

    /** the next player has taken over (gapless hand-off or end of a crossfade) */
    private void promoteNext() {
        MediaPlayer old = mp;
        mp = nmp; pos = npos; nmp = null; npos = -1; fading = false;
        mp.setOnPreparedListener(this);
        prepared = true; playing = true; wantPlay = true;
        loopA = loopB = -1;
        setVol(mp, current(), 1);
        applyRate();
        if (old != null) { detachFx(old); try { old.setNextMediaPlayer(null); } catch (Exception ignored) { } try { old.release(); } catch (Exception ignored) { } }
        JSONObject it = current();
        if (it != null) { updateMeta(it); loadArt(it); }
        emit();
        if (xfadeMs == 0 && gapless) prepareNext();
        startPoll();
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            synchronized (Player.this) {
                if (sleepAt > 0 && System.currentTimeMillis() >= sleepAt) { sleepAt = 0; pause(); return; }
                if (!playing || mp == null || !prepared) return;
                long cur, dur;
                try { cur = mp.getCurrentPosition(); dur = mp.getDuration(); } catch (Exception e) { return; }
                if (loopB > loopA && loopA >= 0 && cur >= loopB) { seekInternal(loopA); main.postDelayed(this, 50); return; }
                JSONObject it = current();
                if (it != null && skipsId.equals(it.optString("id"))) {
                    for (int i = 0; i + 1 < skips.length; i += 2) if (cur >= skips[i] && cur < skips[i + 1] - 250) { seekInternal(skips[i + 1]); break; }
                }
                if (xfadeMs > 0 && !sleepEnd && dur > 0) {
                    long left = dur - cur;
                    if (nmp == null && left <= xfadeMs + 1500) prepareNext();
                    if (nmp != null && !fading && left <= xfadeMs) {
                        fading = true; fadeStart = System.currentTimeMillis(); fadeLen = Math.max(200, left);
                        try { nmp.start(); } catch (Exception e) { dropNext(); }
                    }
                    if (fading && nmp != null) {
                        float f = Math.min(1, (System.currentTimeMillis() - fadeStart) / (float) fadeLen);
                        setVol(mp, it, 1 - f);
                        setVol(nmp, queue.optJSONObject(order.get(npos)), f);
                        if (f >= 1) { promoteNext(); return; }
                    }
                }
                main.postDelayed(this, fading ? 30 : 60);
            }
        }
    };

    private void startPoll() { main.removeCallbacks(poll); main.postDelayed(poll, 60); }

    synchronized void setGain(String id, double db) {
        gains.put(id, db);
        JSONObject it = current();
        if (it != null && id.equals(it.optString("id")) && mp != null) { setVol(mp, it, fading ? 0 : 1); attachFx(mp, it); }
    }

    synchronized void setEq(String json) {
        eqDb = null;
        try { if (json != null && !json.isEmpty()) { JSONArray a = new JSONArray(json); eqDb = new double[5]; for (int i = 0; i < 5; i++) eqDb[i] = a.optDouble(i, 0); } } catch (Exception ignored) { }
        if (mp != null) attachFx(mp, current());
        if (nmp != null) attachFx(nmp, npos >= 0 ? queue.optJSONObject(order.get(npos)) : null);
    }

    synchronized void setXfade(int ms, boolean gap) {
        xfadeMs = Math.max(0, Math.min(12000, ms)); gapless = gap;
        dropNext();
        if (playing && xfadeMs == 0 && gapless) prepareNext();
    }

    synchronized void setLoop(long a, long b) { loopA = a; loopB = b; emit(); }

    synchronized void setSkips(String id, String json) {
        skipsId = id == null ? "" : id;
        try { JSONArray a = new JSONArray(json); skips = new long[a.length()]; for (int i = 0; i < skips.length; i++) skips[i] = a.optLong(i); } catch (Exception e) { skips = new long[0]; }
    }

    synchronized void setSleep(long ms, boolean end) {
        sleepAt = ms > 0 ? System.currentTimeMillis() + ms : 0;
        sleepEnd = end;
        if (playing) startPoll(); else if (sleepAt > 0) main.postDelayed(poll, ms + 50);
        emit();
    }

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(i.getAction())) pause();
        }
    };

    private final AudioManager.OnAudioFocusChangeListener focus = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int change) {
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause();
        }
    };

    private Player() { }

    synchronized void init(Context c, Listener l) {
        app = c.getApplicationContext();
        listener = l;
        if (session == null) {
            session = new MediaSession(app, "VideoDefteri");
            session.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() { play(); }
                @Override public void onPause() { pause(); }
                @Override public void onSkipToNext() { next(); }
                @Override public void onSkipToPrevious() { prev(); }
                @Override public void onSeekTo(long ms) { seek(ms); }
                @Override public void onStop() { stop(); }
            });
            session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        }
    }

    MediaSession.Token token() {
        return session != null ? session.getSessionToken() : null;
    }

    /* ---------- queue ---------- */

    synchronized void setQueue(String json, int index, long startMs, boolean autoplay) {
        try { queue = new JSONArray(json); } catch (Exception e) { queue = new JSONArray(); }
        buildOrder(index);
        pos = shuffle ? 0 : Math.max(0, Math.min(index, queue.length() - 1));
        load(startMs, autoplay);
    }

    /** "add to queue": after the current song (next) or at the end; starts playing when nothing is loaded */
    synchronized void enqueue(String json, boolean next) {
        JSONObject it;
        try { it = new JSONObject(json); } catch (Exception e) { return; }
        if (current() == null) { setQueue("[" + it + "]", 0, 0, true); return; }
        queue.put(it);
        int i = queue.length() - 1;
        if (next) { order.add(pos + 1, i); dropNext(); if (playing && xfadeMs == 0 && gapless) prepareNext(); }
        else order.add(i);
        emit();
    }

    private void buildOrder(int first) {
        order = new ArrayList<>();
        for (int i = 0; i < queue.length(); i++) order.add(i);
        if (shuffle && queue.length() > 1) {
            order.remove(Integer.valueOf(first));
            Collections.shuffle(order, new Random());
            order.add(0, first);
        }
    }

    private JSONObject current() {
        if (pos < 0 || pos >= order.size()) return null;
        return queue.optJSONObject(order.get(pos));
    }

    private void load(long startMs, boolean autoplay) {
        releasePlayer();
        JSONObject it = current();
        if (it == null) { emit(); return; }
        prepared = false;
        wantPlay = autoplay;
        pendingSeek = startMs > 0 ? startMs : -1;
        error = "";
        art = null;
        loopA = loopB = -1;
        try {
            mp = newPlayer(it);
            mp.prepareAsync();
        } catch (Exception e) {
            error = "load";
            releasePlayer();
        }
        updateMeta(it);
        loadArt(it);
        emit();
    }

    @Override
    public void onPrepared(MediaPlayer p) {
        synchronized (this) {
            if (p != mp) return;
            prepared = true;
            attachFx(mp, current());
            setVol(mp, current(), 1);
            if (pendingSeek > 0) seekInternal(pendingSeek);
            pendingSeek = -1;
            if (wantPlay) play();
            else emit();
            JSONObject it = current();
            if (it != null) updateMeta(it);
        }
    }

    @Override
    public void onCompletion(MediaPlayer p) {
        synchronized (this) {
            if (p != mp) return;
            if (sleepEnd) { sleepEnd = false; sleepAt = 0; dropNext(); playing = false; wantPlay = false; abandonFocus(); PlayerService.update(app, false); emit("ended"); return; }
            if (nmp != null && (fading || (xfadeMs == 0 && gapless))) { if (xfadeMs > 0 && !fading) { try { nmp.start(); } catch (Exception ignored) { } } promoteNext(); return; }
            if (repeat == 2) { seekInternal(0); startInternal(); return; }
            if (pos + 1 < order.size()) { pos++; load(0, true); return; }
            if (repeat == 1 && order.size() > 0) { pos = 0; load(0, true); return; }
            playing = false;
            wantPlay = false;
            abandonFocus();
            PlayerService.update(app, false);
            emit("ended");
        }
    }

    @Override
    public boolean onError(MediaPlayer p, int what, int extra) {
        synchronized (this) {
            if (p != mp) return true;
            error = "play";
            playing = false;
            releasePlayer();
            PlayerService.update(app, false);
            emit("error");
        }
        return true;
    }

    /* ---------- controls ---------- */

    synchronized void play() {
        if (mp == null) {
            if (current() != null) load(0, true);
            return;
        }
        if (!prepared) { wantPlay = true; return; }
        if (!requestFocus()) return;
        startInternal();
    }

    private void startInternal() {
        try {
            mp.start();
            applyRate();
            playing = true;
            wantPlay = true;
            startPoll();
            if (xfadeMs == 0 && gapless && nmp == null) prepareNext();
            registerNoisy(true);
            session.setActive(true);
            PlayerService.update(app, true);
        } catch (Exception e) {
            playing = false;
        }
        emit();
    }

    synchronized void pause() {
        wantPlay = false;
        if (fading) dropNext();
        if (mp != null && prepared && playing) {
            try { mp.pause(); } catch (Exception ignored) { }
        }
        playing = false;
        registerNoisy(false);
        PlayerService.update(app, false);
        emit();
    }

    synchronized void toggle() {
        if (playing) pause(); else play();
    }

    synchronized void seek(long ms) {
        if (mp == null) return;
        if (!prepared) { pendingSeek = ms; return; }
        if (fading) { dropNext(); setVol(mp, current(), 1); }
        seekInternal(ms);
        emit("seeked");
    }

    private void seekInternal(long ms) {
        try {
            long d = mp.getDuration();
            long t = Math.max(0, d > 0 ? Math.min(ms, d - 50) : ms);
            if (Build.VERSION.SDK_INT >= 26) mp.seekTo(t, MediaPlayer.SEEK_CLOSEST);
            else mp.seekTo((int) t);
        } catch (Exception ignored) { }
    }

    synchronized void next() {
        if (order.isEmpty()) return;
        if (pos + 1 < order.size()) pos++;
        else if (repeat == 1) pos = 0;
        else return;
        load(0, playing || wantPlay);
    }

    synchronized void prev() {
        if (order.isEmpty()) return;
        if (positionMs() > 3000 || pos == 0) { seek(0); return; }
        pos--;
        load(0, playing || wantPlay);
    }

    synchronized void setRate(float r) {
        rate = Math.max(0.5f, Math.min(r, 3f));
        if (playing) applyRate();
        emit();
    }

    private void applyRate() {
        if (mp == null || !prepared || Build.VERSION.SDK_INT < 23) return;
        try {
            PlaybackParams p = mp.getPlaybackParams();
            p.setSpeed(rate);
            mp.setPlaybackParams(p);
        } catch (Exception ignored) { }
    }

    synchronized void setShuffle(boolean on) {
        if (on == shuffle) return;
        shuffle = on;
        dropNext();
        int curIdx = (pos >= 0 && pos < order.size()) ? order.get(pos) : 0;
        buildOrder(curIdx);
        pos = order.indexOf(curIdx);
        if (pos < 0) pos = 0;
        emit();
    }

    synchronized void setRepeat(int r) {
        repeat = Math.max(0, Math.min(2, r));
        dropNext();
        if (playing && xfadeMs == 0 && gapless) prepareNext();
        emit();
    }

    synchronized void stop() {
        wantPlay = false;
        playing = false;
        releasePlayer();
        queue = new JSONArray();
        order = new ArrayList<>();
        pos = -1;
        abandonFocus();
        registerNoisy(false);
        if (session != null) session.setActive(false);
        PlayerService.stop(app);
        emit();
    }

    /** True while this item is loaded; used before editing/deleting a file. */
    synchronized boolean isCurrent(String id) {
        JSONObject it = current();
        return it != null && id.equals(it.optString("id"));
    }

    synchronized void releaseIfCurrent(String id) {
        if (isCurrent(id)) stop();
    }

    /** Stops playback when the given file (src JSON) is the one loaded, so it can be replaced or deleted. */
    synchronized void releaseIfCurrentSrc(String srcJson) {
        JSONObject it = current();
        if (it == null) return;
        try {
            JSONObject want = new JSONObject(srcJson);
            JSONObject have = it.optJSONObject("src");
            if (have == null) return;
            String a = want.optString("uri", "") + "|" + want.optString("path", "");
            String b = have.optString("uri", "") + "|" + have.optString("path", "");
            if (a.equals(b)) stop();
        } catch (Exception ignored) { }
    }

    private void releasePlayer() {
        dropNext();
        if (mp != null) {
            detachFx(mp);
            try { mp.reset(); } catch (Exception ignored) { }
            try { mp.release(); } catch (Exception ignored) { }
        }
        mp = null;
        prepared = false;
    }

    /* ---------- state ---------- */

    synchronized long positionMs() {
        try { return mp != null && prepared ? mp.getCurrentPosition() : Math.max(0, pendingSeek); } catch (Exception e) { return 0; }
    }

    synchronized long durationMs() {
        try {
            if (mp != null && prepared) { int d = mp.getDuration(); if (d > 0) return d; }
        } catch (Exception ignored) { }
        JSONObject it = current();
        return it != null ? it.optLong("dur", 0) : 0;
    }

    synchronized String state() {
        JSONObject o = new JSONObject();
        try {
            JSONObject it = current();
            o.put("id", it != null ? it.optString("id") : "");
            o.put("index", pos >= 0 && pos < order.size() ? order.get(pos) : -1);
            o.put("count", queue.length());
            o.put("playing", playing);
            o.put("want", wantPlay);
            o.put("prepared", prepared);
            o.put("pos", positionMs());
            o.put("dur", durationMs());
            o.put("rate", rate);
            o.put("shuffle", shuffle);
            o.put("repeat", repeat);
            o.put("error", error);
            o.put("loopA", loopA);
            o.put("loopB", loopB);
            o.put("sleepAt", sleepAt);
            o.put("sleepEnd", sleepEnd);
            o.put("t", System.currentTimeMillis());
        } catch (Exception ignored) { }
        return o.toString();
    }

    private void emit() { emit("state"); }

    private void emit(final String kind) {
        updateSession();
        final Listener l = listener;
        if (l == null) return;
        final String s = state();
        main.post(new Runnable() {
            @Override public void run() { l.onPlayerEvent("{\"kind\":\"" + kind + "\",\"state\":" + s + "}"); }
        });
    }

    /* ---------- session / notification ---------- */

    synchronized String title() {
        JSONObject it = current();
        return it != null ? it.optString("title", "Video Defteri") : "Video Defteri";
    }

    synchronized String subtitle() {
        JSONObject it = current();
        if (it == null) return "";
        String a = it.optString("artist", "");
        return a.isEmpty() ? it.optString("album", "") : a;
    }

    synchronized boolean isPlaying() { return playing; }

    synchronized boolean hasQueue() { return order.size() > 1; }

    synchronized Bitmap art() { return art; }

    private void updateMeta(JSONObject it) {
        if (session == null) return;
        MediaMetadata.Builder b = new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, it.optString("title"))
            .putString(MediaMetadata.METADATA_KEY_ARTIST, it.optString("artist"))
            .putString(MediaMetadata.METADATA_KEY_ALBUM, it.optString("album"))
            .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs());
        if (art != null) b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        session.setMetadata(b.build());
    }

    private void loadArt(final JSONObject it) {
        final long albumId = it.optLong("albumId", -1);
        if (albumId < 0 || app == null) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Bitmap bm = AudioTools.albumArt(app, albumId, 512);
                if (bm == null) return;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        synchronized (Player.this) {
                            JSONObject cur = current();
                            if (cur == null || cur.optLong("albumId", -2) != albumId) return;
                            art = bm;
                            updateMeta(cur);
                            PlayerService.update(app, playing);
                        }
                    }
                });
            }
        }).start();
    }

    private void updateSession() {
        if (session == null) return;
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
            | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP
            | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        session.setPlaybackState(new PlaybackState.Builder()
            .setActions(actions)
            .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, positionMs(), playing ? rate : 0f)
            .build());
    }

    private boolean requestFocus() {
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return true;
        int r;
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusReq == null) {
                focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setOnAudioFocusChangeListener(focus).build();
            }
            r = am.requestAudioFocus(focusReq);
        } else {
            r = am.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        return r != AudioManager.AUDIOFOCUS_REQUEST_FAILED;
    }

    private void abandonFocus() {
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        if (Build.VERSION.SDK_INT >= 26) { if (focusReq != null) am.abandonAudioFocusRequest(focusReq); }
        else am.abandonAudioFocus(focus);
    }

    private void registerNoisy(boolean on) {
        try {
            if (on && !noisyRegistered) {
                IntentFilter f = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
                if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(noisy, f, Context.RECEIVER_EXPORTED);
                else app.registerReceiver(noisy, f);
                noisyRegistered = true;
            } else if (!on && noisyRegistered) {
                app.unregisterReceiver(noisy);
                noisyRegistered = false;
            }
        } catch (Exception ignored) { }
    }
}
