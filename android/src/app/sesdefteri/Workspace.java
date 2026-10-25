package app.sesdefteri;

import android.content.Context;

import app.sesdefteri.dsp.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The "Edit sound" workspace. Every step reads one 16-bit WAV from the cache and writes the next one,
 * so each stage can be played, compared and undone, and nothing is re-encoded until the user saves.
 */
final class Workspace {
    private Workspace() { }

    static File dir(Context c) {
        File d = new File(c.getCacheDir(), "ws");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static File newFile(Context c) { return new File(dir(c), "s" + System.nanoTime() + ".wav"); }

    /** our own WAV files always have the canonical 44-byte header */
    static final class Info { int ch, rate; long frames; }

    static Info info(File f) throws Exception {
        RandomAccessFile r = new RandomAccessFile(f, "r");
        try {
            byte[] h = new byte[44];
            r.readFully(h);
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(h).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            Info i = new Info();
            i.ch = b.getShort(22);
            i.rate = b.getInt(24);
            i.frames = (r.length() - 44) / (2L * i.ch);
            return i;
        } finally {
            r.close();
        }
    }

    /** streams a workspace WAV as planar floats */
    static Pipeline.Source wav(final File f, final Info in, final AudioTools.Progress prog, final double[] slice, final AtomicBoolean cancel) {
        return new Pipeline.Source() {
            @Override public void run(Node sink) throws Exception {
                final int BL = 4096;
                float[][] x = new float[in.ch][BL];
                byte[] rb = new byte[BL * in.ch * 2];
                long done = 0;
                InputStream is = new BufferedInputStream(new FileInputStream(f), 1 << 16);
                try {
                    if (is.skip(44) != 44) throw new IllegalStateException("wav");
                    while (done < in.frames) {
                        if (cancel != null && cancel.get()) throw new InterruptedException("cancel");
                        int n = (int) Math.min(BL, in.frames - done), need = n * in.ch * 2, got = 0;
                        while (got < need) { int r = is.read(rb, got, need - got); if (r < 0) break; got += r; }
                        n = got / (in.ch * 2);
                        if (n <= 0) break;
                        int q = 0;
                        for (int i = 0; i < n; i++) for (int c = 0; c < in.ch; c++, q += 2) x[c][i] = (short) ((rb[q] & 0xff) | (rb[q + 1] << 8)) / 32768f;
                        sink.push(x, n);
                        done += n;
                        if (prog != null && slice != null && (done & 0x3ffff) < BL) prog.on(slice[0] + (slice[1] - slice[0]) * done / in.frames);
                    }
                } finally {
                    is.close();
                }
                sink.end();
            }
        };
    }

    /** pass-through meter: loudness, loudness range, sample peak */
    static final class Meter extends Node {
        final Loudness l;
        double peak;

        Meter(int ch, int rate) { l = new Loudness(ch, rate); }

        @Override public void push(float[][] b, int n) throws Exception {
            l.measure(b, n);
            for (float[] c : b) for (int i = 0; i < n; i++) { float a = Math.abs(c[i]); if (a > peak) peak = a; }
            next.push(b, n);
        }
    }

    static Analyzer.Analysis analyze(File f, Info in, AudioTools.Progress p, AtomicBoolean cancel) throws Exception {
        Analyzer an = new Analyzer(in.ch, in.rate);
        wav(f, in, p, new double[] { 0, 0.3 }, cancel).run(an);
        return an.finish();
    }

    /** noise spectrum of a stretch the user marked as "only noise" (mean power over all its frames) */
    static double[] learnNoise(File in, Info inf, double t0, double t1, AtomicBoolean cancel) throws Exception {
        if (t1 - t0 < 0.2) return null;
        Analyzer an = new Analyzer(inf.ch, inf.rate);
        Keep k = new Keep(new JSONArray().put(new JSONArray().put(t0 * 1000).put(t1 * 1000)), inf.rate);
        k.to(an);
        wav(in, inf, null, new double[] { 0, 1 }, cancel).run(k);
        Analyzer.Analysis r = an.finish();
        double[] p = r.noisePower, l = r.ltasPower;
        if (p == null) return null;
        double[] o = new double[p.length];
        for (int i = 0; i < o.length; i++) o[i] = Math.max(p[i] * 1.4, l != null && l.length == o.length && l[i] > 0 ? l[i] : 0);
        return o;
    }

    static JSONObject done(File out, Meter m, JSONObject report) throws Exception {
        Info i = info(out);
        JSONObject o = new JSONObject();
        JSONObject src = new JSONObject();
        src.put("path", out.getAbsolutePath());
        o.put("src", src);
        o.put("path", out.getAbsolutePath());
        o.put("dur", Math.round(i.frames * 1000.0 / i.rate));
        o.put("ch", i.ch);
        o.put("rate", i.rate);
        double lufs = m.l.integrated();
        o.put("lufs", Double.isInfinite(lufs) ? -99 : Math.round(lufs * 10) / 10.0);
        o.put("lra", Math.round(m.l.range() * 10) / 10.0);
        o.put("peak", Math.round(20 * Math.log10(Math.max(1e-9, m.peak)) * 10) / 10.0);
        o.put("report", report == null ? new JSONObject() : report);
        return o;
    }

    /* ---------- start: any audio → workspace WAV ---------- */

    static JSONObject init(Context c, JSONObject src, AudioTools.Progress p, AtomicBoolean cancel) throws Exception {
        clean(c, null);
        Studio.Fmt f = Studio.probe(c, src);
        File out = newFile(c);
        Meter m = new Meter(f.ch, f.rate);
        m.to(new Studio.WavNode(out, f.rate));
        Studio.source(c, src, f, p, new double[][] { { 0, 1 } }, cancel).run(m);
        return done(out, m, null);
    }

    /** deletes workspace files except the listed ones */
    static void clean(Context c, JSONArray keep) {
        java.util.HashSet<String> k = new java.util.HashSet<>();
        if (keep != null) for (int i = 0; i < keep.length(); i++) k.add(keep.optString(i));
        File[] fs = dir(c).listFiles();
        if (fs != null) for (File f : fs) if (!k.contains(f.getAbsolutePath())) f.delete();
    }

    /* ---------- one step ---------- */

    static JSONObject op(Context c, JSONObject a, final AudioTools.Progress p, AtomicBoolean cancel) throws Exception {
        File in = new File(a.getString("path"));
        Info inf = info(in);
        String op = a.getString("op");
        File out = new File(in.getParentFile(), "s" + System.nanoTime() + ".wav");
        JSONObject rep = new JSONObject();
        int ch = inf.ch, fs = inf.rate;
        final double str = Math.max(0.3, Math.min(1.6, a.optDouble("strength", 1)));   // Light 0.6 / Balanced 1 / Strong 1.4
        try {
            if ("insert".equals(op)) return insert(c, in, inf, out, a, p, cancel);
            if ("aidenoise".equals(op)) return aiDenoise(c, in, inf, out, str, p, cancel);
            if ("gate".equals(op)) return gate(in, inf, out, str, p, cancel);
            if ("sepdenoise".equals(op)) return sepDenoise(c, in, inf, p, cancel);
            if ("pauses".equals(op)) return pauses(in, inf, out, a, p, cancel);
            if ("breaths".equals(op)) return breaths(in, inf, out, str, p, cancel);
            if ("declip".equals(op)) return declip(in, inf, out, p, cancel);
            Node head;
            Meter m;
            double[] slice = { 0.3, 1 };
            switch (op) {
                case "cut": {
                    m = new Meter(ch, fs);
                    head = new Keep(a.getJSONArray("keep"), fs);
                    head.to(m);
                    slice = new double[] { 0, 1 };
                    break;
                }
                case "denoise": {
                    Analyzer.Analysis an = analyze(in, inf, p, cancel);
                    double nr = Math.max(4, Math.min(24, (Math.max(6, Math.min(18, (42 - an.snr) * 0.55 + 6))) * str));
                    double[] prof = a.has("profile") ? Studio.doubles(a.optJSONArray("profile")) : null;
                    if (prof != null && prof.length == an.noisePower.length) rep.put("room", true); else prof = an.noisePower;
                    JSONArray lr = a.optJSONArray("learn");   // [from, to] seconds of noise only, picked by the user
                    if (lr != null && lr.length() == 2) {
                        double[] lp = learnNoise(in, inf, lr.getDouble(0), lr.getDouble(1), cancel);
                        if (lp != null && lp.length == an.noisePower.length) { prof = lp; rep.put("learned", true); nr = Math.min(30, nr + 4); }
                    }
                    Spectral s = new Spectral(ch, fs).noise(prof, nr, 1.0);
                    if (a.optBoolean("track", true)) s.tracking(fs);
                    Analyzer after = new Analyzer(ch, fs);
                    m = new Meter(ch, fs);
                    s.to(new Tee(after)).to(m);
                    head = s;
                    rep.put("reduction", Math.round(nr));
                    rep.put("before", Math.round(an.noiseDb));
                    rep.put("_after", true);
                    tees.set(after);
                    break;
                }
                case "hum": {
                    Analyzer.Analysis an = analyze(in, inf, p, cancel);
                    double[] hr = an.humRatioDb;
                    double h50 = Math.max(hr[0], hr[1]), h60 = Math.max(hr[2], hr[3]);
                    double base = h60 > h50 ? 60 : 50;
                    if (Math.max(h50, h60) < 3) { rep.put("none", true); return same(a, rep); }
                    double[] notch = { base, base * 2, base * 3, base * 4, base * 5 };
                    rep.put("hz", (int) base);
                    rep.put("strength", Math.round(Math.max(h50, h60)));
                    m = new Meter(ch, fs);
                    head = new Stages.Filters(ch, fs, 0, notch);
                    head.to(m);
                    break;
                }
                case "trim": {
                    Analyzer.Analysis an = analyze(in, inf, p, cancel);
                    long s = Math.max(0, an.activeStart - Math.round(0.3 * fs)), e = Math.min(an.samples, an.activeEnd + Math.round(0.5 * fs));
                    if (e - s < fs) { s = 0; e = an.samples; }
                    rep.put("start", Math.round(s * 100.0 / fs) / 100.0);
                    rep.put("end", Math.round((an.samples - e) * 100.0 / fs) / 100.0);
                    m = new Meter(ch, fs);
                    head = new Stages.Trim(s, e);
                    head.to(m);
                    break;
                }
                case "clicks": {
                    final Stages.ClickRepair cr = new Stages.ClickRepair(ch, fs, 10);
                    m = new Meter(ch, fs);
                    cr.to(m);
                    head = cr;
                    clicks.set(cr);
                    slice = new double[] { 0, 1 };
                    break;
                }
                case "plosive": {
                    Stages.Filters hp = new Stages.Filters(ch, fs, 75, null);
                    Stages.PlosiveTamer pt = new Stages.PlosiveTamer(ch, fs, 1 + 0.8 * str);
                    m = new Meter(ch, fs);
                    hp.to(pt).to(m);
                    head = hp;
                    plos.set(pt);
                    slice = new double[] { 0, 1 };
                    break;
                }
                case "deess": {
                    Analyzer.Analysis an = analyze(in, inf, p, cancel);
                    Stages.DeEsser d = new Stages.DeEsser(ch, fs, Math.pow(10, (an.speechDb - 12) / 20), 9 * str);
                    m = new Meter(ch, fs);
                    d.to(m);
                    head = d;
                    dees.set(d);
                    break;
                }
                case "channels": {
                    if (ch < 2) { rep.put("reason", "mono"); return same(a, rep); }
                    Analyzer.Analysis an = analyze(in, inf, p, cancel);
                    int mode = an.channelMode == Stages.ChannelMap.KEEP ? Stages.ChannelMap.MONO : an.channelMode;
                    rep.put("reason", an.channelMode == Stages.ChannelMap.KEEP ? "mono" : an.channelReason);
                    m = new Meter(1, fs);
                    head = new Stages.ChannelMap(ch, mode);
                    head.to(m);
                    break;
                }
                case "eq": {
                    Analyzer.Analysis an = analyze(in, inf, p, cancel);
                    double[] measured = Enhancer.bandsOf(an.ltasPower, fs, an.fftSize);
                    double[] target = a.has("learned") && !a.isNull("learned") ? Studio.doubles(a.optJSONArray("learned")) : Enhancer.referenceBands();
                    double[] g = eqGains(measured, target, str);
                    double mean = 0;
                    for (double x : g) mean += Math.abs(x) / g.length;
                    if (mean < 0.5) { rep.put("none", true); return same(a, rep); }
                    Spectral s = new Spectral(ch, fs).eq(Enhancer.eqCurve(g, fs, Fft.nextPow2((int) Math.round(fs * 0.021))));
                    m = new Meter(ch, fs);
                    s.to(m);
                    head = s;
                    rep.put("mean", Math.round(mean * 10) / 10.0);
                    double[] after = new double[g.length];
                    for (int i = 0; i < g.length; i++) after[i] = measured[i] + g[i];
                    rep.put("tone", Studio.json(after));
                    break;
                }
                case "level": {
                    Analyzer.Analysis an = analyze(in, inf, p, cancel);
                    rep.put("lraBefore", Math.round(an.lra * 10) / 10.0);
                    if (an.lra < 4) { rep.put("none", true); return same(a, rep); }
                    m = new Meter(ch, fs);
                    head = new Stages.Compressor(ch, fs, an.speechMeanDb + 2, 1 + 1.5 * str);
                    head.to(m);
                    break;
                }
                case "loud": {
                    Enhancer.Target t = Enhancer.Target.of(a.optString("target", "youtube"));
                    double gain = loudGain(in, inf, t, p, cancel);
                    rep.put("target", t.id);
                    rep.put("gain", Math.round(gain * 10) / 10.0);
                    m = new Meter(ch, fs);
                    head = new Limiter(ch, fs, t.ceilingDb, gain);
                    head.to(m);
                    slice = new double[] { 0.6, 1 };
                    break;
                }
                case "mouth": {
                    // lip and tongue clicks: smaller and a little longer than vinyl clicks, so a more sensitive detector
                    final Stages.ClickRepair cr = new Stages.ClickRepair(ch, fs, 16 + 14 * str, 0.004);
                    m = new Meter(ch, fs);
                    cr.to(m);
                    head = cr;
                    clicks.set(cr);
                    slice = new double[] { 0, 1 };
                    break;
                }
                case "stereo": {
                    if (ch >= 2) { rep.put("none", true); return same(a, rep); }
                    m = new Meter(2, fs);
                    head = new Node() {
                        float[][] o = new float[2][0];
                        @Override public void push(float[][] b, int n) throws Exception {
                            if (o[0].length < n) o = new float[2][n];
                            System.arraycopy(b[0], 0, o[0], 0, n); System.arraycopy(b[0], 0, o[1], 0, n);
                            next.push(o, n);
                        }
                    };
                    head.to(m);
                    slice = new double[] { 0, 1 };
                    break;
                }
                case "dereverb": {
                    Spectral sp = new Spectral(ch, fs).dereverb(fs, a.optDouble("t60", 0.6), Math.max(0.5, Math.min(1.5, str)));
                    m = new Meter(ch, fs);
                    sp.to(m);
                    head = sp;
                    slice = new double[] { 0, 1 };
                    break;
                }
                case "comp": {
                    double thr = Math.max(-50, Math.min(-6, a.optDouble("thr", -24))), ratio = Math.max(1.2, Math.min(10, a.optDouble("ratio", 3)));
                    double makeup = Math.min(12, -thr * (1 - 1 / ratio) * 0.5);
                    m = new Meter(ch, fs);
                    head = new Stages.Compressor(ch, fs, thr, ratio);
                    head.to(new Limiter(ch, fs, -1.0, makeup)).to(m);
                    rep.put("makeup", Math.round(makeup * 10) / 10.0);
                    slice = new double[] { 0, 1 };
                    break;
                }
                case "reverb": {
                    m = new Meter(ch, fs);
                    head = new Stages.Reverb(ch, fs, a.optDouble("room", 0.5), a.optDouble("mix", 0.3));
                    head.to(new Limiter(ch, fs, -1.0, 0)).to(m);
                    slice = new double[] { 0, 1 };
                    break;
                }
                case "delay": {
                    m = new Meter(ch, fs);
                    head = new Stages.Delay(ch, fs, a.optDouble("ms", 300), a.optDouble("fb", 0.35), a.optDouble("mix", 0.35));
                    head.to(new Limiter(ch, fs, -1.0, 0)).to(m);
                    slice = new double[] { 0, 1 };
                    break;
                }
                case "eqpreset": {
                    double[] g = Studio.doubles(a.getJSONArray("gains"));
                    m = new Meter(ch, fs);
                    head = Stages.Filters.eq5(ch, fs, g);
                    head.to(new Limiter(ch, fs, -1.0, 0)).to(m);   // boosts must not clip
                    slice = new double[] { 0, 1 };
                    break;
                }
                case "speed": {
                    double sp = Math.max(0.5, Math.min(2, a.optDouble("speed", 1))), st = Math.max(-12, Math.min(12, a.optDouble("semitones", 0)));
                    m = new Meter(ch, fs);
                    TimePitch tp = new TimePitch(ch, fs, sp, st, a.optBoolean("formant", true));
                    rep.put("speed", sp);
                    rep.put("semitones", st);
                    tp.to(m);
                    head = tp;
                    slice = new double[] { 0, 1 };
                    break;
                }
                case "clarify": {
                    // 2.7 "make the voice clearer": measured against an average voice, then mud / presence / air / exciter, compressor, level
                    Analyzer.Analysis an = analyze(in, inf, p, cancel);
                    double[] mb = Enhancer.bandsOf(an.ltasPower, fs, an.fftSize), ref = Enhancer.referenceBands();
                    double mud = bandDiff(mb, ref, 200, 400), pres = bandDiff(mb, ref, 2000, 4000), air = bandDiff(mb, ref, 6300, 10000);
                    double mudDb = -Math.min(8, (1.5 + Math.max(0, mud) * 0.6) * str);
                    double presDb = Math.min(7, (1.5 + Math.max(0, -pres) * 0.6) * str);
                    double airDb = Math.min(5, (1 + Math.max(0, -air) * 0.4) * str);
                    m = new Meter(ch, fs);
                    head = new app.sesdefteri.dsp.Clarity(ch, fs, mudDb, presDb, airDb, 0.25 * str);
                    head.to(new Stages.Compressor(ch, fs, an.speechMeanDb + 4, 1.6 + 0.6 * str)).to(new Limiter(ch, fs, -1.0, 1.5 * str)).to(m);
                    rep.put("mud", Math.round(-mudDb * 10) / 10.0);
                    rep.put("pres", Math.round(presDb * 10) / 10.0);
                    rep.put("air", Math.round(airDb * 10) / 10.0);
                    break;
                }
                case "voicefx": {
                    String pr = a.optString("preset", "radio");
                    rep.put("preset", pr);
                    m = new Meter(ch, fs);
                    if ("high".equals(pr) || "deep".equals(pr)) head = new TimePitch(ch, fs, 1, "high".equals(pr) ? 5 : -5, false);
                    else head = new app.sesdefteri.dsp.VoiceFx(ch, fs, pr);
                    head.to(new Limiter(ch, fs, -1.0, 0)).to(m);
                    slice = new double[] { 0, 1 };
                    break;
                }
                case "mix":
                    return mix(c, in, inf, out, a, p, cancel);
                default:
                    throw new IllegalArgumentException("op " + op);
            }
            m.to(new Studio.WavNode(out, fs));
            wav(in, inf, p, slice, cancel).run(head);
            Analyzer after = tees.get();
            if (after != null) { rep.put("after", Math.round(after.finish().noiseDb)); tees.remove(); }
            Stages.ClickRepair cr = clicks.get();
            if (cr != null) { rep.put("count", cr.repaired); clicks.remove(); }
            Stages.PlosiveTamer pt = plos.get();
            if (pt != null) { rep.put("seconds", Math.round(pt.active * 10.0 / fs) / 10.0); plos.remove(); }
            Stages.DeEsser d = dees.get();
            if (d != null) { rep.put("seconds", Math.round(d.active * 10.0 / fs) / 10.0); dees.remove(); }
            rep.remove("_after");
            // nothing to fix in this recording: say so instead of handing back an identical copy
            if ((cr != null && cr.repaired == 0) || (pt != null && pt.active < fs / 20) || (d != null && d.active < fs / 5)) {
                out.delete();
                rep.put("none", true);
                return same(a, rep);
            }
            return done(out, m, rep);
        } catch (Exception e) {
            out.delete();
            throw e;
        }
    }

    /* ---------- 1.9: shorter pauses, quieter breaths, repaired clipping ---------- */

    /** pauses longer than max become `keep` long (the middle is cut, the edges stay so speech keeps its rhythm) */
    static JSONObject pauses(File in, Info inf, File out, JSONObject a, AudioTools.Progress p, AtomicBoolean cancel) throws Exception {
        double max = Math.max(0.3, a.optDouble("max", 0.8)), keep = Math.min(max, Math.max(0.15, a.optDouble("keep", 0.45)));
        Speech.Scan sc = Speech.scan(in, inf, part(p, 0, 0.4), cancel);
        java.util.ArrayList<int[]> l = Speech.pauses(sc, max);
        JSONObject rep = new JSONObject();
        if (l.isEmpty()) { rep.put("none", true); return same(a, rep); }
        JSONArray ranges = new JSONArray(), cuts = new JSONArray();
        double pos = 0, outT = 0, saved = 0, dur = inf.frames / (double) inf.rate;
        for (int[] r : l) {
            double t0 = r[0] * Speech.FR + keep / 2, t1 = r[1] * Speech.FR - keep / 2;
            if (t1 - t0 < 0.05) continue;
            ranges.put(new JSONArray().put(pos * 1000).put(t0 * 1000));
            outT += t0 - pos;
            cuts.put(new JSONArray().put(Math.round(outT * 1000) / 1000.0).put(Math.round((t1 - t0) * 1000) / 1000.0));
            saved += t1 - t0;
            pos = t1;
        }
        ranges.put(new JSONArray().put(pos * 1000).put(dur * 1000 + 1000));
        Meter m = new Meter(inf.ch, inf.rate);
        Keep k = new Keep(ranges, inf.rate);
        k.to(m).to(new Studio.WavNode(out, inf.rate));
        wav(in, inf, part(p, 0.4, 1), new double[] { 0, 1 }, cancel).run(k);
        rep.put("count", cuts.length());
        rep.put("saved", Math.round(saved * 10) / 10.0);
        rep.put("cuts", cuts);
        return done(out, m, rep);
    }

    /** turns the found regions down by `db`, with short ramps */
    static final class GainRegions extends Node {
        final long[] a, b;
        final double g;
        final int ramp;
        long pos;
        int i;
        GainRegions(java.util.ArrayList<int[]> l, int hop, double db, int ramp) {
            a = new long[l.size()]; b = new long[l.size()];
            for (int k = 0; k < a.length; k++) { a[k] = (long) l.get(k)[0] * hop; b[k] = (long) l.get(k)[1] * hop; }
            g = Math.pow(10, db / 20); this.ramp = ramp;
        }
        @Override public void push(float[][] x, int n) throws Exception {
            for (int k = 0; k < n; k++, pos++) {
                while (i < a.length && pos >= b[i] + ramp) i++;
                if (i >= a.length || pos < a[i] - ramp) continue;
                double d = Math.min(Math.min(pos - (a[i] - ramp), (b[i] + ramp) - pos), ramp) / (double) ramp;   // 0 at the ramp ends, 1 inside
                float gg = (float) (1 - (1 - g) * Math.max(0, Math.min(1, d)));
                for (float[] c : x) c[k] *= gg;
            }
            next.push(x, n);
        }
    }

    static JSONObject breaths(File in, Info inf, File out, double str, AudioTools.Progress p, AtomicBoolean cancel) throws Exception {
        Speech.Scan sc = Speech.scan(in, inf, part(p, 0, 0.4), cancel);
        java.util.ArrayList<int[]> l = Speech.breaths(sc);
        JSONObject rep = new JSONObject();
        if (l.isEmpty()) { rep.put("none", true); return same(null, rep); }
        Meter m = new Meter(inf.ch, inf.rate);
        GainRegions gr = new GainRegions(l, sc.hop, -(4 + 6 * str), (int) (inf.rate * 0.015));
        gr.to(m).to(new Studio.WavNode(out, inf.rate));
        wav(in, inf, part(p, 0.4, 1), new double[] { 0, 1 }, cancel).run(gr);
        rep.put("count", l.size());
        return done(out, m, rep);
    }

    /**
     * Clipped runs (|x| ≥ 0.985, up to 3 ms) are redrawn with a cubic through two good samples on each side,
     * then the whole file is scaled so the rebuilt peaks fit.
     */
    static JSONObject declip(File in, Info inf, File out, AudioTools.Progress p, AtomicBoolean cancel) throws Exception {
        final int ch = inf.ch, B = 1 << 16, M = 256;
        final float CL = 0.985f;
        final int maxRun = Math.max(8, inf.rate * 3 / 1000);
        RandomAccessFile r = new RandomAccessFile(in, "r");
        int[] runs = new int[1];
        double peak = 0;
        Meter m = null;
        try {
            for (int pass = 0; pass < 2; pass++) {
                double gain = pass == 0 ? 1 : Math.min(1, 0.97 / Math.max(1e-6, peak));
                Node sink = null;
                if (pass == 1) { m = new Meter(ch, inf.rate); m.to(new Studio.WavNode(out, inf.rate)); sink = m; }
                float[][] x = new float[ch][B + 2 * M], o = new float[ch][B];
                byte[] buf = new byte[(B + 2 * M) * ch * 2];
                for (long s = 0; s < inf.frames; s += B) {
                    if (cancel != null && cancel.get()) throw new InterruptedException("cancel");
                    long A = Math.max(0, s - M), E = Math.min(inf.frames, s + B + M);
                    int n = (int) (E - A), off = (int) (s - A), keep = (int) Math.min(B, inf.frames - s);
                    r.seek(44 + A * ch * 2);
                    r.readFully(buf, 0, n * ch * 2);
                    for (int i = 0, q = 0; i < n; i++) for (int c = 0; c < ch; c++, q += 2) x[c][i] = (short) ((buf[q] & 0xff) | (buf[q + 1] << 8)) / 32768f;
                    for (int c = 0; c < ch; c++) {
                        float[] v = x[c];
                        for (int i = 2; i < n - 2; i++) {
                            if (Math.abs(v[i]) < CL) continue;
                            int e = i;
                            while (e + 1 < n - 2 && Math.abs(v[e + 1]) >= CL && Math.signum(v[e + 1]) == Math.signum(v[i])) e++;
                            int len = e - i + 1;
                            if (len >= 2 && len <= maxRun && i - 2 >= 0 && e + 2 < n) {
                                // cubic through (−2, −1) on the left and (len, len+1) on the right, in local coordinates
                                double[] tx = { -2, -1, len, len + 1 }, ty = { v[i - 2], v[i - 1], v[e + 1], v[e + 2] };
                                for (int k = 0; k < len; k++) {
                                    double t = k, y = 0;
                                    for (int a2 = 0; a2 < 4; a2++) {
                                        double w = 1;
                                        for (int b2 = 0; b2 < 4; b2++) if (b2 != a2) w *= (t - tx[b2]) / (tx[a2] - tx[b2]);
                                        y += w * ty[a2];
                                    }
                                    if (Math.abs(y) > Math.abs(v[i + k]) && Math.signum(y) == Math.signum(v[i + k])) v[i + k] = (float) Math.max(-4, Math.min(4, y));
                                }
                                if (pass == 0 && i >= off && i < off + keep) runs[0]++;
                            }
                            i = e;
                        }
                        for (int i = off; i < off + keep; i++) { double a3 = Math.abs(v[i]); if (a3 > peak && pass == 0) peak = a3; }
                    }
                    if (pass == 1) {
                        for (int c = 0; c < ch; c++) for (int i = 0; i < keep; i++) o[c][i] = (float) (x[c][off + i] * gain);
                        sink.push(o, keep);
                    }
                    p.on(0.5 * pass + 0.5 * (s + keep) / inf.frames);
                }
                if (pass == 0 && runs[0] == 0) { JSONObject rep = new JSONObject(); rep.put("none", true); return same(null, rep); }
                if (pass == 1) sink.end();
            }
        } finally {
            r.close();
        }
        JSONObject rep = new JSONObject();
        rep.put("count", runs[0]);
        rep.put("gain", Math.round(20 * Math.log10(Math.min(1, 0.97 / Math.max(1e-6, peak))) * 10) / 10.0);
        return done(out, m, rep);
    }

    /** hesitation sounds in any audio: [[start, end], …] in seconds */
    static JSONObject fillers(Context c, JSONObject a, AudioTools.Progress p, AtomicBoolean cancel) throws Exception {
        File w = new File(c.getCacheDir(), "fl" + System.nanoTime() + ".wav");
        try {
            decodeTo(c, a.getJSONObject("src"), 1, 16000, p, new double[] { 0, 0.7 }, cancel, w);
            Info inf = info(w);
            float[] x = new float[(int) inf.frames];
            byte[] b = new byte[x.length * 2];
            RandomAccessFile r = new RandomAccessFile(w, "r");
            try { r.seek(44); r.readFully(b); } finally { r.close(); }
            for (int i = 0; i < x.length; i++) x[i] = (short) ((b[2 * i] & 0xff) | (b[2 * i + 1] << 8)) / 32768f;
            JSONArray out = new JSONArray();
            for (double[] q : Speech.fillers(x, 16000)) out.put(new JSONArray().put(Math.round(q[0] * 100) / 100.0).put(Math.round(q[1] * 100) / 100.0));
            p.on(1);
            return new JSONObject().put("ranges", out);
        } finally {
            w.delete();
        }
    }

    /** a new recording made of the kept ranges of another (text-based editing) */
    static JSONObject keepRender(Context c, JSONObject a, AudioTools.Progress p, AtomicBoolean cancel) throws Exception {
        JSONObject item = AudioTools.render(c, a.getJSONObject("src"), a.getJSONArray("keep"), Studio.safe(a.optString("name", "Ses")) + ".m4a", p, cancel);
        return new JSONObject().put("item", item);
    }

    /** integrated loudness of any audio (for level matching in the player) */
    static JSONObject loudness(Context c, JSONObject a, AudioTools.Progress p, AtomicBoolean cancel) throws Exception {
        JSONObject src = a.getJSONObject("src");
        Studio.Fmt f = Studio.probe(c, src);
        Loudness l = new Loudness(f.ch, f.rate);
        l.to(new Sink());
        Studio.source(c, src, f, p, new double[][] { { 0, 1 } }, cancel).run(l);
        double v = l.integrated();
        JSONObject o = new JSONObject();
        o.put("lufs", Double.isInfinite(v) ? -70 : Math.round(v * 10) / 10.0);
        return o;
    }

    /* ---------- 2.0: ringtone, chapters ---------- */

    static JSONObject ringtone(Context c, JSONObject a, AudioTools.Progress p, AtomicBoolean cancel) throws Exception {
        long st = Math.max(0, a.getLong("startMs")), len = Math.max(1000, Math.min(40000, a.getLong("lenMs")));
        JSONArray keep = new JSONArray().put(new JSONArray().put(st).put(st + len));
        String name = Studio.safe(a.optString("name", "Zil")) + ".m4a";
        JSONObject item = AudioTools.render(c, a.getJSONObject("src"), keep, MediaLib.createRingtone(c, name, "audio/mp4"), Math.min(1.0, len / 8000.0), true, p, cancel);
        JSONObject r = new JSONObject();
        r.put("item", item);
        return r;
    }

    /** chapter marks at the longest pauses, roughly every 1.5–5 minutes */
    static JSONObject chapters(Context c, JSONObject a, AudioTools.Progress p, AtomicBoolean cancel) throws Exception {
        File w = new File(c.getCacheDir(), "ch" + System.nanoTime() + ".wav");
        try {
            decodeTo(c, a.getJSONObject("src"), 1, 16000, p, new double[] { 0, 0.8 }, cancel, w);
            Info inf = info(w);
            Speech.Scan sc = Speech.scan(w, inf, part(p, 0.8, 1), cancel);
            java.util.ArrayList<int[]> ps = Speech.pauses(sc, 0.8);
            double dur = inf.frames / (double) inf.rate, last = 0, minGap = a.optDouble("min", 90), maxGap = a.optDouble("max", 300);
            JSONArray times = new JSONArray();
            while (true) {
                int[] best = null;
                for (int[] r : ps) {
                    double mid = (r[0] + r[1]) * Speech.FR / 2;
                    if (mid < last + minGap || mid > last + maxGap || mid > dur - 30) continue;
                    if (best == null || r[1] - r[0] > best[1] - best[0]) best = r;
                }
                if (best == null) {
                    // no pause in the window: move on so a long monologue still gets later chapters
                    if (last + maxGap >= dur - 30) break;
                    last += maxGap;
                    continue;
                }
                last = (best[0] + best[1]) * Speech.FR / 2;
                times.put(Math.round(last * 10) / 10.0);
            }
            JSONObject r = new JSONObject();
            r.put("times", times);
            return r;
        } finally {
            w.delete();
        }
    }

    /** noise removal with the voice-separation model: keeps the voice, drops everything else */
    /** DPDFNet speech denoiser (48 kHz mono model; other rates are converted there and back) */
    static JSONObject aiDenoise(Context c, File in, Info inf, File out, double str, AudioTools.Progress p, AtomicBoolean cancel) throws Exception {
        Analyzer.Analysis b = analyze(in, inf, part(p, 0, 0.25), cancel);
        long h = Denoiser.open(c);
        try {
            // Light keeps ~25 % of the original (-12 dB), Balanced -23 dB, Strong -34 dB
            double lim = 12 + 22 * Math.max(0, Math.min(1, (str - 0.6) / 0.8));
            int ch = inf.ch, fs = inf.rate;
            Analyzer after = new Analyzer(ch, fs);
            Meter m = new Meter(ch, fs);
            Node head = new Denoiser.Stage(h, ch, Math.pow(10, -lim / 20)), tail = head;
            if (fs != Denoiser.RATE) {
                Resampler up = new Resampler(ch, (double) fs, (double) Denoiser.RATE), down = new Resampler(ch, (double) Denoiser.RATE, (double) fs);
                up.to(head); head.to(down); head = up; tail = down;
            }
            tail.to(new Tee(after)).to(m).to(new Studio.WavNode(out, fs));
            wav(in, inf, part(p, 0.25, 1), new double[] { 0, 1 }, cancel).run(head);
            double na = after.finish().noiseDb;
            JSONObject rep = new JSONObject();
            rep.put("before", Math.round(b.noiseDb));
            rep.put("after", Math.round(na));
            rep.put("reduction", Math.max(0, Math.round(b.noiseDb - na)));
            rep.put("ai", true);
            return done(out, m, rep);
        } catch (Exception e) {
            out.delete();
            throw e;
        } finally {
            Denoiser.destroy(h);
        }
    }

    static final class Gate extends Node {
        final int ch, LA, HOLD; final float closed, thr, eA, gA, gR;
        final float[][] dl; int dp, hold; long seen; float env, gain = 1; float[][] o = new float[1][0];
        Gate(int ch, int la, int holdN, float closed, float thr, float eA, float gA, float gR) {
            this.ch = ch; LA = la; HOLD = holdN; this.closed = closed; this.thr = thr; this.eA = eA; this.gA = gA; this.gR = gR;
            dl = new float[ch][la];
        }
        @Override public void push(float[][] b, int n) throws Exception {
            if (o[0].length < n) o = new float[ch][n];
            int k = 0;
            for (int i = 0; i < n; i++) {
                float pw = 0;
                for (int c = 0; c < ch; c++) pw += b[c][i] * b[c][i];
                env += (pw / ch - env) * eA;
                if (env > thr) hold = HOLD; else if (hold > 0) hold--;
                float tg = hold > 0 ? 1 : closed;
                gain += (tg - gain) * (tg > gain ? gA : gR);
                // the gain follows the newest audio, the output is LA samples older: the gate opens just before a word
                for (int c = 0; c < ch; c++) { float d = dl[c][dp]; dl[c][dp] = b[c][i]; o[c][k] = d * gain; }
                dp = (dp + 1) % LA;
                if (++seen > LA) k++;
            }
            if (k > 0) {
                next.push(o, k);
            }
        }
        @Override public void end() throws Exception {
            int left = (int) Math.min(LA, seen);
            if (left > 0) {
                if (o[0].length < left) o = new float[ch][left];
                int start = seen < LA ? 0 : dp;
                for (int i = 0; i < left; i++) for (int c = 0; c < ch; c++) o[c][i] = dl[c][(start + i) % LA] * gain;
                next.push(o, left);
            }
            next.end();
        }
    }

    /** noise removal with the voice separation model (Spleeter): keeps only the voice stem */
    static JSONObject sepDenoise(Context c, File in, Info inf, AudioTools.Progress p, AtomicBoolean cancel) throws Exception {
        if (!Separator.available()) throw new IllegalStateException("sep-unavailable");
        Analyzer.Analysis b = analyze(in, inf, part(p, 0, 0.1), cancel);
        File vm = Separator.model(c, "vocals.fp16.onnx"), am = Separator.model(c, "accompaniment.fp16.onnx");
        File[] o = Separator.run(in, inf, vm, am, part(p, 0.1, 0.9), cancel);
        o[1].delete();
        try {
            Info oi = info(o[0]);
            Analyzer after = new Analyzer(oi.ch, oi.rate);
            Meter m = new Meter(oi.ch, oi.rate);
            Node t = new Tee(after);
            t.to(m).to(new Sink());
            wav(o[0], oi, part(p, 0.9, 1), new double[] { 0, 1 }, cancel).run(t);
            double na = after.finish().noiseDb;
            JSONObject rep = new JSONObject();
            rep.put("before", Math.round(b.noiseDb));
            rep.put("after", Math.round(na));
            rep.put("reduction", Math.max(0, Math.round(b.noiseDb - na)));
            rep.put("ai", true);
            return done(o[0], m, rep);
        } catch (Exception e) {
            o[0].delete();
            throw e;
        }
    }

    /** noise gate: between words the remaining hiss is pulled down (5 ms look-ahead, 120 ms hold) */
    static JSONObject gate(File in, Info inf, File out, double str, AudioTools.Progress p, AtomicBoolean cancel) throws Exception {
        Speech.Scan sc = Speech.scan(in, inf, part(p, 0, 0.3), cancel);
        JSONObject rep = new JSONObject();
        double gap = sc.speech - sc.noise;
        if (gap < 6 || sc.noise < -80) { rep.put("none", true); return same(null, rep); }
        final double thrDb = sc.noise + Math.max(4, Math.min(12, gap * 0.3));
        final double range = Math.min(gap - 2, 8 + 10 * str);
        rep.put("range", Math.round(range));
        final int ch = inf.ch, fs = inf.rate, LA = Math.round(fs * 0.005f), HOLD = Math.round(fs * 0.12f);
        final float closed = (float) Math.pow(10, -range / 20), thr = (float) Math.pow(10, thrDb / 10);
        final float eA = (float) (1 - Math.exp(-1.0 / (0.01 * fs))), gA = (float) (1 - Math.exp(-1.0 / (0.003 * fs))), gR = (float) (1 - Math.exp(-1.0 / (0.15 * fs)));
        Node g = new Gate(ch, LA, HOLD, closed, thr, eA, gA, gR);
        Meter m = new Meter(ch, fs);
        g.to(m).to(new Studio.WavNode(out, fs));
        wav(in, inf, part(p, 0.3, 1), new double[] { 0, 1 }, cancel).run(g);
        return done(out, m, rep);
    }

    /** per-band correction towards the target tone (smoothed over neighbours, capped) */
    /** mean of measured − reference over the bands between lo and hi (Hz) */
    static double bandDiff(double[] m, double[] ref, double lo, double hi) {
        double s = 0; int n = 0;
        for (int i = 0; i < Enhancer.BANDS.length; i++) if (Enhancer.BANDS[i] >= lo && Enhancer.BANDS[i] <= hi) { s += m[i] - ref[i]; n++; }
        return n == 0 ? 0 : s / n;
    }

    static double[] eqGains(double[] measured, double[] target, double str) {
        double[] g = new double[Enhancer.BANDS.length];
        for (int i = 0; i < g.length; i++) {
            double s = 0, w = 0;
            for (int j = Math.max(0, i - 1); j <= Math.min(g.length - 1, i + 1); j++) { double ww = j == i ? 2 : 1; s += (target[j] - measured[j]) * ww; w += ww; }
            double v = 0.8 * str * s / w, max = Enhancer.BANDS[i] < 160 ? 0 : Enhancer.BANDS[i] > 8000 ? 6 : 8;
            g[i] = Math.max(-8, Math.min(max, v));
        }
        return g;
    }

    /* ---------- "Clean": diagnosis of a recording, then all chosen fixes in one go ---------- */

    static File cleanDir(Context c) {
        File d = new File(c.getCacheDir(), "clean");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static AudioTools.Progress part(final AudioTools.Progress p, final double a, final double b) {
        return new AudioTools.Progress() { @Override public void on(double f) { p.on(a + (b - a) * Math.max(0, Math.min(1, f))); } };
    }

    static JSONObject diagnose(Context c, JSONObject a, AudioTools.Progress p, AtomicBoolean cancel) throws Exception {
        File d = cleanDir(c);
        File[] old = d.listFiles();
        if (old != null) for (File x : old) x.delete();
        JSONObject src = a.getJSONObject("src");
        Studio.Fmt f = Studio.probe(c, src);
        File w = new File(d, "o" + System.nanoTime() + ".wav");
        decodeTo(c, src, Math.min(2, f.ch), Timeline.RATE, p, new double[] { 0, 0.4 }, cancel, w);
        Info inf = info(w);
        int ch = inf.ch, fs = inf.rate;
        // pass 1: levels, noise, hum, channels, tone, loudness
        Analyzer an0 = new Analyzer(ch, fs);
        Loudness lo = new Loudness(ch, fs);
        Node t1 = new Tee(an0);
        t1.to(lo).to(new Sink());
        wav(w, inf, p, new double[] { 0.4, 0.7 }, cancel).run(t1);
        Analyzer.Analysis an = an0.finish();
        // pass 2: count clicks, plosives and harsh "s" with the same detectors the fixes use
        Stages.ClickRepair cr = new Stages.ClickRepair(ch, fs, 10);
        Stages.Filters hp = new Stages.Filters(ch, fs, 75, null);
        Stages.PlosiveTamer pt = new Stages.PlosiveTamer(ch, fs, 1.8);
        Stages.DeEsser de = new Stages.DeEsser(ch, fs, Math.pow(10, (an.speechDb - 12) / 20), 9);
        cr.to(new Sink());
        hp.to(pt).to(new Sink());
        de.to(new Sink());
        Node t2 = new Tee(cr);
        t2.to(new Tee(hp)).to(de);
        wav(w, inf, p, new double[] { 0.7, 1 }, cancel).run(t2);

        JSONObject o = new JSONObject();
        o.put("path", w.getAbsolutePath());
        o.put("dur", inf.frames * 1000.0 / fs);
        o.put("ch", ch);
        double lufs = lo.integrated();
        o.put("lufs", Double.isInfinite(lufs) ? -99 : Math.round(lufs * 10) / 10.0);
        o.put("lra", Math.round(lo.range() * 10) / 10.0);
        o.put("peak", Math.round(20 * Math.log10(Math.max(1e-9, an.peak)) * 10) / 10.0);
        o.put("clipped", an.clipped);
        o.put("snr", Math.round(an.snr));
        o.put("noise", Math.round(an.noiseDb));
        double[] hr = an.humRatioDb;
        double h50 = Math.max(hr[0], hr[1]), h60 = Math.max(hr[2], hr[3]);
        o.put("hum", Math.round(Math.max(h50, h60)));
        o.put("humHz", h60 > h50 ? 60 : 50);
        o.put("clicks", cr.repaired);
        o.put("plosive", Math.round(pt.active * 10.0 / fs) / 10.0);
        o.put("sib", Math.round(de.active * 10.0 / fs) / 10.0);
        o.put("channel", ch > 1 ? (an.channelMode == Stages.ChannelMap.KEEP ? "" : an.channelReason) : "");
        o.put("head", Math.round(an.activeStart * 10.0 / fs) / 10.0);
        o.put("tail", Math.round((an.samples - an.activeEnd) * 10.0 / fs) / 10.0);
        double[] g = eqGains(Enhancer.bandsOf(an.ltasPower, fs, an.fftSize), Enhancer.referenceBands(), 1);
        double mean = 0;
        for (double x : g) mean += Math.abs(x) / g.length;
        o.put("eq", Math.round(mean * 10) / 10.0);
        o.put("speech", an.speechFrames > 0);
        o.put("noiseP", Studio.json(an.noisePower));
        // long pauses and breaths (frame scan of the same file)
        Speech.Scan sc = Speech.scan(w, inf, null, cancel);
        double pmax = a.optDouble("pmax", 1.0);
        java.util.ArrayList<int[]> ps = Speech.pauses(sc, pmax);
        double extra = 0;
        for (int[] r : ps) extra += (r[1] - r[0]) * Speech.FR - a.optDouble("pkeep", 0.45);
        o.put("pauses", ps.size());
        o.put("pauseSec", Math.round(extra * 10) / 10.0);
        o.put("breaths", Speech.breaths(sc).size());
        return o;
    }

    /** runs the chosen fixes one after another on the diagnosed WAV; the original recording is never touched */
    static JSONObject clean(Context c, JSONObject a, AudioTools.Progress p, AtomicBoolean cancel) throws Exception {
        File in = new File(a.getString("path")), cur = in;
        JSONArray fixes = a.getJSONArray("fixes");
        JSONObject reports = new JSONObject(), last = null;
        double trimStart = 0;
        int n = fixes.length();
        try {
            for (int i = 0; i < n; i++) {
                String fx = fixes.getString(i);
                JSONObject o = new JSONObject();
                o.put("path", cur.getAbsolutePath());
                o.put("op", "denoise".equals(fx) && a.optBoolean("ai") ? "aidenoise" : fx);
                o.put("strength", a.optDouble("strength", 1));
                if ("loud".equals(fx)) o.put("target", a.optString("target", "youtube"));
                if ("pauses".equals(fx)) { o.put("max", a.optDouble("pmax", 0.8)); o.put("keep", a.optDouble("pkeep", 0.45)); }
                if ("eq".equals(fx) && a.has("learned")) o.put("learned", a.get("learned"));
                if ("denoise".equals(fx) && a.has("profile")) o.put("profile", a.get("profile"));
                JSONObject r = op(c, o, part(p, (double) i / n, (double) (i + 1) / n), cancel);
                if (r.optJSONObject("report") != null) reports.put(fx, r.getJSONObject("report"));
                if (r.optBoolean("same")) continue;
                if (cur != in) cur.delete();
                cur = new File(r.getString("path"));
                last = r;
                if ("trim".equals(fx)) trimStart = r.getJSONObject("report").optDouble("start", 0);
            }
        } catch (Exception e) {
            if (cur != in) cur.delete();
            throw e;
        }
        JSONObject res = last != null ? last : new JSONObject().put("path", in.getAbsolutePath()).put("dur", info(in).frames * 1000.0 / info(in).rate);
        res.put("reports", reports);
        res.put("trimStart", trimStart);
        return res;
    }

    // per-job holders for the stage objects whose counters go into the report
    private static final ThreadLocal<Analyzer> tees = new ThreadLocal<>();
    private static final ThreadLocal<Stages.ClickRepair> clicks = new ThreadLocal<>();
    private static final ThreadLocal<Stages.PlosiveTamer> plos = new ThreadLocal<>();
    private static final ThreadLocal<Stages.DeEsser> dees = new ThreadLocal<>();

    static JSONObject same(JSONObject a, JSONObject rep) throws Exception {
        JSONObject o = new JSONObject();
        o.put("same", true);
        o.put("report", rep);
        return o;
    }

    /** copies audio to an analyzer on the side */
    static final class Tee extends Node {
        final Node side;
        Tee(Node side) { this.side = side; }
        @Override public void push(float[][] b, int n) throws Exception { side.push(b, n); next.push(b, n); }
        @Override public void end() throws Exception { side.end(); next.end(); }
    }

    /** keeps only the listed [ms, ms] ranges, with 8 ms fades at every join */
    static final class Keep extends Node {
        final long[] ks, ke;
        final int fade;
        long pos;
        int seg;
        float[][] out = new float[1][0];

        Keep(JSONArray keep, int fs) throws Exception {
            int n = keep.length();
            ks = new long[n]; ke = new long[n];
            for (int i = 0; i < n; i++) {
                JSONArray r = keep.getJSONArray(i);
                ks[i] = Math.round(r.getDouble(0) * fs / 1000.0);
                ke[i] = Math.round(r.getDouble(1) * fs / 1000.0);
            }
            fade = Math.max(1, (int) (fs * 0.008));
        }

        @Override public void push(float[][] b, int n) throws Exception {
            int ch = b.length;
            if (out.length != ch || out[0].length < n) out = new float[ch][n];
            int k = 0;
            for (int i = 0; i < n; i++, pos++) {
                while (seg < ks.length && pos >= ke[seg]) seg++;
                if (seg >= ks.length) break;
                if (pos < ks[seg]) continue;
                double g = 1;
                long a = pos - ks[seg], z = ke[seg] - pos;
                if (ks[seg] > 0 && a < fade) g = (double) a / fade;
                if (seg < ks.length - 1 && z < fade) g = Math.min(g, (double) z / fade);
                for (int c = 0; c < ch; c++) out[c][k] = (float) (b[c][i] * g);
                k++;
            }
            if (k > 0) next.push(out, k);
        }
    }

    /** gain that lands on the target loudness after the limiter (two quick measuring runs) */
    static double loudGain(File in, Info inf, Enhancer.Target t, AudioTools.Progress p, AtomicBoolean cancel) throws Exception {
        Loudness l0 = new Loudness(inf.ch, inf.rate);
        l0.to(new Sink());
        wav(in, inf, p, new double[] { 0, 0.2 }, cancel).run(l0);
        double lufs = l0.integrated();
        if (Double.isInfinite(lufs)) return 0;
        double gain = Math.max(-30, Math.min(30, t.lufs - lufs));
        for (int it = 0; it < 2; it++) {
            Limiter lim = new Limiter(inf.ch, inf.rate, t.ceilingDb, gain);
            Loudness pr = new Loudness(inf.ch, inf.rate);
            lim.to(pr).to(new Sink());
            wav(in, inf, p, new double[] { 0.2 + 0.2 * it, 0.4 + 0.2 * it }, cancel).run(lim);
            double got = pr.integrated();
            if (Double.isInfinite(got) || Math.abs(t.lufs - got) < 0.2) break;
            gain = Math.max(-30, Math.min(36, gain + (t.lufs - got) * 1.1));
        }
        return gain;
    }

    static final class Sink extends Node {
        @Override public void push(float[][] b, int n) { }
        @Override public void end() { }
    }

    /** decodes another file into a WAV with the workspace's rate and channel count */
    static File decodeLike(Context c, JSONObject src, int ch, int rate, AudioTools.Progress p, double[] slice, AtomicBoolean cancel) throws Exception {
        return decodeTo(c, src, ch, rate, p, slice, cancel, newFile(c));
    }

    static File decodeTo(Context c, JSONObject src, final int ch, int rate, AudioTools.Progress p, double[] slice, AtomicBoolean cancel, File out) throws Exception {
        Studio.Fmt f = Studio.probe(c, src);
        Node tail = new Studio.WavNode(out, rate);
        Node head = tail;
        if (f.rate != rate) { Resampler r = new Resampler(ch, (double) f.rate, (double) rate); r.to(head); head = r; }
        if (f.ch != ch) {
            final int inCh = f.ch;
            final Node nx = head;
            head = new Node() {
                float[][] o = new float[ch][0];
                @Override public void push(float[][] b, int n) throws Exception {
                    if (o[0].length < n) o = new float[o.length][n];
                    for (int i = 0; i < n; i++) {
                        float s = 0;
                        for (int k = 0; k < inCh; k++) s += b[k][i];
                        for (int k = 0; k < o.length; k++) o[k][i] = o.length == 1 ? s / inCh : b[Math.min(k, inCh - 1)][i];
                    }
                    nx.push(o, n);
                }
                @Override public void end() throws Exception { nx.end(); }
            };
        }
        Studio.source(c, src, f, p, new double[][] { slice }, cancel).run(head);
        return out;
    }

    /* ---------- insert another recording at a position ---------- */

    static JSONObject insert(Context c, File in, Info inf, File out, JSONObject a, AudioTools.Progress p, AtomicBoolean cancel) throws Exception {
        File other = decodeLike(c, a.getJSONObject("other"), inf.ch, inf.rate, p, new double[] { 0, 0.5 }, cancel);
        try {
            Info oi = info(other);
            long at = Math.max(0, Math.min(inf.frames, Math.round(a.optDouble("posMs", inf.frames * 1000.0 / inf.rate) * inf.rate / 1000.0)));
            int fade = (int) (inf.rate * 0.01);
            Meter m = new Meter(inf.ch, inf.rate);
            m.to(new Studio.WavNode(out, inf.rate));
            // first part, the inserted audio, the rest; short fades so the joins don't click
            copy(in, inf, 0, at, 0, at < inf.frames ? fade : 0, m);
            copy(other, oi, 0, oi.frames, at > 0 ? fade : 0, at < inf.frames ? fade : 0, m);
            copy(in, inf, at, inf.frames, at > 0 ? fade : 0, 0, m);
            m.end();
            JSONObject rep = new JSONObject();
            rep.put("at", Math.round(at * 100.0 / inf.rate) / 100.0);
            rep.put("added", Math.round(oi.frames * 100.0 / oi.rate) / 100.0);
            return done(out, m, rep);
        } catch (Exception e) {
            out.delete();
            throw e;
        } finally {
            other.delete();
        }
    }

    /** pushes frames [from, to) of a workspace WAV, fading in/out over the given lengths */
    static void copy(File f, Info inf, long from, long to, int fadeIn, int fadeOut, Node sink) throws Exception {
        if (to <= from) return;
        RandomAccessFile r = new RandomAccessFile(f, "r");
        try {
            r.seek(44 + from * inf.ch * 2);
            final int BL = 4096;
            float[][] x = new float[inf.ch][BL];
            byte[] rb = new byte[BL * inf.ch * 2];
            long pos = from;
            while (pos < to) {
                int n = (int) Math.min(BL, to - pos);
                r.readFully(rb, 0, n * inf.ch * 2);
                int q = 0;
                for (int i = 0; i < n; i++) {
                    long k = pos + i;
                    double g = 1;
                    if (fadeIn > 0 && k - from < fadeIn) g = (double) (k - from) / fadeIn;
                    if (fadeOut > 0 && to - k <= fadeOut) g = Math.min(g, (double) (to - k - 1) / fadeOut);
                    for (int c = 0; c < inf.ch; c++, q += 2) x[c][i] = (float) ((short) ((rb[q] & 0xff) | (rb[q + 1] << 8)) / 32768f * g);
                }
                sink.push(x, n);
                pos += n;
            }
        } finally {
            r.close();
        }
    }

    /* ---------- background music with ducking ---------- */

    static JSONObject mix(Context c, File in, Info inf, File out, JSONObject a, final AudioTools.Progress p, AtomicBoolean cancel) throws Exception {
        JSONObject msrc = a.getJSONObject("music");
        Studio.Fmt fm = Studio.probe(c, msrc);
        int mch = Math.min(2, fm.ch);
        File mtemp = new File(dir(c), "m" + System.nanoTime() + ".pcm"), temp = new File(dir(c), "t" + System.nanoTime() + ".pcm");
        Pipeline.FileTemp mt = new Pipeline.FileTemp(mtemp, mch);
        try {
            Node head = mt;
            if (fm.rate != inf.rate) { Resampler r = new Resampler(mch, (double) fm.rate, (double) inf.rate); r.to(mt); head = r; }
            Studio.source(c, msrc, fm, p, new double[][] { { 0, 0.2 } }, cancel).run(head);
            int outCh = Math.max(inf.ch, mch);
            Meter m = new Meter(outCh, inf.rate);
            m.to(new Studio.WavNode(out, inf.rate));
            Pipeline.mix(wav(in, inf, p, new double[] { 0.2, 0.6 }, cancel), inf.ch, inf.rate, mt,
                a.optDouble("bedDb", -6), a.optDouble("duckDb", -14), a.optString("target", "youtube"), temp, m,
                new Pipeline.Progress() { @Override public void on(double x) { if (x >= 0.7) p.on(0.6 + 0.4 * (x - 0.7) / 0.3); } });
            JSONObject rep = new JSONObject();
            rep.put("bed", a.optDouble("bedDb", -6));
            rep.put("duck", a.optDouble("duckDb", -14));
            return done(out, m, rep);
        } catch (Exception e) {
            out.delete();
            throw e;
        } finally {
            mt.delete();
            temp.delete();
        }
    }

    /** export channel choice: "mono" / "stereo" make a converted copy (null = keep as is) */
    static File chans(Context c, File in, Info inf, String want) throws Exception {
        int to = "mono".equals(want) ? 1 : "stereo".equals(want) ? 2 : inf.ch;
        if (to == inf.ch) return null;
        File out = new File(dir(c), "x" + System.nanoTime() + ".wav");
        Studio.WavNode w = new Studio.WavNode(out, inf.rate);
        RandomAccessFile r = new RandomAccessFile(in, "r");
        try {
            r.seek(44);
            final int BL = 8192;
            byte[] b = new byte[BL * inf.ch * 2];
            float[][] o = new float[to][BL];
            for (long done = 0; done < inf.frames; ) {
                int n = (int) Math.min(BL, inf.frames - done);
                r.readFully(b, 0, n * inf.ch * 2);
                for (int i = 0, q = 0; i < n; i++) {
                    float v = 0;
                    for (int k = 0; k < inf.ch; k++, q += 2) v += (short) ((b[q] & 0xff) | (b[q + 1] << 8)) / 32768f;
                    v /= inf.ch;
                    for (int k = 0; k < to; k++) o[k][i] = v;
                }
                w.push(o, n);
                done += n;
            }
            w.end();
            return out;
        } catch (Exception e) {
            out.delete(); throw e;
        } finally {
            r.close();
        }
    }

    /* ---------- export: the current step as a file in the cache, for the share sheet ---------- */

    static JSONObject export(Context c, JSONObject a, AudioTools.Progress p, AtomicBoolean cancel) throws Exception {
        File src = new File(a.getString("path")), conv = chans(c, src, info(src), a.optString("chans", ""));
        try { return export0(c, a, conv != null ? conv : src, p, cancel); } finally { if (conv != null) conv.delete(); }
    }

    private static JSONObject export0(Context c, JSONObject a, File in, AudioTools.Progress p, AtomicBoolean cancel) throws Exception {
        Info inf = info(in);
        File d = new File(c.getCacheDir(), "export");
        d.mkdirs();
        File[] old = d.listFiles();
        if (old != null) for (File x : old) x.delete();
        String fmt = fmt(a);
        boolean wavFmt = "wav".equals(fmt);
        File out = new File(d, Studio.safe(a.optString("name", "Ses")) + "." + fmt);
        if ("mp3".equals(fmt)) {
            mp3(in, inf, out, a.optInt("kbps", 192), p, cancel, a);
        } else if (wavFmt) {
            java.io.FileInputStream is = new java.io.FileInputStream(in);
            java.io.FileOutputStream os = new java.io.FileOutputStream(out);
            try { byte[] b = new byte[1 << 16]; int n; while ((n = is.read(b)) > 0) os.write(b, 0, n); } finally { is.close(); os.close(); }
        } else {
            wav(in, inf, p, new double[] { 0, 1 }, cancel).run(new Studio.EncNode(out, inf.rate, inf.ch > 1 ? 192000 : 128000));
        }
        JSONObject res = new JSONObject();
        JSONObject src = new JSONObject();
        src.put("path", out.getAbsolutePath());
        res.put("src", src);
        res.put("name", out.getName());
        res.put("mime", wavFmt ? "audio/wav" : "mp3".equals(fmt) ? "audio/mpeg" : "audio/mp4");
        return res;
    }

    /** wav / m4a / mp3 (mp3 falls back to m4a where the encoder isn't shipped) */
    static String fmt(JSONObject a) {
        String f = a.optString("format", "m4a");
        if ("mp3".equals(f) && !Mp3.available()) return "m4a";
        return "wav".equals(f) || "mp3".equals(f) ? f : "m4a";
    }

    static void mp3(File in, Info inf, File out, int kbps, AudioTools.Progress p, AtomicBoolean cancel, JSONObject meta) throws Exception {
        long h = Mp3.init(inf.rate, inf.ch, kbps);
        if (h == 0) throw new IllegalStateException("mp3");
        java.io.OutputStream os = new java.io.BufferedOutputStream(new java.io.FileOutputStream(out), 1 << 16);
        if (meta != null) os.write(id3(meta.optString("title", ""), meta.optString("artist", ""), meta.optString("cover", "")));
        RandomAccessFile r = new RandomAccessFile(in, "r");
        try {
            r.seek(44);
            final int BL = 4096;
            byte[] b = new byte[BL * inf.ch * 2];
            short[] s = new short[BL * inf.ch];
            for (long done = 0; done < inf.frames; ) {
                if (cancel != null && cancel.get()) throw new InterruptedException("cancel");
                int n = (int) Math.min(BL, inf.frames - done), k = n * inf.ch;
                r.readFully(b, 0, k * 2);
                for (int i = 0; i < k; i++) s[i] = (short) ((b[2 * i] & 0xff) | (b[2 * i + 1] << 8));
                byte[] o = Mp3.encode(h, s, n, inf.ch);
                if (o != null) os.write(o);
                done += n;
                if (p != null && (done / BL) % 64 == 0) p.on((double) done / inf.frames);
            }
            os.write(Mp3.flush(h));
        } catch (Exception e) {
            os.close(); out.delete(); throw e;
        } finally {
            Mp3.close(h);
            r.close();
            try { os.close(); } catch (Exception ignored) { }
        }
    }

    /** ID3v2.3 tag: title, artist and a JPEG cover (UTF-16 text so Turkish letters survive) */
    static byte[] id3(String title, String artist, String coverB64) throws Exception {
        java.io.ByteArrayOutputStream f = new java.io.ByteArrayOutputStream();
        text(f, "TIT2", title);
        text(f, "TPE1", artist);
        if (coverB64 != null && !coverB64.isEmpty()) {
            byte[] img = android.util.Base64.decode(coverB64, android.util.Base64.DEFAULT);
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            b.write(0); b.write("image/jpeg".getBytes("ISO-8859-1")); b.write(0); b.write(3); b.write(0); b.write(img);
            frame(f, "APIC", b.toByteArray());
        }
        byte[] body = f.toByteArray();
        if (body.length == 0) return new byte[0];
        java.io.ByteArrayOutputStream t = new java.io.ByteArrayOutputStream();
        t.write("ID3".getBytes("ISO-8859-1")); t.write(3); t.write(0); t.write(0);
        int n = body.length;
        t.write((n >> 21) & 0x7f); t.write((n >> 14) & 0x7f); t.write((n >> 7) & 0x7f); t.write(n & 0x7f);
        t.write(body);
        return t.toByteArray();
    }

    private static void text(java.io.ByteArrayOutputStream f, String id, String v) throws Exception {
        if (v == null || v.isEmpty()) return;
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        b.write(1); b.write(0xff); b.write(0xfe); b.write(v.getBytes("UTF-16LE")); b.write(0); b.write(0);
        frame(f, id, b.toByteArray());
    }

    private static void frame(java.io.ByteArrayOutputStream f, String id, byte[] data) throws Exception {
        f.write(id.getBytes("ISO-8859-1"));
        int n = data.length;
        f.write((n >> 24) & 0xff); f.write((n >> 16) & 0xff); f.write((n >> 8) & 0xff); f.write(n & 0xff);
        f.write(0); f.write(0);
        f.write(data);
    }

    /* ---------- save the current step as a real file ---------- */

    static JSONObject save(Context c, JSONObject a, AudioTools.Progress p, AtomicBoolean cancel) throws Exception {
        File src = new File(a.getString("path")), conv = chans(c, src, info(src), a.optString("chans", ""));
        try { return save0(c, a, conv != null ? conv : src, p, cancel); } finally { if (conv != null) conv.delete(); }
    }

    private static JSONObject save0(Context c, JSONObject a, File in, AudioTools.Progress p, AtomicBoolean cancel) throws Exception {
        Info inf = info(in);
        String name = Studio.safe(a.optString("name", "Ses"));
        String fmt = fmt(a);
        boolean wavFmt = "wav".equals(fmt);
        JSONObject item;
        if ("mp3".equals(fmt)) {
            File out = new File(dir(c), "e" + System.nanoTime() + ".mp3");
            try {
                mp3(in, inf, out, a.optInt("kbps", 192), p, cancel, a);
                MediaLib.Target tg = MediaLib.create(c, name + ".mp3", "audio/mpeg");
                item = MediaLib.copyInTarget(c, tg, out);
                if (item == null) { MediaLib.discard(c, tg); throw new IllegalStateException("publish"); }
            } finally {
                out.delete();
            }
        } else if (wavFmt) {
            MediaLib.Target tg = MediaLib.create(c, name + ".wav", "audio/x-wav");
            item = MediaLib.copyInTarget(c, tg, in);
            if (item == null) { MediaLib.discard(c, tg); throw new IllegalStateException("publish"); }
        } else {
            File out = new File(dir(c), "e" + System.nanoTime() + ".m4a");
            try {
                Studio.EncNode enc = new Studio.EncNode(out, inf.rate, inf.ch > 1 ? 192000 : 128000);
                wav(in, inf, p, new double[] { 0, 0.95 }, cancel).run(enc);
                MediaLib.Target tg = MediaLib.create(c, name + ".m4a", "audio/mp4");
                item = MediaLib.copyInTarget(c, tg, out);
                if (item == null) { MediaLib.discard(c, tg); throw new IllegalStateException("publish"); }
            } finally {
                out.delete();
            }
        }
        if (item != null && (a.has("title") || a.has("artist"))) MediaLib.tags(c, item, a.optString("title", ""), a.optString("artist", ""));
        JSONObject res = new JSONObject();
        res.put("item", item);
        if (a.has("replace")) res.put("replaced", MediaLib.deleteOwn(c, a.getJSONObject("replace")));
        return res;
    }
}
