package app.sesdefteri;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Video Defteri: the phone's videos, thumbnails, proxies, and the export of a cut list with its sound into one MP4.
 * When every clip has the same format, starts on a key frame and nothing is drawn over it, the picture is copied as it
 * is; otherwise VideoEngine draws and encodes it again (H.264).
 */
final class VideoEdit {
    private VideoEdit() { }

    /* ---------- library ---------- */

    static JSONObject list(Context c, boolean perm) {
        JSONObject o = new JSONObject();
        JSONArray items = new JSONArray();
        try {
            o.put("perm", perm);
            o.put("items", items);
        } catch (Exception ignored) { }
        if (!perm) return o;
        Uri coll = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] cols = { MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.SIZE };
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(coll, cols, null, null, MediaStore.Video.Media.DATE_ADDED + " DESC");
            if (cur == null) return o;
            while (cur.moveToNext()) {
                long dur = cur.getLong(2);
                if (dur <= 0) continue;
                JSONObject it = new JSONObject();
                it.put("uri", Uri.withAppendedPath(coll, String.valueOf(cur.getLong(0))).toString());
                it.put("name", cur.getString(1));
                it.put("dur", dur);
                it.put("w", cur.getInt(3));
                it.put("h", cur.getInt(4));
                it.put("date", cur.getLong(5) * 1000);
                it.put("size", cur.getLong(6));
                items.put(it);
            }
        } catch (Exception ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        return o;
    }

    /** {name, dur (ms), w, h (as shown, rotation applied), audio} */
    static JSONObject info(Context c, String uri) throws Exception {
        Uri u = Uri.parse(uri);
        MediaMetadataRetriever m = new MediaMetadataRetriever();
        try {
            m.setDataSource(c, u);
            String d = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            int w = num(m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)), h = num(m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            int rot = num(m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            if (w <= 0) throw new IllegalStateException("novideo");
            JSONObject o = new JSONObject();
            o.put("uri", uri);
            o.put("name", name(c, u));
            o.put("dur", d == null ? 0 : Long.parseLong(d.trim()));
            o.put("w", rot == 90 || rot == 270 ? h : w);
            o.put("h", rot == 90 || rot == 270 ? w : h);
            o.put("audio", "yes".equals(m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)));
            return o;
        } finally {
            try { m.release(); } catch (Exception ignored) { }
        }
    }

    private static int num(String s) {
        try { return s == null ? 0 : Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private static String name(Context c, Uri u) {
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(u, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null);
            if (cur != null && cur.moveToFirst()) {
                String n = cur.getString(0);
                if (n != null) { int dot = n.lastIndexOf('.'); return dot > 0 ? n.substring(0, dot) : n; }
            }
        } catch (Exception ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        return "Video";
    }

    /** a small JPEG of the frame at ms (kept in the cache folder) */
    static byte[] thumb(Context c, String uri, long ms) {
        File dir = new File(c.getCacheDir(), "thumbs");
        File f = new File(dir, Integer.toHexString(uri.hashCode()) + "-" + Long.toHexString(uri.length()) + "-" + (ms / 100) + ".jpg");
        if (f.exists()) {
            try {
                FileInputStream in = new FileInputStream(f);
                try {
                    byte[] b = new byte[(int) f.length()];
                    int n = 0;
                    while (n < b.length) { int k = in.read(b, n, b.length - n); if (k < 0) break; n += k; }
                    return b;
                } finally {
                    in.close();
                }
            } catch (Exception ignored) { }
        }
        MediaMetadataRetriever m = new MediaMetadataRetriever();
        try {
            m.setDataSource(c, Uri.parse(uri));
            Bitmap b = Build.VERSION.SDK_INT >= 27
                ? m.getScaledFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 320)
                : m.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (b == null) return null;
            if (b.getWidth() > 320 || b.getHeight() > 320) {
                float s = 320f / Math.max(b.getWidth(), b.getHeight());
                Bitmap x = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * s)), Math.max(1, Math.round(b.getHeight() * s)), true);
                b.recycle();
                b = x;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, 72, out);
            b.recycle();
            byte[] jpg = out.toByteArray();
            try {
                if (!dir.exists()) dir.mkdirs();
                FileOutputStream fo = new FileOutputStream(f);
                fo.write(jpg);
                fo.close();
            } catch (Exception ignored) { }
            return jpg;
        } catch (Exception e) {
            return null;
        } finally {
            try { m.release(); } catch (Exception ignored) { }
        }
    }

    /* ---------- the sound of the cut list ---------- */

    /**
     * a.audio: a timeline mix (tlMix) to use as the sound; without it the clips' own sound one after the other.
     * Returns a 48 kHz WAV, or null when there is no sound at all.
     */
    static File sound(Context c, JSONObject a, final AudioTools.Progress p, final double lo, final double hi, AtomicBoolean cancel) throws Exception {
        JSONArray mix = a.optJSONArray("audio");
        if (mix != null && mix.length() == 0) return null;
        ArrayList<File> tmp = new ArrayList<>();
        try {
            if (mix == null) {
                mix = new JSONArray();
                JSONArray clips = a.getJSONArray("clips");
                HashMap<String, File> done = new HashMap<>();
                double at = 0;
                for (int i = 0; i < clips.length(); i++) {
                    JSONObject k = clips.getJSONObject(i);
                    double in = k.getDouble("in"), len = k.getDouble("out") - in;
                    String uri = k.getString("uri");
                    File w = done.get(uri);
                    if (w == null && !done.containsKey(uri)) {
                        final double f0 = lo + (hi - lo) * 0.7 * i / clips.length(), f1 = lo + (hi - lo) * 0.7 * (i + 1) / clips.length();
                        JSONObject src = new JSONObject().put("uri", uri);
                        try {
                            Studio.Fmt f = Studio.probe(c, src);
                            w = new File(c.getCacheDir(), "vsnd-" + System.nanoTime() + ".wav");
                            tmp.add(w);
                            Workspace.decodeTo(c, src, Math.min(2, f.ch), Timeline.RATE, new AudioTools.Progress() {
                                @Override public void on(double x) { if (p != null) p.on(f0 + (f1 - f0) * x); }
                            }, new double[] { 0, 1 }, cancel, w);
                        } catch (InterruptedException e) {
                            throw e;
                        } catch (Exception e) {
                            if (cancel != null && cancel.get()) throw new InterruptedException("cancel");
                            w = null;   // no sound in this video: silence for its length
                        }
                        done.put(uri, w);
                    }
                    if (w != null) mix.put(new JSONObject().put("path", w.getAbsolutePath()).put("at", at).put("in", in).put("len", len).put("g", 1));
                    at += len;
                }
                if (mix.length() == 0) return null;
            }
            final double m0 = a.has("audio") ? lo : lo + (hi - lo) * 0.7;
            JSONObject r = Timeline.render(c, new JSONObject().put("clips", mix), new AudioTools.Progress() {
                @Override public void on(double x) { if (p != null) p.on(m0 + (hi - m0) * x); }
            }, cancel);
            return new File(r.getString("path"));
        } finally {
            for (File f : tmp) f.delete();
        }
    }

    /** job vSound: the sound as a WAV, for speech-to-text (subtitles) */
    static JSONObject soundJob(Context c, JSONObject a, AudioTools.Progress p, AtomicBoolean cancel) throws Exception {
        File w = sound(c, a, p, 0, 1, cancel);
        if (w == null) throw new IllegalStateException("nosound");
        File to = new File(c.getCacheDir(), "vsound.wav");
        to.delete();
        if (!w.renameTo(to)) to = w;
        return new JSONObject().put("path", to.getAbsolutePath()).put("dur", Workspace.info(to).frames * 1000.0 / Timeline.RATE);
    }

    /* ---------- export ---------- */

    static final class Seg {
        String uri;
        long in, out;            // µs in the source
        MediaFormat fmt;
        String mime;
        int track, w, h, rot;
    }

    /**
     * a: {clips: [{id, uri, at, in, out, tx (s)}], audio?: timeline mix, q: 0 (as it is) / 1080 / 720, subs?: [[t0, t1, text]]
     * burned in, name}
     */
    static JSONObject render(Context c, JSONObject a, AudioTools.Progress p, AtomicBoolean cancel) throws Exception {
        JSONArray clips = a.getJSONArray("clips");
        if (clips.length() == 0) throw new IllegalStateException("empty");
        Seg[] segs = new Seg[clips.length()];
        long total = 0;
        boolean fades = false;
        JSONArray comp = new JSONArray();
        for (int i = 0; i < segs.length; i++) {
            JSONObject k = clips.getJSONObject(i);
            Seg s = segs[i] = open(c, k.getString("uri"));
            s.in = Math.max(0, Math.round(k.getDouble("in") * 1e6));
            s.out = Math.round(k.getDouble("out") * 1e6);
            if (s.out <= s.in) throw new IllegalStateException("empty");
            double at = k.optDouble("at", total / 1e6), tx = k.optDouble("tx", 0);
            if (tx > 0) fades = true;
            total = Math.max(total, Math.round(at * 1e6) + s.out - s.in);
            comp.put(new JSONObject().put("id", "c" + i).put("src", s.uri).put("at", at).put("in", s.in / 1e6).put("out", s.out / 1e6).put("tx", tx));
        }
        int q = a.optInt("q", 0);
        JSONArray subs = a.optJSONArray("subs");
        if (subs != null && subs.length() == 0) subs = null;
        boolean copy = q == 0 && subs == null && !fades && copyable(c, segs);

        File cache = c.getCacheDir();
        long id = System.nanoTime();
        File m4a = new File(cache, "vexp-" + id + ".m4a"), pic = new File(cache, "vexp-" + id + "-v.mp4"), mp4 = new File(cache, "vexp-" + id + ".mp4");
        File wav = null;
        try {
            wav = sound(c, a, p, 0, 0.15, cancel);
            if (wav != null) VideoMux.aac(wav, total, m4a, p, 0.15, 0.22, cancel);
            if (copy) {
                copyPicture(c, segs, pic, total, p, 0.22, 0.9, cancel);
            } else {
                // the frame: the first clip as shown, short side 1080 / 720 (or as it is, at most 1920 on the long side)
                Seg f = segs[0];
                boolean turn = f.rot == 90 || f.rot == 270;
                double dw = turn ? f.h : f.w, dh = turn ? f.w : f.h;
                double sc = q > 0 ? q / Math.min(dw, dh) : Math.min(1, 1920 / Math.max(dw, dh));
                VideoEngine.encode(c, comp, even(dw * sc), even(dh * sc), 30, 1f, subs, pic, p, 0.22, 0.95, cancel);
            }
            MediaExtractor vx = new MediaExtractor();
            try {
                vx.setDataSource(pic.getAbsolutePath());
                vx.selectTrack(0);
                VideoMux.join(vx, vx.getTrackFormat(0), total, wav != null ? m4a : null, copy ? segs[0].rot : 0, mp4, p, 0.95, 1, cancel);
            } finally {
                vx.release();
            }
            return VideoMux.publish(c, mp4, a.optString("name", "Video") + ".mp4");
        } finally {
            if (wav != null) wav.delete();
            m4a.delete();
            pic.delete();
            mp4.delete();
        }
    }

    private static int even(double v) { return Math.max(2, (int) Math.round(v / 2) * 2); }

    /* ---------- proxies: a small copy for smooth preview and quick seeking ---------- */

    /** job vProxy {uri}: 540p H.264 with a key frame every half second + the sound as a 48 kHz WAV (cached by source) */
    static JSONObject proxy(Context c, JSONObject a, final AudioTools.Progress p, AtomicBoolean cancel) throws Exception {
        String uri = a.getString("uri");
        File dir = new File(c.getFilesDir(), "vproxy");
        if (!dir.exists()) dir.mkdirs();
        String key = Integer.toHexString(uri.hashCode()) + "-" + Integer.toHexString(uri.length());
        File v = new File(dir, key + ".mp4"), w = new File(dir, key + ".wav");
        JSONObject o = new JSONObject();
        if (!v.exists()) {
            Seg s = open(c, uri);
            long dur = AudioTools.durationMs(c, Uri.parse(uri));
            if (dur <= 0) throw new IllegalStateException("novideo");
            boolean turn = s.rot == 90 || s.rot == 270;
            double dw = turn ? s.h : s.w, dh = turn ? s.w : s.h, sc = Math.min(1, 540 / Math.min(dw, dh));
            File tmp = new File(dir, key + ".tmp");
            try {
                JSONArray one = new JSONArray().put(new JSONObject().put("id", "p").put("src", uri).put("at", 0).put("in", 0).put("out", dur / 1000.0));
                VideoEngine.encode(c, one, even(dw * sc), even(dh * sc), 30, 0.5f, null, tmp, p, 0, 0.75, cancel);
                if (!tmp.renameTo(v)) throw new IllegalStateException("proxy");
            } finally {
                tmp.delete();
            }
        }
        if (!w.exists()) {
            File tmp = new File(dir, key + ".wtmp");
            try {
                Studio.Fmt f = Studio.probe(c, new JSONObject().put("uri", uri));
                Workspace.decodeTo(c, new JSONObject().put("uri", uri), Math.min(2, f.ch), Timeline.RATE, new AudioTools.Progress() {
                    @Override public void on(double x) { if (p != null) p.on(0.75 + 0.25 * x); }
                }, new double[] { 0, 1 }, cancel, tmp);
                if (!tmp.renameTo(w)) throw new IllegalStateException("proxy");
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                if (cancel != null && cancel.get()) throw new InterruptedException("cancel");
                // no sound in this video
            } finally {
                tmp.delete();
            }
        }
        o.put("v", v.getAbsolutePath());
        MediaExtractor x = new MediaExtractor();
        try {
            x.setDataSource(v.getAbsolutePath());
            MediaFormat f = x.getTrackFormat(0);
            o.put("w", f.getInteger(MediaFormat.KEY_WIDTH));
            o.put("h", f.getInteger(MediaFormat.KEY_HEIGHT));
        } finally {
            x.release();
        }
        if (w.exists()) {
            Workspace.Info i = Workspace.info(w);
            o.put("a", w.getAbsolutePath());
            o.put("ach", i.ch);
            o.put("adur", i.frames / (double) i.rate);
        }
        return o;
    }

    /** drops proxies whose source is no longer in any project */
    static void proxyClean(Context c, JSONArray keep) {
        File[] fs = new File(c.getFilesDir(), "vproxy").listFiles();
        if (fs == null) return;
        java.util.HashSet<String> k = new java.util.HashSet<>();
        for (int i = 0; i < keep.length(); i++) { String u = keep.optString(i); k.add(Integer.toHexString(u.hashCode()) + "-" + Integer.toHexString(u.length())); }
        for (File f : fs) { String n = f.getName(); int dot = n.indexOf('.'); if (!k.contains(dot > 0 ? n.substring(0, dot) : n)) f.delete(); }
    }

    private static Seg open(Context c, String uri) throws Exception {
        Seg s = new Seg();
        s.uri = uri;
        MediaExtractor x = new MediaExtractor();
        try {
            x.setDataSource(c, Uri.parse(uri), null);
            s.track = -1;
            for (int i = 0; i < x.getTrackCount(); i++) {
                String m = x.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("video/")) { s.track = i; break; }
            }
            if (s.track < 0) throw new IllegalStateException("novideo");
            s.fmt = x.getTrackFormat(s.track);
            s.mime = s.fmt.getString(MediaFormat.KEY_MIME);
            s.w = s.fmt.getInteger(MediaFormat.KEY_WIDTH);
            s.h = s.fmt.getInteger(MediaFormat.KEY_HEIGHT);
        } finally {
            x.release();
        }
        s.rot = VideoMux.rotation(c, Uri.parse(uri));
        return s;
    }

    /** same format everywhere and every clip starts on a key frame: the picture can be copied without re-encoding */
    private static boolean copyable(Context c, Seg[] segs) {
        Seg f = segs[0];
        ByteBuffer csd = f.fmt.getByteBuffer("csd-0");
        for (Seg s : segs) {
            if (!s.mime.equals(f.mime) || s.w != f.w || s.h != f.h || s.rot != f.rot) return false;
            ByteBuffer o = s.fmt.getByteBuffer("csd-0");
            if (csd == null ? o != null : !csd.equals(o)) return false;
            if (s.in == 0) continue;
            MediaExtractor x = new MediaExtractor();
            try {
                x.setDataSource(c, Uri.parse(s.uri), null);
                x.selectTrack(s.track);
                x.seekTo(s.in, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                if (Math.abs(x.getSampleTime() - s.in) > 40000) return false;
            } catch (Exception e) {
                return false;
            } finally {
                x.release();
            }
        }
        return true;
    }

    private static void copyPicture(Context c, Seg[] segs, File out, long total, AudioTools.Progress p, double lo, double hi, AtomicBoolean cancel) throws Exception {
        MediaMuxer mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        try {
            int t = mux.addTrack(segs[0].fmt);
            mux.start();
            int cap = 4 << 20;
            for (Seg s : segs) if (s.fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) cap = Math.max(cap, s.fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
            ByteBuffer buf = ByteBuffer.allocate(cap);
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            long base = 0, n = 0;
            for (Seg s : segs) {
                MediaExtractor x = new MediaExtractor();
                try {
                    x.setDataSource(c, Uri.parse(s.uri), null);
                    x.selectTrack(s.track);
                    x.seekTo(s.in, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                    long first = x.getSampleTime();
                    while (true) {
                        if (cancel != null && cancel.get()) throw new InterruptedException("cancel");
                        long tm = x.getSampleTime();
                        if (tm < 0 || tm >= s.out) break;
                        int size = x.readSampleData(buf, 0);
                        if (size < 0) break;
                        long pts = base + tm - first;
                        if (pts >= 0) {
                            bi.set(0, size, pts, (x.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                            mux.writeSampleData(t, buf, bi);
                        }
                        x.advance();
                        if (p != null && ++n % 64 == 0) p.on(lo + (hi - lo) * Math.min(1, pts / (double) total));
                    }
                } finally {
                    x.release();
                }
                base += s.out - s.in;
            }
            mux.stop();
        } finally {
            mux.release();
        }
    }
}
