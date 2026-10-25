package app.sesdefteri;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import app.sesdefteri.dsp.Limiter;
import app.sesdefteri.dsp.Node;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Multitrack project: every clip is a 16-bit 48 kHz WAV in files/proj. The page owns the layout;
 * here we only decode new clips, mix them live through an AudioTrack, and render the final mix.
 */
final class Timeline {
    private Timeline() { }

    static final int RATE = 48000;
    static final double HALF_PI = Math.PI / 2;
    static final float ENV_FLOOR = -40;   // the bottom of the volume line is silence

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "proj");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** any audio → project WAV (48 kHz, mono or stereo) */
    static JSONObject add(Context c, JSONObject a, AudioTools.Progress p, AtomicBoolean cancel) throws Exception {
        JSONObject src = a.getJSONObject("src");
        Studio.Fmt f = Studio.probe(c, src);
        File out = new File(dir(c), "c" + System.nanoTime() + ".wav");
        try {
            Workspace.decodeTo(c, src, Math.min(2, f.ch), RATE, p, new double[] { 0, 1 }, cancel, out);
            Workspace.Info i = Workspace.info(out);
            JSONObject o = new JSONObject();
            o.put("path", out.getAbsolutePath());
            o.put("dur", i.frames * 1000.0 / i.rate);
            o.put("ch", i.ch);
            return o;
        } catch (Exception e) {
            out.delete();
            throw e;
        }
    }

    static void clean(Context c, JSONArray keep) {
        java.util.HashSet<String> k = new java.util.HashSet<>();
        if (keep != null) for (int i = 0; i < keep.length(); i++) k.add(keep.optString(i));
        File[] fs = dir(c).listFiles();
        if (fs != null) for (File f : fs) if (!k.contains(f.getAbsolutePath())) f.delete();
    }

    /* ---------- mixing ---------- */

    static final class Clip {
        RandomAccessFile f;
        int ch, rate;
        long frames, at, in, len;
        float g, duck = 1;   // duck: gain while the other tracks are speaking (1 = never ducked)
        int fi, fo, xi, xo;   // xi / xo: crossfades with the neighbouring clip on the same track (equal power)
        double[] et; float[] ed;   // volume line: times in frames from the clip start, levels in dB
        byte[] buf = new byte[0];
        java.util.ArrayList<app.sesdefteri.dsp.Biquad> eq;   // live 5-band EQ (null = flat)
        float[][] tmp = new float[2][0];
    }

    /** same bands as the eqpreset op: shelf 100 Hz, peaks 300 Hz / 1 kHz / 3 kHz, shelf 8 kHz */
    static java.util.ArrayList<app.sesdefteri.dsp.Biquad> eq(JSONArray g, double bass, double treble) {
        java.util.ArrayList<app.sesdefteri.dsp.Biquad> l = new java.util.ArrayList<>();
        // "bass boost" / "treble boost" buttons: wide shelves under the 5-band EQ
        if (bass != 0) l.add(new app.sesdefteri.dsp.Biquad(2).setLowShelf(RATE, 150, 0.6, bass));
        if (treble != 0) l.add(new app.sesdefteri.dsp.Biquad(2).setHighShelf(RATE, 5000, 0.6, treble));
        if (g == null || g.length() < 5) return l.isEmpty() ? null : l;
        double[] mid = { 300, 1000, 3000 };
        double v = g.optDouble(0, 0);
        if (v != 0) l.add(new app.sesdefteri.dsp.Biquad(2).setLowShelf(RATE, 100, 0.707, v));
        for (int i = 0; i < 3; i++) { v = g.optDouble(i + 1, 0); if (v != 0) l.add(app.sesdefteri.dsp.Biquad.peaking(2, RATE, mid[i], 0.9, v)); }
        v = g.optDouble(4, 0);
        if (v != 0) l.add(new app.sesdefteri.dsp.Biquad(2).setHighShelf(RATE, 8000, 0.707, v));
        return l.isEmpty() ? null : l;
    }

    /** clips: [{path, at, in, len (seconds), g (linear), fi, fo, xi, xo (seconds), env: [[t (s from clip start), dB]]}] */
    static final class Engine {
        final Clip[] clips;
        final long end;
        final int ch;

        Engine(JSONArray a) throws Exception {
            java.util.ArrayList<Clip> l = new java.util.ArrayList<>();
            long e = 0;
            int c2 = 1;
            try {
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    File file = new File(o.getString("path"));
                    if (!file.exists()) continue;
                    Workspace.Info inf = Workspace.info(file);
                    Clip k = new Clip();
                    k.ch = inf.ch; k.rate = inf.rate; k.frames = inf.frames;
                    k.at = Math.max(0, Math.round(o.getDouble("at") * RATE));
                    k.in = Math.max(0, Math.min(inf.frames, Math.round(o.getDouble("in") * RATE)));
                    k.len = Math.max(0, Math.min(inf.frames - k.in, Math.round(o.getDouble("len") * RATE)));
                    k.g = (float) o.optDouble("g", 1);
                    k.fi = (int) Math.round(o.optDouble("fi", 0) * RATE);
                    k.fo = (int) Math.round(o.optDouble("fo", 0) * RATE);
                    k.xi = (int) Math.round(o.optDouble("xi", 0) * RATE);
                    k.xo = (int) Math.round(o.optDouble("xo", 0) * RATE);
                    JSONArray ev = o.optJSONArray("env");
                    if (ev != null && ev.length() > 0) {
                        k.et = new double[ev.length()]; k.ed = new float[ev.length()];
                        for (int j = 0; j < ev.length(); j++) { JSONArray pt = ev.getJSONArray(j); k.et[j] = pt.getDouble(0) * RATE; k.ed[j] = (float) pt.getDouble(1); }
                    }
                    double dd = o.optDouble("duck", 0);
                    if (dd < 0) k.duck = (float) Math.pow(10, dd / 20);
                    k.eq = eq(o.optJSONArray("eq"), o.optDouble("bass", 0), o.optDouble("treble", 0));
                    if (k.len <= 0 || k.g <= 0) continue;
                    k.f = new RandomAccessFile(file, "r");
                    l.add(k);
                    e = Math.max(e, k.at + k.len);
                    if (k.ch > 1) c2 = 2;
                }
            } catch (Exception x) {
                for (Clip k : l) try { k.f.close(); } catch (Exception ignored) { }
                throw x;
            }
            clips = l.toArray(new Clip[0]);
            end = e;
            ch = c2;
        }

        // ducking state: level of the other tracks and how deep the ducked ones are pulled down (0..1)
        private float env, depth;
        private float[] dep = new float[0];
        private static final float THR = 0.02f, E_ATK = (float) (1 - Math.exp(-1.0 / (0.005 * RATE))), E_REL = (float) Math.exp(-1.0 / (0.25 * RATE)),
            D_ATK = (float) (1 - Math.exp(-1.0 / (0.08 * RATE))), D_REL = (float) (1 - Math.exp(-1.0 / (0.6 * RATE)));

        /** frames [pos, pos+n) of every clip mixed into out (2 channels, cleared here) */
        void render(long pos, int n, float[][] out) throws Exception {
            for (float[] o : out) java.util.Arrays.fill(o, 0, n, 0f);
            boolean ducked = false;
            for (Clip k : clips) { if (k.duck < 1) ducked = true; else add(k, pos, n, out, null); }
            if (!ducked) return;
            if (dep.length < n) dep = new float[n];
            for (int i = 0; i < n; i++) {
                float x = Math.max(Math.abs(out[0][i]), Math.abs(out[1][i]));
                env = x > env ? env + (x - env) * E_ATK : env * E_REL;
                float tg = env > THR ? 1 : 0;
                depth += (tg - depth) * (tg > depth ? D_ATK : D_REL);
                dep[i] = depth;
            }
            for (Clip k : clips) if (k.duck < 1) add(k, pos, n, out, dep);
        }

        private void add(Clip k, long pos, int n, float[][] out, float[] dep) throws Exception {
            {
                long a = Math.max(pos, k.at), z = Math.min(pos + n, k.at + k.len);
                if (z <= a) return;
                int cnt = (int) (z - a), off = (int) (a - pos), bytes = cnt * k.ch * 2;
                if (k.buf.length < bytes) k.buf = new byte[bytes];
                k.f.seek(44 + (k.in + a - k.at) * k.ch * 2);
                k.f.readFully(k.buf, 0, bytes);
                byte[] b = k.buf;
                int q = 0;
                float[] L = out[0], Rr = out[1];
                int base = off;
                double[] et = k.et;
                int j = 0;
                if (et != null) while (j < et.length - 1 && et[j + 1] <= a - k.at) j++;
                if (k.eq != null) {
                    if (k.tmp[0].length < cnt) k.tmp = new float[2][cnt];
                    L = k.tmp[0]; Rr = k.tmp[1]; base = 0;
                    java.util.Arrays.fill(L, 0, cnt, 0f); java.util.Arrays.fill(Rr, 0, cnt, 0f);
                }
                for (int i = 0; i < cnt; i++) {
                    long r = a - k.at + i;
                    float g = k.g;
                    if (k.fi > 0 && r < k.fi) g *= (float) r / k.fi;
                    if (k.fo > 0 && k.len - r < k.fo) g *= (float) (k.len - r) / k.fo;
                    if (k.xi > 0 && r < k.xi) g *= (float) Math.sin(HALF_PI * r / k.xi);
                    if (k.xo > 0 && k.len - r < k.xo) g *= (float) Math.sin(HALF_PI * (k.len - r) / k.xo);
                    if (et != null) {
                        float db;
                        if (r <= et[0]) db = k.ed[0];
                        else {
                            while (j < et.length - 1 && et[j + 1] <= r) j++;
                            db = j == et.length - 1 ? k.ed[j] : k.ed[j] + (float) ((k.ed[j + 1] - k.ed[j]) * (r - et[j]) / (et[j + 1] - et[j]));
                        }
                        g *= db <= ENV_FLOOR ? 0 : (float) Math.exp(db * 0.11512925);
                    }
                    if (dep != null) g *= 1 - dep[off + i] * (1 - k.duck);
                    float l = (short) ((b[q] & 0xff) | (b[q + 1] << 8)) / 32768f;
                    q += 2;
                    float rr = l;
                    if (k.ch > 1) { rr = (short) ((b[q] & 0xff) | (b[q + 1] << 8)) / 32768f; q += 2; }
                    L[base + i] += l * g;
                    Rr[base + i] += rr * g;
                }
                if (k.eq != null) {
                    for (app.sesdefteri.dsp.Biquad f : k.eq) { f.process(0, L, 0, cnt); f.process(1, Rr, 0, cnt); }
                    for (int i = 0; i < cnt; i++) { out[0][off + i] += L[i]; out[1][off + i] += Rr[i]; }
                }
            }
        }

        void close() { for (Clip k : clips) try { k.f.close(); } catch (Exception ignored) { } }
    }

    /* ---------- live playback ---------- */

    static final class Play implements Runnable {
        static Play cur;
        final long start;
        volatile Engine pending;
        volatile boolean stop;
        Engine eng;
        AudioTrack track;

        Play(Engine e, long start) { eng = e; this.start = start; }

        @Override public void run() {
            final int BL = 1024;
            float[][] mix = new float[2][BL];
            short[] pcm = new short[BL * 2];
            long pos = start;
            try {
                track.play();
                while (!stop) {
                    Engine p = pending;
                    if (p != null) { pending = null; eng.close(); eng = p; }
                    if (pos >= eng.end) break;
                    int n = (int) Math.min(BL, eng.end - pos);
                    eng.render(pos, n, mix);
                    for (int i = 0, j = 0; i < n; i++) {
                        for (int c = 0; c < 2; c++, j++) {
                            float v = mix[c][i], av = Math.abs(v);
                            // soft knee above -1 dB so EQ / bass boosts don't crackle while previewing (export uses the real limiter)
                            if (av > 0.89f) v = Math.signum(v) * (0.89f + 0.11f * (float) Math.tanh((av - 0.89f) / 0.11f));
                            pcm[j] = (short) (v * 32767);
                        }
                    }
                    track.write(pcm, 0, n * 2);
                    pos += n;
                }
                if (!stop) {
                    // let the buffer drain so the end isn't cut off
                    long want = pos - start;
                    for (int i = 0; i < 100 && !stop && (track.getPlaybackHeadPosition() & 0xffffffffL) < want; i++) Thread.sleep(20);
                }
            } catch (Exception ignored) {
            } finally {
                try { track.stop(); } catch (Exception ignored) { }
                track.release();
                eng.close();
                Engine p = pending;
                if (p != null) p.close();
                synchronized (Play.class) { if (cur == this) cur = null; }
            }
        }
    }

    static synchronized void play(String json, double sec) throws Exception {
        stop();
        Engine e = new Engine(new JSONArray(json));
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        Play p = new Play(e, Math.max(0, Math.round(sec * RATE)));
        p.track = new AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
            Math.max(min * 2, RATE / 10 * 4), AudioTrack.MODE_STREAM);
        synchronized (Play.class) { Play.cur = p; }
        Thread th = new Thread(p, "timeline");
        th.setPriority(Thread.MAX_PRIORITY);
        th.start();
    }

    /** new layout while playing (volume, fades, moved clips) without a restart */
    static void update(String json) throws Exception {
        Play p;
        synchronized (Play.class) { p = Play.cur; }
        if (p == null) return;
        Engine e = new Engine(new JSONArray(json)), old = p.pending;
        p.pending = e;
        if (old != null) old.close();
    }

    static void stop() {
        Play p;
        synchronized (Play.class) { p = Play.cur; Play.cur = null; }
        if (p != null) p.stop = true;
    }

    /** seconds played, or -1 when stopped */
    static double pos() {
        Play p;
        synchronized (Play.class) { p = Play.cur; }
        if (p == null) return -1;
        try { return (p.start + (p.track.getPlaybackHeadPosition() & 0xffffffffL)) / (double) RATE; } catch (Exception e) { return p.start / (double) RATE; }
    }

    /* ---------- final mix ---------- */

    static JSONObject render(Context c, JSONObject a, AudioTools.Progress p, AtomicBoolean cancel) throws Exception {
        Engine e = new Engine(a.getJSONArray("clips"));
        // "proj": a merged clip that stays in the project; otherwise a temporary mix for export
        File out = "proj".equals(a.optString("dir")) ? new File(dir(c), "m" + System.nanoTime() + ".wav") : Workspace.newFile(c);
        try {
            if (e.end <= 0) throw new IllegalStateException("empty");
            final int BL = 4096, ch = e.ch;
            float[][] mix = new float[2][BL], o = new float[ch][BL];
            Node head = new Studio.WavNode(out, RATE);
            if (a.optBoolean("limit", true)) { Limiter lim = new Limiter(ch, RATE, -1.0, 0); lim.to(head); head = lim; }
            for (long pos = 0; pos < e.end; pos += BL) {
                if (cancel != null && cancel.get()) throw new InterruptedException("cancel");
                int n = (int) Math.min(BL, e.end - pos);
                e.render(pos, n, mix);
                if (ch == 1) System.arraycopy(mix[0], 0, o[0], 0, n);
                else { System.arraycopy(mix[0], 0, o[0], 0, n); System.arraycopy(mix[1], 0, o[1], 0, n); }
                head.push(o, n);
                if (p != null && (pos / BL) % 32 == 0) p.on((double) pos / e.end);
            }
            head.end();
            JSONObject r = new JSONObject();
            r.put("path", out.getAbsolutePath());
            r.put("dur", e.end * 1000.0 / RATE);
            r.put("ch", ch);
            return r;
        } catch (Exception x) {
            out.delete();
            throw x;
        } finally {
            e.close();
        }
    }
}
