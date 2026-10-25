package app.sesdefteri;

import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The project mix becomes the sound of a video: the picture is copied as it is (no re-encoding, so it is fast
 * and loses nothing), the mix is encoded to AAC and the two are written into a new MP4 in Movies/Video Defteri.
 */
final class VideoMux {
    private VideoMux() { }

    /** a: {video: uri, clips: [...] (as for tlRender), name} */
    static JSONObject run(Context c, JSONObject a, final AudioTools.Progress p, AtomicBoolean cancel) throws Exception {
        Uri vu = Uri.parse(a.getString("video"));
        File cache = c.getCacheDir();
        File m4a = new File(cache, "vmix-" + System.nanoTime() + ".m4a"), mp4 = new File(cache, "vout-" + System.nanoTime() + ".mp4");
        File wav = null;
        MediaExtractor vx = new MediaExtractor(), ax = null;
        MediaMuxer mux = null;
        try {
            vx.setDataSource(c, vu, null);
            int vt = -1;
            for (int i = 0; i < vx.getTrackCount(); i++) {
                String mime = vx.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) { vt = i; break; }
            }
            if (vt < 0) throw new IllegalStateException("novideo");
            MediaFormat vf = vx.getTrackFormat(vt);
            long vdur = vf.containsKey(MediaFormat.KEY_DURATION) ? vf.getLong(MediaFormat.KEY_DURATION) : 0;
            if (vdur <= 0) vdur = AudioTools.durationMs(c, vu) * 1000;

            // 1. the mix (0–40 %)
            JSONObject r = Timeline.render(c, new JSONObject().put("clips", a.getJSONArray("clips")), new AudioTools.Progress() {
                @Override public void on(double f) { if (p != null) p.on(f * 0.4); }
            }, cancel);
            wav = new File(r.getString("path"));

            // 2. mix → AAC, never longer than the picture (40–70 %)
            Workspace.Info inf = Workspace.info(wav);
            long frames = vdur > 0 ? Math.min(inf.frames, vdur * inf.rate / 1000000) : inf.frames;
            AudioTools.Encoder enc = new AudioTools.Encoder(m4a);
            enc.start(inf.rate, inf.ch);
            RandomAccessFile raf = new RandomAccessFile(wav, "r");
            try {
                raf.seek(44);
                final int BL = 4096;
                byte[] b = new byte[BL * inf.ch * 2];
                short[] s = new short[BL * inf.ch];
                for (long done = 0; done < frames; ) {
                    if (cancel != null && cancel.get()) throw new InterruptedException("cancel");
                    int n = (int) Math.min(BL, frames - done), cnt = n * inf.ch;
                    raf.readFully(b, 0, cnt * 2);
                    for (int i = 0; i < cnt; i++) s[i] = (short) ((b[2 * i] & 0xff) | (b[2 * i + 1] << 8));
                    enc.feed(s, cnt);
                    done += n;
                    if (p != null && (done / BL) % 16 == 0) p.on(0.4 + 0.3 * done / frames);
                }
                enc.finish();
            } catch (Exception e) {
                enc.abort();
                throw e;
            } finally {
                raf.close();
            }

            // 3. picture + new sound → MP4 (70–100 %)
            ax = new MediaExtractor();
            ax.setDataSource(m4a.getAbsolutePath());
            ax.selectTrack(0);
            vx.selectTrack(vt);
            mux = new MediaMuxer(mp4.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            mux.setOrientationHint(rotation(c, vu));
            int ov, oa;
            try {
                ov = mux.addTrack(vf);
                oa = mux.addTrack(ax.getTrackFormat(0));
            } catch (Exception e) {
                throw new IllegalStateException("videofmt");
            }
            mux.start();
            int cap = vf.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) ? Math.max(vf.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE), 1 << 20) : 4 << 20;
            ByteBuffer buf = ByteBuffer.allocate(cap);
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            boolean vEnd = false, aEnd = false;
            long cnt = 0;
            while (!vEnd || !aEnd) {
                if (cancel != null && cancel.get()) throw new InterruptedException("cancel");
                long vtm = vEnd ? Long.MAX_VALUE : vx.getSampleTime(), atm = aEnd ? Long.MAX_VALUE : ax.getSampleTime();
                if (vtm < 0) { vEnd = true; continue; }
                if (atm < 0 || (vdur > 0 && atm > vdur)) { aEnd = true; continue; }
                boolean video = vtm <= atm;
                MediaExtractor x = video ? vx : ax;
                int size = x.readSampleData(buf, 0);
                if (size < 0) { if (video) vEnd = true; else aEnd = true; continue; }
                bi.set(0, size, video ? vtm : atm, (x.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                mux.writeSampleData(video ? ov : oa, buf, bi);
                x.advance();
                if (p != null && vdur > 0 && ++cnt % 64 == 0) p.on(0.7 + 0.3 * Math.min(1, Math.min(vtm, atm) / (double) vdur));
            }
            mux.stop();
            mux.release();
            mux = null;
            return publish(c, mp4, a.optString("name", "Video") + ".mp4");
        } finally {
            vx.release();
            if (ax != null) ax.release();
            if (mux != null) try { mux.release(); } catch (Exception ignored) { }
            if (wav != null) wav.delete();
            m4a.delete();
            mp4.delete();
        }
    }

    private static int rotation(Context c, Uri u) {
        MediaMetadataRetriever m = new MediaMetadataRetriever();
        try {
            m.setDataSource(c, u);
            String r = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            int v = r == null ? 0 : Integer.parseInt(r.trim());
            return v == 90 || v == 180 || v == 270 ? v : 0;
        } catch (Exception e) {
            return 0;
        } finally {
            try { m.release(); } catch (Exception ignored) { }
        }
    }

    /** Movies/Video Defteri on Android 10+, the app's own Movies folder on 7–9 */
    private static JSONObject publish(Context c, File from, String name) throws Exception {
        JSONObject src = new JSONObject();
        Uri u = null;
        File f = null;
        OutputStream out;
        if (MediaLib.scoped()) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + MediaLib.FOLDER);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            u = c.getContentResolver().insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
            if (u == null) throw new IllegalStateException("publish");
            out = c.getContentResolver().openOutputStream(u, "w");
            if (out == null) { c.getContentResolver().delete(u, null, null); throw new IllegalStateException("publish"); }
        } else {
            File base = c.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
            if (base == null) base = new File(c.getFilesDir(), "movies");
            File d = new File(base, MediaLib.FOLDER);
            if (!d.exists()) d.mkdirs();
            f = new File(d, name);
            out = new FileOutputStream(f);
        }
        InputStream in = new FileInputStream(from);
        try {
            byte[] b = new byte[128 * 1024];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            out.close();
        } catch (Exception e) {
            try { out.close(); } catch (Exception ignored) { }
            if (u != null) c.getContentResolver().delete(u, null, null);
            if (f != null) f.delete();
            throw e;
        } finally {
            in.close();
        }
        if (u != null) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            c.getContentResolver().update(u, v, null, null);
            src.put("uri", u.toString());
        } else {
            src.put("path", f.getAbsolutePath());
        }
        JSONObject o = new JSONObject();
        o.put("src", src);
        o.put("name", name);
        o.put("gallery", u != null);
        return o;
    }
}
