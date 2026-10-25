package app.sesdefteri;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.Surface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Video Defteri: the phone's videos, thumbnails, and the export of a cut list (clips of videos one after the other)
 * with its sound into one MP4. When every clip has the same format and starts on a key frame the picture is copied
 * as it is; otherwise it is decoded, drawn with OpenGL (fit, rotation, burned-in subtitles) and encoded again (H.264).
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

    /** a: {clips: [{uri, in, out (s)}], audio?, q: 0 (as it is) / 1080 / 720, subs?: [[t0, t1, text]] burned in, name} */
    static JSONObject render(Context c, JSONObject a, AudioTools.Progress p, AtomicBoolean cancel) throws Exception {
        JSONArray clips = a.getJSONArray("clips");
        if (clips.length() == 0) throw new IllegalStateException("empty");
        Seg[] segs = new Seg[clips.length()];
        long total = 0;
        for (int i = 0; i < segs.length; i++) {
            JSONObject k = clips.getJSONObject(i);
            Seg s = segs[i] = open(c, k.getString("uri"));
            s.in = Math.max(0, Math.round(k.getDouble("in") * 1e6));
            s.out = Math.round(k.getDouble("out") * 1e6);
            if (s.out <= s.in) throw new IllegalStateException("empty");
            total += s.out - s.in;
        }
        int q = a.optInt("q", 0);
        JSONArray subs = a.optJSONArray("subs");
        if (subs != null && subs.length() == 0) subs = null;
        boolean copy = q == 0 && subs == null && copyable(c, segs);

        File cache = c.getCacheDir();
        long id = System.nanoTime();
        File m4a = new File(cache, "vexp-" + id + ".m4a"), pic = new File(cache, "vexp-" + id + "-v.mp4"), mp4 = new File(cache, "vexp-" + id + ".mp4");
        File wav = null;
        try {
            wav = sound(c, a, p, 0, 0.2, cancel);
            if (wav != null) VideoMux.aac(wav, total, m4a, p, 0.2, 0.3, cancel);
            if (copy) copyPicture(c, segs, pic, total, p, 0.3, 0.9, cancel);
            else transcode(c, segs, q, subs, pic, total, p, 0.3, 0.95, cancel);
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

    /* ----- decode → OpenGL → encode ----- */

    private static final String VS = "uniform mat4 uM; uniform mat4 uT; attribute vec4 aP; attribute vec4 aC; varying vec2 vC;"
        + "void main() { gl_Position = uM * aP; vC = (uT * aC).xy; }";
    private static final String FS_OES = "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 vC;"
        + "uniform samplerExternalOES uS; void main() { gl_FragColor = texture2D(uS, vC); }";
    private static final String FS_2D = "precision mediump float; varying vec2 vC; uniform sampler2D uS; void main() { gl_FragColor = texture2D(uS, vC); }";
    private static final float[] QUAD = { -1, -1, 0, 0, 1, -1, 1, 0, -1, 1, 0, 1, 1, 1, 1, 1 };
    private static final float[] FLIP = { 1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 0, 1, 0, 1 };   // bitmap rows run downwards
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private static final class Gl {
        EGLDisplay dpy;
        EGLContext ctx;
        EGLSurface surf;
        int progOes, prog2d, texOes, texSub;
        FloatBuffer quad;

        Gl(Surface s) {
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] v = new int[2];
            if (!EGL14.eglInitialize(dpy, v, 0, v, 1)) throw new IllegalStateException("egl");
            int[] at = { EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE };
            EGLConfig[] cf = new EGLConfig[1];
            int[] n = new int[1];
            if (!EGL14.eglChooseConfig(dpy, at, 0, cf, 0, 1, n, 0) || n[0] < 1) throw new IllegalStateException("egl");
            ctx = EGL14.eglCreateContext(dpy, cf[0], EGL14.EGL_NO_CONTEXT, new int[] { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE }, 0);
            surf = EGL14.eglCreateWindowSurface(dpy, cf[0], s, new int[] { EGL14.EGL_NONE }, 0);
            if (ctx == null || surf == null || !EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) throw new IllegalStateException("egl");
            progOes = program(VS, FS_OES);
            prog2d = program(VS, FS_2D);
            int[] t = new int[2];
            GLES20.glGenTextures(2, t, 0);
            texOes = t[0];
            texSub = t[1];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texOes);
            params(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texSub);
            params(GLES20.GL_TEXTURE_2D);
            quad = ByteBuffer.allocateDirect(QUAD.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(QUAD).position(0);
        }

        private static void params(int target) {
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        }

        private static int shader(int type, String src) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            int[] ok = new int[1];
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) throw new IllegalStateException("gl: " + GLES20.glGetShaderInfoLog(s));
            return s;
        }

        private static int program(String vs, String fs) {
            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs));
            GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs));
            GLES20.glLinkProgram(p);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) throw new IllegalStateException("gl: " + GLES20.glGetProgramInfoLog(p));
            return p;
        }

        void draw(int prog, int target, int tex, float[] m, float[] t) {
            GLES20.glUseProgram(prog);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(target, tex);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uS"), 0);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uM"), 1, false, m, 0);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uT"), 1, false, t, 0);
            int ap = GLES20.glGetAttribLocation(prog, "aP"), ac = GLES20.glGetAttribLocation(prog, "aC");
            quad.position(0);
            GLES20.glVertexAttribPointer(ap, 2, GLES20.GL_FLOAT, false, 16, quad);
            GLES20.glEnableVertexAttribArray(ap);
            quad.position(2);
            GLES20.glVertexAttribPointer(ac, 2, GLES20.GL_FLOAT, false, 16, quad);
            GLES20.glEnableVertexAttribArray(ac);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }

        void release() {
            if (dpy == null) return;
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (surf != null) EGL14.eglDestroySurface(dpy, surf);
            if (ctx != null) EGL14.eglDestroyContext(dpy, ctx);
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(dpy);
            dpy = null;
        }
    }

    /** the burned-in subtitle: a picture as big as the frame, white text with a dark outline near the bottom */
    private static final class Subs {
        final double[] t0, t1;
        final String[] text;
        final Bitmap bmp;
        final TextPaint fill, stroke;
        int shown = -2;

        Subs(JSONArray a, int w, int h) throws Exception {
            int n = a.length();
            t0 = new double[n]; t1 = new double[n]; text = new String[n];
            for (int i = 0; i < n; i++) { JSONArray s = a.getJSONArray(i); t0[i] = s.getDouble(0); t1[i] = s.getDouble(1); text[i] = s.getString(2).trim(); }
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            float size = Math.min(w, h) * 0.058f;
            fill = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            fill.setTextSize(size);
            fill.setColor(Color.WHITE);
            fill.setFakeBoldText(true);
            stroke = new TextPaint(fill);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(size * 0.16f);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setColor(0xE0000000);
        }

        /** uploads the subtitle for time s when it changed; returns false when there is none to draw */
        boolean at(double s, int tex) {
            int k = -1;
            for (int i = 0; i < t0.length; i++) if (s >= t0[i] && s < t1[i] && !text[i].isEmpty()) { k = i; break; }
            if (k != shown) {
                shown = k;
                if (k >= 0) {
                    bmp.eraseColor(0);
                    Canvas cv = new Canvas(bmp);
                    int w = bmp.getWidth(), lw = (int) (w * 0.88f);
                    StaticLayout ls = new StaticLayout(text[k], stroke, lw, Layout.Alignment.ALIGN_CENTER, 1f, 0f, false);
                    StaticLayout lf = new StaticLayout(text[k], fill, lw, Layout.Alignment.ALIGN_CENTER, 1f, 0f, false);
                    cv.translate((w - lw) / 2f, bmp.getHeight() - bmp.getHeight() * 0.06f - lf.getHeight());
                    ls.draw(cv);
                    lf.draw(cv);
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
                }
            }
            return k >= 0;
        }
    }

    private static int even(double v) { return Math.max(2, (int) Math.round(v / 2) * 2); }

    private static void transcode(Context c, Seg[] segs, int q, JSONArray subs, File out, long total, AudioTools.Progress p, double lo, double hi,
                                  AtomicBoolean cancel) throws Exception {
        // output size: the first clip as shown, its short side 1080 / 720 (or as it is, at most 1920 on the long side)
        Seg f = segs[0];
        boolean turn = f.rot == 90 || f.rot == 270;
        double dw = turn ? f.h : f.w, dh = turn ? f.w : f.h;
        double sc = q > 0 ? q / Math.min(dw, dh) : Math.min(1, 1920 / Math.max(dw, dh));
        int W = even(dw * sc), H = even(dh * sc);

        MediaCodec enc = null;
        Surface in = null;
        Gl gl = null;
        SurfaceTexture st = null;
        HandlerThread ht = null;
        MediaMuxer mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        final int[] track = { -1 };
        try {
            for (int attempt = 0; ; attempt++) {
                MediaFormat vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H);
                vf.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                vf.setInteger(MediaFormat.KEY_BIT_RATE, (int) Math.min(16000000, Math.max(2000000, (long) W * H * 4)));
                vf.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
                vf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                try {
                    enc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    break;
                } catch (Exception e) {
                    enc.release();
                    enc = null;
                    if (attempt > 0) throw new IllegalStateException("videoenc");
                    W = W / 16 * 16; H = H / 16 * 16;   // some encoders want sizes in steps of 16
                }
            }
            in = enc.createInputSurface();
            enc.start();
            gl = new Gl(in);
            final Object lock = new Object();
            final boolean[] avail = { false };
            ht = new HandlerThread("vframes");
            ht.start();
            st = new SurfaceTexture(gl.texOes);
            st.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
                @Override public void onFrameAvailable(SurfaceTexture t) { synchronized (lock) { avail[0] = true; lock.notifyAll(); } }
            }, new Handler(ht.getLooper()));
            Surface decOut = new Surface(st);
            Subs sub = subs != null ? new Subs(subs, W, H) : null;
            MediaCodec.BufferInfo ei = new MediaCodec.BufferInfo(), di = new MediaCodec.BufferInfo();
            float[] stm = new float[16], m = new float[16], rot = new float[16], id = new float[16];
            Matrix.setIdentityM(id, 0);
            long base = 0, last = -1;
            try {
                for (Seg s : segs) {
                    // this clip fitted into the frame, turned upright
                    boolean tn = s.rot == 90 || s.rot == 270;
                    double sw = tn ? s.h : s.w, sh = tn ? s.w : s.h, k = Math.min(W / sw, H / sh);
                    Matrix.setIdentityM(m, 0);
                    Matrix.scaleM(m, 0, (float) (sw * k / W), (float) (sh * k / H), 1);
                    Matrix.setRotateM(rot, 0, -s.rot, 0, 0, 1);
                    float[] mm = new float[16];
                    Matrix.multiplyMM(mm, 0, m, 0, rot, 0);

                    MediaExtractor x = new MediaExtractor();
                    MediaCodec dec = null;
                    try {
                        x.setDataSource(c, Uri.parse(s.uri), null);
                        x.selectTrack(s.track);
                        x.seekTo(s.in, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                        dec = MediaCodec.createDecoderByType(s.mime);
                        MediaFormat df = s.fmt;
                        if (Build.VERSION.SDK_INT >= 30) df.setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, 0);
                        dec.configure(df, decOut, null, 0);
                        dec.start();
                        boolean inEnd = false, outEnd = false;
                        while (!outEnd) {
                            if (cancel != null && cancel.get()) throw new InterruptedException("cancel");
                            if (!inEnd) {
                                int ii = dec.dequeueInputBuffer(5000);
                                if (ii >= 0) {
                                    long tm = x.getSampleTime();
                                    int size = tm < 0 || tm > s.out + 500000 ? -1 : x.readSampleData(dec.getInputBuffer(ii), 0);
                                    if (size < 0) {
                                        dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                        inEnd = true;
                                    } else {
                                        dec.queueInputBuffer(ii, 0, size, tm, 0);
                                        x.advance();
                                    }
                                }
                            }
                            int oi = dec.dequeueOutputBuffer(di, 5000);
                            if (oi < 0) continue;
                            long pts = di.presentationTimeUs;
                            boolean eos = (di.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                            boolean show = di.size > 0 && pts >= s.in && pts < s.out;
                            dec.releaseOutputBuffer(oi, show);
                            if (show) {
                                synchronized (lock) {
                                    long until = System.currentTimeMillis() + 3000;
                                    while (!avail[0]) {
                                        long w = until - System.currentTimeMillis();
                                        if (w <= 0) throw new IllegalStateException("vframe");
                                        lock.wait(w);
                                    }
                                    avail[0] = false;
                                }
                                st.updateTexImage();
                                long op = base + pts - s.in;
                                if (op > last) {
                                    last = op;
                                    st.getTransformMatrix(stm);
                                    GLES20.glViewport(0, 0, W, H);
                                    GLES20.glClearColor(0, 0, 0, 1);
                                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                                    gl.draw(gl.progOes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, gl.texOes, mm, stm);
                                    if (sub != null && sub.at(op / 1e6, gl.texSub)) {
                                        GLES20.glEnable(GLES20.GL_BLEND);
                                        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                                        gl.draw(gl.prog2d, GLES20.GL_TEXTURE_2D, gl.texSub, id, FLIP);
                                        GLES20.glDisable(GLES20.GL_BLEND);
                                    }
                                    EGLExt.eglPresentationTimeANDROID(gl.dpy, gl.surf, op * 1000);
                                    EGL14.eglSwapBuffers(gl.dpy, gl.surf);
                                    drain(enc, mux, track, ei, false);
                                    if (p != null) p.on(lo + (hi - lo) * Math.min(1, op / (double) total));
                                }
                            }
                            if (eos || pts >= s.out) outEnd = true;
                        }
                    } finally {
                        if (dec != null) try { dec.stop(); } catch (Exception ignored) { }
                        if (dec != null) dec.release();
                        x.release();
                    }
                    base += s.out - s.in;
                }
            } finally {
                decOut.release();
            }
            enc.signalEndOfInputStream();
            drain(enc, mux, track, ei, true);
            if (track[0] < 0) throw new IllegalStateException("videoenc");
            mux.stop();
        } finally {
            if (st != null) st.release();
            if (ht != null) ht.quit();
            if (gl != null) gl.release();
            if (enc != null) { try { enc.stop(); } catch (Exception ignored) { } enc.release(); }
            if (in != null) in.release();
            try { mux.release(); } catch (Exception ignored) { }
        }
    }

    private static void drain(MediaCodec enc, MediaMuxer mux, int[] track, MediaCodec.BufferInfo bi, boolean end) {
        int idle = 0;
        while (true) {
            int oi = enc.dequeueOutputBuffer(bi, end ? 10000 : 0);
            if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!end || ++idle > 300) return;
            } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track[0] = mux.addTrack(enc.getOutputFormat());
                mux.start();
            } else if (oi >= 0) {
                ByteBuffer b = enc.getOutputBuffer(oi);
                if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bi.size > 0 && track[0] >= 0 && b != null) {
                    b.position(bi.offset);
                    b.limit(bi.offset + bi.size);
                    mux.writeSampleData(track[0], b, bi);
                }
                enc.releaseOutputBuffer(oi, false);
                if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            }
        }
    }
}
