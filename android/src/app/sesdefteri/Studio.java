package app.sesdefteri;

import android.content.Context;

import app.sesdefteri.asr.Keywords;
import app.sesdefteri.asr.Transcriber;
import app.sesdefteri.dsp.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Android side of the sound studio: decoding into the pure-Java processing chains, encoding, saving. */
final class Studio {
    private Studio() { }

    static final class Fmt { int ch = 1, rate = 44100; }

    private static Boolean asr;

    /** speech-to-text needs the native library (shipped for 64-bit ARM phones) */
    static synchronized boolean asrAvailable() {
        if (asr == null) {
            try { System.loadLibrary("sherpa-onnx-jni"); asr = true; } catch (Throwable e) { asr = false; }
        }
        return asr;
    }

    private static final class Probed extends RuntimeException { Probed() { super("probed"); } }

    /** channel count and sample rate as the decoder actually delivers them */
    static Fmt probe(Context c, JSONObject src) throws Exception {
        final Fmt f = new Fmt();
        try {
            AudioTools.decode(c, src, new AudioTools.PcmSink() {
                @Override public void pcm(short[] d, int count, int ch, int rate) {
                    f.ch = ch; f.rate = rate;
                    throw new Probed();
                }
            }, null, null);
        } catch (Probed ignored) { }
        f.ch = Math.max(1, Math.min(2, f.ch));
        return f;
    }

    /** a Pipeline.Source that decodes the file on every run; pass k reports progress in its own slice */
    static Pipeline.Source source(final Context c, final JSONObject src, final Fmt f, final AudioTools.Progress prog,
                                  final double[][] slices, final AtomicBoolean cancel) {
        return new Pipeline.Source() {
            int pass = 0;

            @Override public void run(final Node sink) throws Exception {
                final double[] sl = slices != null && pass < slices.length ? slices[pass] : null;
                pass++;
                final float[][] buf = new float[f.ch][4096];
                AudioTools.decode(c, src, new AudioTools.PcmSink() {
                    @Override public void pcm(short[] d, int count, int ch, int rate) throws Exception {
                        int frames = count / ch, p = 0;
                        while (p < frames) {
                            int n = Math.min(4096, frames - p);
                            for (int i = 0; i < n; i++) {
                                int b = (p + i) * ch;
                                for (int k = 0; k < f.ch; k++) buf[k][i] = d[b + Math.min(k, ch - 1)] / 32768f;
                            }
                            sink.push(buf, n);
                            p += n;
                        }
                    }
                }, sl == null || prog == null ? null : new AudioTools.Progress() {
                    @Override public void on(double x) { prog.on(sl[0] + (sl[1] - sl[0]) * x); }
                }, cancel);
                sink.end();
            }
        };
    }

    /** planar float → AAC encoder (16-bit) */
    static final class EncNode extends Node {
        final AudioTools.Encoder enc;
        final int rate, bitrate;
        short[] s = new short[0];
        boolean done;

        EncNode(File out, int rate, int bitrate) { enc = new AudioTools.Encoder(out); this.rate = rate; this.bitrate = bitrate; }

        @Override public void push(float[][] b, int n) throws Exception {
            int ch = b.length;
            if (!enc.started) { enc.bitrate = bitrate; enc.start(rate, ch); }
            if (s.length < n * ch) s = new short[n * ch];
            int q = 0;
            for (int i = 0; i < n; i++) for (int c = 0; c < ch; c++) {
                float v = b[c][i];
                int x = Math.round(v * 32767f);
                s[q++] = (short) (x > 32767 ? 32767 : x < -32768 ? -32768 : x);
            }
            enc.feed(s, q);
        }

        @Override public void end() throws Exception { if (!done) { done = true; enc.finish(); } }

        void abort() { enc.abort(); }
    }

    /** planar float → 16-bit WAV file */
    static final class WavNode extends Node {
        final File f;
        final int rate;
        java.io.OutputStream os;
        Wav.Writer w;
        long frames;
        int ch;

        WavNode(File f, int rate) { this.f = f; this.rate = rate; }

        @Override public void push(float[][] b, int n) throws Exception {
            if (w == null) {
                ch = b.length;
                os = new java.io.BufferedOutputStream(new java.io.FileOutputStream(f), 1 << 16);
                w = new Wav.Writer(os, ch, rate, false, 0);
            }
            w.write(b, n);
            frames += n;
        }

        @Override public void end() throws Exception {
            if (os != null) os.close();
            RandomAccessFile raf = new RandomAccessFile(f, "rw");
            try { Wav.patch(raf, frames, Math.max(1, ch), rate, false); } finally { raf.close(); }
        }
    }

    static double[] doubles(JSONArray a) {
        if (a == null) return null;
        double[] d = new double[a.length()];
        for (int i = 0; i < d.length; i++) d[i] = a.optDouble(i, 0);
        return d;
    }

    static JSONArray json(double[] d) throws Exception {
        JSONArray a = new JSONArray();
        if (d != null) for (double v : d) a.put(Math.round(v * 100) / 100.0);
        return a;
    }

    static String safe(String n) {
        String s = n == null ? "" : n.replaceAll("[\\\\/:*?\"<>|]+", " ").replaceAll("\\s+", " ").trim();
        if (s.length() > 80) s = s.substring(0, 80).trim();
        return s.isEmpty() ? "Video Defteri" : s;
    }

    static JSONObject beatsJson(Beats b, double shift) throws Exception {
        JSONObject o = new JSONObject();
        o.put("bpm", Math.round(b.bpm * 10) / 10.0);
        o.put("confidence", Math.round(b.confidence * 100) / 100.0);
        JSONArray t = new JSONArray();
        if (b.confidence >= 0.35) for (double x : b.times) { double v = x - shift; if (v >= 0) t.put(Math.round(v * 1000) / 1000.0); }
        o.put("times", t);
        return o;
    }

    /* ---------- beats only ---------- */

    static JSONObject beats(Context c, JSONObject src, AudioTools.Progress prog, AtomicBoolean cancel) throws Exception {
        Fmt f = probe(c, src);
        Analyzer an = new Analyzer(f.ch, f.rate);
        source(c, src, f, prog, new double[][] { { 0, 1 } }, cancel).run(an);
        Analyzer.Analysis a = an.finish();
        return beatsJson(Beats.track(a.onset, a.onsetRate), -a.fftSize / 2.0 / a.fs);
    }

    /* ---------- speech to text ---------- */

    static JSONObject transcribe(Context c, JSONObject src, String lang, final AudioTools.Progress prog, AtomicBoolean cancel) throws Exception {
        Fmt f = probe(c, src);
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
        final Transcriber tr;
        try {
            String model = AsrModels.pick(c, src.optString("_model", ""));
            if (model == null) throw new IllegalStateException("asr-missing");
            tr = new Transcriber(null, AsrModels.dir(c, model).getAbsolutePath(), model, lang == null ? "" : lang, threads);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("asr-unavailable: " + e.getMessage());
        }
        try {
            Stages.ChannelMap mono = new Stages.ChannelMap(f.ch, Stages.ChannelMap.MONO);
            Resampler rs = new Resampler(1, (double) f.rate, (double) Transcriber.RATE);
            // rumble and handling noise below 80 Hz only confuse the recogniser
            mono.to(rs).to(new Stages.Filters(1, Transcriber.RATE, 80, null)).to(new Node() {
                @Override public void push(float[][] b, int n) { tr.accept(b[0], n); }
                @Override public void end() { }
            });
            source(c, src, f, prog, new double[][] { { 0, 0.98 } }, cancel).run(mono);
            List<Transcriber.Segment> segs = tr.finish();
            JSONObject res = new JSONObject();
            JSONArray sa = new JSONArray();
            for (Transcriber.Segment s : segs) {
                JSONArray x = new JSONArray();
                x.put(Math.round(s.start * 100) / 100.0); x.put(Math.round(s.end * 100) / 100.0); x.put(s.text);
                sa.put(x);
            }
            String text = Transcriber.text(segs);
            res.put("segments", sa);
            res.put("text", text);
            res.put("srt", Transcriber.srt(segs));
            JSONArray tags = new JSONArray();
            for (String s : Keywords.tags(text, 5)) tags.put(s);
            res.put("tags", tags);
            res.put("title", Keywords.title(segs));
            res.put("lang", tr.detectedLang);
            return res;
        } finally {
            tr.release();
        }
    }

}
