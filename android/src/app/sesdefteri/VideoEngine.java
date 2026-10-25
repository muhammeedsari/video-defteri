package app.sesdefteri;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
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
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.Choreographer;
import android.view.Surface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The video engine: clips are decoded with MediaCodec into SurfaceTextures and drawn with OpenGL ES 2 (fit, rotation,
 * crossfade, burned-in subtitles). The same compositor draws the live preview (a TextureView over the page, clocked by
 * the sound from Timeline) and the export (the encoder's input surface, fixed frame rate).
 */
final class VideoEngine {

    /* ---------- OpenGL ---------- */

    private static final String VS = "uniform mat4 uM; uniform mat4 uT; attribute vec4 aP; attribute vec4 aC; varying vec2 vC;"
        + "void main() { gl_Position = uM * aP; vC = (uT * aC).xy; }";
    private static final String FS_OES = "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 vC;"
        + "uniform samplerExternalOES uS; uniform float uA; void main() { gl_FragColor = vec4(texture2D(uS, vC).rgb, uA); }";
    private static final String FS_2D = "precision mediump float; varying vec2 vC; uniform sampler2D uS; uniform float uA;"
        + "void main() { gl_FragColor = texture2D(uS, vC) * uA; }";
    /** 9 taps along uD (one direction of a gaussian), rgb scaled by uA */
    private static final String FS_BLUR = "precision mediump float; varying vec2 vC; uniform sampler2D uS; uniform float uA; uniform vec2 uD;"
        + "void main() { vec3 c = texture2D(uS, vC).rgb * 0.227;"
        + " c += (texture2D(uS, vC + uD).rgb + texture2D(uS, vC - uD).rgb) * 0.195;"
        + " c += (texture2D(uS, vC + 2.0 * uD).rgb + texture2D(uS, vC - 2.0 * uD).rgb) * 0.122;"
        + " c += (texture2D(uS, vC + 3.0 * uD).rgb + texture2D(uS, vC - 3.0 * uD).rgb) * 0.054;"
        + " c += (texture2D(uS, vC + 4.0 * uD).rgb + texture2D(uS, vC - 4.0 * uD).rgb) * 0.016;"
        + " gl_FragColor = vec4(c * uA, 1.0); }";
    private static final float[] QUAD = { -1, -1, 0, 0, 1, -1, 1, 0, -1, 1, 0, 1, 1, 1, 1, 1 };
    private static final float[] FLIP = { 1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 0, 1, 0, 1 };   // bitmap rows run downwards
    private static final float[] ID = new float[16];
    static { Matrix.setIdentityM(ID, 0); }
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    static final class Gl {
        EGLDisplay dpy;
        EGLContext ctx;
        EGLConfig cfg;
        EGLSurface pbuf;
        int progOes, prog2d, progBlur;
        float dx, dy;   // uD of the blur
        FloatBuffer quad;

        Gl() {
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] v = new int[2];
            if (!EGL14.eglInitialize(dpy, v, 0, v, 1)) throw new IllegalStateException("egl");
            int[] at = { EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE };
            EGLConfig[] cf = new EGLConfig[1];
            int[] n = new int[1];
            if (!EGL14.eglChooseConfig(dpy, at, 0, cf, 0, 1, n, 0) || n[0] < 1) throw new IllegalStateException("egl");
            cfg = cf[0];
            ctx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, new int[] { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE }, 0);
            pbuf = EGL14.eglCreatePbufferSurface(dpy, cfg, new int[] { EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE }, 0);
            if (ctx == null || pbuf == null || !EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx)) throw new IllegalStateException("egl");
            progOes = program(VS, FS_OES);
            prog2d = program(VS, FS_2D);
            progBlur = program(VS, FS_BLUR);
            quad = ByteBuffer.allocateDirect(QUAD.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(QUAD).position(0);
        }

        /** a Surface or a SurfaceTexture to draw into */
        EGLSurface window(Object s) {
            EGLSurface w = EGL14.eglCreateWindowSurface(dpy, cfg, s, new int[] { EGL14.EGL_NONE }, 0);
            if (w == null || w == EGL14.EGL_NO_SURFACE) throw new IllegalStateException("egl");
            return w;
        }

        void current(EGLSurface s) { EGL14.eglMakeCurrent(dpy, s, s, ctx); }

        void drop(EGLSurface s) {
            EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx);
            EGL14.eglDestroySurface(dpy, s);
        }

        static int texture(int target) {
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            GLES20.glBindTexture(target, t[0]);
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            return t[0];
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

        void draw(int prog, int target, int tex, float[] m, float[] t, float alpha) {
            GLES20.glUseProgram(prog);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(target, tex);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uS"), 0);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uA"), alpha);
            if (prog == progBlur) GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uD"), dx, dy);
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
            EGL14.eglDestroySurface(dpy, pbuf);
            EGL14.eglDestroyContext(dpy, ctx);
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(dpy);
            dpy = null;
        }
    }

    /* ---------- one decoder: frames of one source, shown up to a time ---------- */

    static final class Dec {
        final MediaExtractor x = new MediaExtractor();
        MediaCodec c;
        SurfaceTexture st;
        Surface surf;
        final int tex;
        final Object lock = new Object();
        boolean avail, inEos, outEos, hasFrame;
        final MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        int held = -1;
        long heldPts, shownPts = Long.MIN_VALUE, seekTo = Long.MIN_VALUE;
        final float[] stm = new float[16];
        int w, h, rot;

        Dec(Context ctx, String src, Handler frames) throws Exception {
            tex = Gl.texture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
            try {
                if (src.startsWith("content://")) {
                    x.setDataSource(ctx, Uri.parse(src), null);
                    rot = VideoMux.rotation(ctx, Uri.parse(src));
                } else {
                    x.setDataSource(src);
                }
                int t = -1;
                for (int i = 0; i < x.getTrackCount(); i++) {
                    String m = x.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                    if (m != null && m.startsWith("video/")) { t = i; break; }
                }
                if (t < 0) throw new IllegalStateException("novideo");
                x.selectTrack(t);
                MediaFormat f = x.getTrackFormat(t);
                w = f.getInteger(MediaFormat.KEY_WIDTH);
                h = f.getInteger(MediaFormat.KEY_HEIGHT);
                st = new SurfaceTexture(tex);
                st.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
                    @Override public void onFrameAvailable(SurfaceTexture s) { synchronized (lock) { avail = true; lock.notifyAll(); } }
                }, frames);
                surf = new Surface(st);
                c = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME));
                c.configure(f, surf, null, 0);
                c.start();
            } catch (Exception e) {
                release();
                throw e;
            }
        }

        /** the next decoded frame (held, not yet shown) or -1 when none came before the deadline / at the end */
        private int next(long deadline) {
            while (true) {
                while (!inEos) {
                    int ii = c.dequeueInputBuffer(0);
                    if (ii < 0) break;
                    int n = x.readSampleData(c.getInputBuffer(ii), 0);
                    if (n < 0) { c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true; }
                    else { c.queueInputBuffer(ii, 0, n, x.getSampleTime(), 0); x.advance(); }
                }
                int oi = c.dequeueOutputBuffer(bi, 2000);
                if (oi >= 0) {
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true;
                    if (bi.size == 0) { c.releaseOutputBuffer(oi, false); if (outEos) return -1; continue; }
                    heldPts = bi.presentationTimeUs;
                    return oi;
                }
                if (outEos || System.currentTimeMillis() >= deadline) return -1;
            }
        }

        /** shows the latest frame at or before us (frames before skipBefore are decoded but not shown) */
        void advance(long us, long deadline, long skipBefore) {
            boolean shown = false;
            while (true) {
                if (held < 0) {
                    if (outEos) return;
                    held = next(shown ? System.currentTimeMillis() + 2 : deadline);
                    if (held < 0) return;
                }
                if (heldPts > us + 2000) return;
                if (heldPts < skipBefore) { c.releaseOutputBuffer(held, false); held = -1; continue; }
                long pts = heldPts;
                c.releaseOutputBuffer(held, true);
                held = -1;
                synchronized (lock) {
                    long until = System.currentTimeMillis() + 500;
                    while (!avail) {
                        long wt = until - System.currentTimeMillis();
                        if (wt <= 0) break;
                        try { lock.wait(wt); } catch (InterruptedException e) { return; }
                    }
                    if (!avail) continue;
                    avail = false;
                }
                st.updateTexImage();
                st.getTransformMatrix(stm);
                hasFrame = true;
                shownPts = pts;
                shown = true;
                if (System.currentTimeMillis() > deadline) return;
            }
        }

        void seek(long us, long deadline) {
            if (held >= 0) { c.releaseOutputBuffer(held, false); held = -1; }
            x.seekTo(us, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            c.flush();
            inEos = outEos = false;
            seekTo = us;
            advance(us, deadline, us - 40000);
        }

        void release() {
            if (c != null) { try { c.stop(); } catch (Exception ignored) { } c.release(); c = null; }
            if (surf != null) { surf.release(); surf = null; }
            if (st != null) { st.release(); st = null; }
            x.release();
            GLES20.glDeleteTextures(1, new int[] { tex }, 0);
        }
    }

    /* ---------- burned-in subtitles ---------- */

    static final class Subs {
        final double[] t0, t1;
        final String[] text;
        final Bitmap bmp;
        final TextPaint fill, stroke;
        final int tex;
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
            tex = Gl.texture(GLES20.GL_TEXTURE_2D);
        }

        /** uploads the subtitle for time s when it changed; false when there is none */
        boolean at(double s) {
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

    /* ---------- the compositor ---------- */

    static final class Clip {
        String id, src;
        long at, in, out, tx;   // µs: place in the sequence, source in/out, fade-in length
        long end() { return at + out - in; }
    }

    static final class Comp {
        final Context ctx;
        final Gl gl;
        final Handler frames;
        final ArrayList<Clip> clips = new ArrayList<>();
        final HashMap<String, Dec> decs = new HashMap<>();
        long total;
        Subs subs;
        final float[] m = new float[16], r = new float[16], mm = new float[16];
        float bgR, bgG, bgB;
        boolean blur;
        final int[] fb = new int[2], ft = new int[2];
        int fw, fh;   // the small frames the blurred background is made in

        Comp(Context ctx, Gl gl, Handler frames) { this.ctx = ctx; this.gl = gl; this.frames = frames; }

        /** [{id, src, at, in, out, tx}] in seconds; clips without a source are left black */
        void set(JSONArray a) throws Exception {
            clips.clear();
            total = 0;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Clip k = new Clip();
                k.id = o.getString("id");
                k.src = o.optString("src", "");
                k.at = Math.round(o.getDouble("at") * 1e6);
                k.in = Math.round(o.getDouble("in") * 1e6);
                k.out = Math.round(o.getDouble("out") * 1e6);
                k.tx = Math.round(o.optDouble("tx", 0) * 1e6);
                if (k.out <= k.in) continue;
                clips.add(k);
                total = Math.max(total, k.end());
            }
            // a decoder belongs to a clip id and its source
            Iterator<Map.Entry<String, Dec>> it = decs.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Dec> e = it.next();
                boolean keep = false;
                for (Clip k : clips) if ((k.id + "|" + k.src).equals(e.getKey())) keep = true;
                if (!keep) { e.getValue().release(); it.remove(); }
            }
        }

        /** {bg: "#rrggbb", blur}: what fills the frame around a clip */
        void look(JSONObject o) {
            int c = 0;
            try { if (o != null) c = (int) Long.parseLong(o.optString("bg", "#000000").replace("#", ""), 16); } catch (Exception ignored) { }
            bgR = ((c >> 16) & 255) / 255f; bgG = ((c >> 8) & 255) / 255f; bgB = (c & 255) / 255f;
            blur = o != null && o.optBoolean("blur", false);
        }

        /** the clip filling the frame, small and blurred, behind it */
        private void blurBehind(Dec d, double sw, double sh, int W, int H, float a) {
            int w = W >= H ? 96 : Math.max(2, Math.round(96f * W / H)), h = W >= H ? Math.max(2, Math.round(96f * H / W)) : 96;
            if (w != fw || h != fh) {
                if (fw > 0) { GLES20.glDeleteFramebuffers(2, fb, 0); GLES20.glDeleteTextures(2, ft, 0); }
                fw = w; fh = h;
                GLES20.glGenFramebuffers(2, fb, 0);
                for (int i = 0; i < 2; i++) {
                    ft[i] = Gl.texture(GLES20.GL_TEXTURE_2D);
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fb[i]);
                    GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, ft[i], 0);
                }
            }
            double s = Math.max(W / sw, H / sh);
            Matrix.setIdentityM(m, 0);
            Matrix.scaleM(m, 0, (float) (sw * s / W), (float) (sh * s / H), 1);
            Matrix.multiplyMM(mm, 0, m, 0, r, 0);
            GLES20.glViewport(0, 0, w, h);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fb[0]);
            gl.draw(gl.progOes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, d.tex, mm, d.stm, 1f);
            for (int i = 0; i < 4; i++) {   // across, down, across, down
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fb[(i + 1) & 1]);
                gl.dx = (i & 1) == 0 ? 1.5f / w : 0; gl.dy = (i & 1) == 0 ? 0 : 1.5f / h;
                gl.draw(gl.progBlur, GLES20.GL_TEXTURE_2D, ft[i & 1], ID, ID, i == 3 ? 0.7f : 1f);
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, W, H);
            if (a < 1) { GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA); }
            gl.draw(gl.prog2d, GLES20.GL_TEXTURE_2D, ft[0], ID, ID, a);
            if (a < 1) GLES20.glDisable(GLES20.GL_BLEND);
        }

        private Dec dec(Clip k) {
            String key = k.id + "|" + k.src;
            Dec d = decs.get(key);
            if (d == null && !k.src.isEmpty() && !decs.containsKey(key)) {
                try { d = new Dec(ctx, k.src, frames); } catch (Exception e) { d = null; }
                decs.put(key, d);
            }
            return d;
        }

        /** draws time T into a W×H surface; budget: ms each decoder may take to catch up */
        void draw(long T, int W, int H, long budget, long seekBudget) {
            GLES20.glViewport(0, 0, W, H);
            GLES20.glClearColor(bgR, bgG, bgB, 1);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            java.util.HashSet<String> used = new java.util.HashSet<>();
            for (Clip k : clips) {
                boolean on = T >= k.at && T < k.end(), soon = !on && k.at > T && k.at - T < 1000000;
                if (!on && !soon) continue;
                Dec d = dec(k);
                used.add(k.id + "|" + k.src);
                if (d == null) continue;
                long local = on ? k.in + T - k.at : k.in;
                long now = System.currentTimeMillis();
                // a seek still on its way (decoding from the key frame) keeps going instead of starting over
                boolean catching = d.seekTo != Long.MIN_VALUE && (!d.hasFrame || d.shownPts < d.seekTo - 40000);
                long ref = catching ? d.seekTo : d.hasFrame ? d.shownPts : Long.MIN_VALUE;
                long dl = now + (on ? (catching ? seekBudget : budget) : Math.min(seekBudget, 60));
                if (ref == Long.MIN_VALUE || local < ref - 1000 || local - ref > 1500000) d.seek(local, dl);
                else if (on || catching) d.advance(local, dl, catching ? local - 40000 : Long.MIN_VALUE);
                if (!on || !d.hasFrame) continue;
                float a = k.tx > 0 && T < k.at + k.tx ? (float) (T - k.at) / k.tx : 1f;
                boolean turn = d.rot == 90 || d.rot == 270;
                double sw = turn ? d.h : d.w, sh = turn ? d.w : d.h, s = Math.min(W / sw, H / sh);
                Matrix.setRotateM(r, 0, -d.rot, 0, 0, 1);
                if (blur && Math.abs(sw * s - W) + Math.abs(sh * s - H) > 2) blurBehind(d, sw, sh, W, H, a);
                Matrix.setIdentityM(m, 0);
                Matrix.scaleM(m, 0, (float) (sw * s / W), (float) (sh * s / H), 1);
                Matrix.multiplyMM(mm, 0, m, 0, r, 0);
                if (a < 1) { GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA); }
                gl.draw(gl.progOes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, d.tex, mm, d.stm, a);
                if (a < 1) GLES20.glDisable(GLES20.GL_BLEND);
            }
            if (subs != null && subs.at(T / 1e6)) {
                GLES20.glEnable(GLES20.GL_BLEND);
                GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                gl.draw(gl.prog2d, GLES20.GL_TEXTURE_2D, subs.tex, ID, FLIP, 1f);
                GLES20.glDisable(GLES20.GL_BLEND);
            }
            Iterator<Map.Entry<String, Dec>> it = decs.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Dec> e = it.next();
                if (!used.contains(e.getKey())) { if (e.getValue() != null) e.getValue().release(); it.remove(); }
            }
        }

        void release() {
            for (Dec d : decs.values()) if (d != null) d.release();
            decs.clear();
        }
    }

    /* ---------- export / proxy: the compositor into an H.264 encoder ---------- */

    /** clips as for Comp.set; W×H even; gop: seconds between key frames */
    static void encode(Context c, JSONArray clips, int W, int H, int fps, float gop, JSONArray subs, JSONObject look, File out,
                       AudioTools.Progress p, double lo, double hi, AtomicBoolean cancel) throws Exception {
        MediaCodec enc = null;
        Surface in = null;
        Gl gl = null;
        EGLSurface win = null;
        Comp comp = null;
        HandlerThread ht = new HandlerThread("vframes");
        ht.start();
        MediaMuxer mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int[] track = { -1 };
        try {
            for (int attempt = 0; ; attempt++) {
                MediaFormat vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H);
                vf.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                vf.setInteger(MediaFormat.KEY_BIT_RATE, (int) Math.min(16000000, Math.max(1500000, (long) W * H * fps / 6)));
                vf.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
                if (Build.VERSION.SDK_INT >= 25) vf.setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, gop);
                else vf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, Math.max(1, Math.round(gop)));
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
            gl = new Gl();
            win = gl.window(in);
            gl.current(win);
            comp = new Comp(c, gl, new Handler(ht.getLooper()));
            comp.set(clips);
            comp.look(look);
            if (subs != null && subs.length() > 0) comp.subs = new Subs(subs, W, H);
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            long total = comp.total;
            for (long k = 0; ; k++) {
                long T = k * 1000000L / fps;
                if (T >= total) break;
                if (cancel != null && cancel.get()) throw new InterruptedException("cancel");
                comp.draw(T, W, H, 3000, 3000);
                EGLExt.eglPresentationTimeANDROID(gl.dpy, win, T * 1000);
                EGL14.eglSwapBuffers(gl.dpy, win);
                drain(enc, mux, track, bi, false);
                if (p != null && k % 8 == 0) p.on(lo + (hi - lo) * T / (double) total);
            }
            enc.signalEndOfInputStream();
            drain(enc, mux, track, bi, true);
            if (track[0] < 0) throw new IllegalStateException("videoenc");
            mux.stop();
        } finally {
            if (comp != null) comp.release();
            if (win != null) gl.drop(win);
            if (gl != null) gl.release();
            ht.quit();
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

    /* ---------- live preview: a TextureView over the page, clocked by the sound ---------- */

    static final VideoEngine I = new VideoEngine();

    private HandlerThread th, fth;
    private Handler h;
    private Context ctx;
    private Gl gl;
    private Comp comp;
    private EGLSurface win;
    private int vw, vh;
    private volatile long T;
    private volatile boolean playing;
    private boolean audio, drawQueued;
    private long anchorT, anchorWall;
    private String pending;

    private VideoEngine() { }

    private synchronized Handler handler(Context c) {
        if (h == null) {
            ctx = c.getApplicationContext();
            th = new HandlerThread("vengine");
            th.start();
            fth = new HandlerThread("vframes");
            fth.start();
            h = new Handler(th.getLooper());
            h.post(new Runnable() {
                @Override public void run() {
                    try { gl = new Gl(); comp = new Comp(ctx, gl, new Handler(fth.getLooper())); } catch (Exception e) { gl = null; }
                }
            });
        }
        return h;
    }

    void surface(Context c, final SurfaceTexture st, final int w, final int ht) {
        handler(c).post(new Runnable() {
            @Override public void run() {
                if (gl == null) return;
                if (win != null) gl.drop(win);
                try { win = gl.window(st); } catch (Exception e) { win = null; }
                vw = w; vh = ht;
                if (pending != null) apply();
                redraw();
            }
        });
    }

    void size(final int w, final int ht) {
        if (h == null) return;
        h.post(new Runnable() { @Override public void run() { vw = w; vh = ht; redraw(); } });
    }

    /** the view is going away: the drawing surface must be dropped before it returns */
    void surfaceGone() {
        if (h == null) return;
        final CountDownLatch l = new CountDownLatch(1);
        h.post(new Runnable() {
            @Override public void run() {
                playing = false;
                if (win != null && gl != null) gl.drop(win);
                win = null;
                l.countDown();
            }
        });
        try { l.await(2, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
    }

    void project(Context c, String json) {
        pending = json;
        handler(c).post(new Runnable() { @Override public void run() { apply(); redraw(); } });
    }

    private void apply() {
        if (comp == null || pending == null) return;
        try { JSONObject o = new JSONObject(pending); comp.set(o.getJSONArray("clips")); comp.look(o); } catch (Exception ignored) { }
        pending = null;
    }

    void seek(double s) {
        if (h == null) return;
        T = Math.max(0, (long) (s * 1e6));
        if (playing) { anchorT = T; anchorWall = System.nanoTime(); }
        else redraw();
    }

    void play(Context c, double s, final String audioJson) {
        handler(c);
        T = Math.max(0, (long) (s * 1e6));
        h.post(new Runnable() {
            @Override public void run() {
                if (comp == null) return;
                apply();
                if (T >= comp.total) T = 0;
                audio = false;
                try {
                    JSONArray a = new JSONArray(audioJson == null || audioJson.isEmpty() ? "[]" : audioJson);
                    if (a.length() > 0) { Timeline.play(a.toString(), T / 1e6); audio = true; }
                } catch (Exception ignored) { }
                anchorT = T;
                anchorWall = System.nanoTime();
                playing = true;
                Choreographer.getInstance().postFrameCallback(tick);
            }
        });
    }

    void pause() {
        playing = false;
        if (audio) { Timeline.stop(); audio = false; }
        redraw();
    }

    double pos() { return T / 1e6; }

    /** paint the current frame again (the view moved or came back) */
    void refresh() { if (!playing) redraw(); }

    boolean isPlaying() { return playing; }

    private final Choreographer.FrameCallback tick = new Choreographer.FrameCallback() {
        @Override public void doFrame(long ns) {
            if (!playing || comp == null) return;
            long now = System.nanoTime();
            double a = audio ? Timeline.pos() : -1;
            if (a >= 0) { anchorT = (long) (a * 1e6); anchorWall = now; T = anchorT; }
            else { audio = false; T = anchorT + (now - anchorWall) / 1000; }
            if (T >= comp.total) {
                T = comp.total;
                playing = false;
                Timeline.stop();
                paint(Math.max(0, T - 1000), 30, 300);
                return;
            }
            paint(T, 20, 60);
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private void redraw() {
        if (h == null || drawQueued) return;
        drawQueued = true;
        h.post(new Runnable() {
            @Override public void run() {
                drawQueued = false;
                if (!playing) paint(Math.min(T, comp == null ? 0 : Math.max(0, comp.total - 1000)), 400, 600);
            }
        });
    }

    private void paint(long t, long budget, long seekBudget) {
        if (win == null || comp == null || vw <= 0 || vh <= 0) return;
        gl.current(win);
        comp.draw(t, vw, vh, budget, seekBudget);
        EGL14.eglSwapBuffers(gl.dpy, win);
    }

    /** drop all decoders (the app goes to the background) */
    void idle() {
        playing = false;
        if (h == null) return;
        h.post(new Runnable() { @Override public void run() { if (comp != null) comp.release(); } });
    }
}
