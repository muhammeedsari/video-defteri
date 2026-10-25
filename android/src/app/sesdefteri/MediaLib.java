package app.sesdefteri;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Phone audio library (MediaStore) and the app's own folder: Music/Video Defteri. */
final class MediaLib {
    static final String FOLDER = "Video Defteri";

    private MediaLib() { }

    static boolean scoped() {
        return Build.VERSION.SDK_INT >= 29;
    }

    static Uri audioCollection() {
        if (Build.VERSION.SDK_INT >= 29) return MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        return MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
    }

    /** Folder used for app files on Android 7–9, where writing to shared Music needs extra permissions. */
    static File ownDir(Context c) {
        File base = c.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (base == null) base = new File(c.getFilesDir(), "music");
        File d = new File(base, FOLDER);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /* ---------- listing ---------- */

    static JSONArray list(Context c, boolean withLibrary) {
        JSONArray out = new JSONArray();
        List<String> ownPaths = new ArrayList<>();
        if (!scoped()) {
            File[] files = ownDir(c).listFiles();
            if (files != null) for (File f : files) {
                if (!f.isFile() || f.getName().startsWith(".")) continue;
                try { out.put(fileItem(f)); ownPaths.add(f.getAbsolutePath()); } catch (JSONException ignored) { }
            }
        }
        if (!withLibrary && !scoped()) return out;
        query(c, null, null, out, ownPaths);
        return out;
    }

    static JSONObject one(Context c, long id) {
        JSONArray a = new JSONArray();
        query(c, MediaStore.Audio.Media._ID + "=?", new String[]{String.valueOf(id)}, a, new ArrayList<String>());
        try { return a.length() > 0 ? a.getJSONObject(0) : null; } catch (JSONException e) { return null; }
    }

    private static void query(Context c, String sel, String[] args, JSONArray out, List<String> skipPaths) {
        List<String> cols = new ArrayList<>();
        String[] base = {
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.IS_MUSIC,
            MediaStore.Audio.Media.DATA
        };
        for (String s : base) cols.add(s);
        if (Build.VERSION.SDK_INT >= 29) {
            cols.add(MediaStore.Audio.Media.RELATIVE_PATH);
            cols.add(MediaStore.Audio.Media.OWNER_PACKAGE_NAME);
        }
        if (Build.VERSION.SDK_INT >= 31) cols.add(MediaStore.Audio.Media.IS_RECORDING);
        String where = MediaStore.Audio.Media.IS_NOTIFICATION + "=0 AND " + MediaStore.Audio.Media.IS_RINGTONE + "=0 AND "
            + MediaStore.Audio.Media.IS_ALARM + "=0";
        if (sel != null) where = where + " AND " + sel;
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(audioCollection(), cols.toArray(new String[0]), where, args,
                MediaStore.Audio.Media.DATE_ADDED + " DESC");
            if (cur == null) return;
            String pkg = c.getPackageName();
            while (cur.moveToNext()) {
                try {
                    long id = cur.getLong(cur.getColumnIndexOrThrow(MediaStore.Audio.Media._ID));
                    long dur = cur.getLong(cur.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION));
                    String data = str(cur, MediaStore.Audio.Media.DATA);
                    if (data != null && skipPaths.contains(data)) continue;
                    String name = str(cur, MediaStore.Audio.Media.DISPLAY_NAME);
                    String rel = Build.VERSION.SDK_INT >= 29 ? str(cur, MediaStore.Audio.Media.RELATIVE_PATH) : null;
                    if (rel == null && data != null) {
                        File p = new File(data).getParentFile();
                        rel = p != null ? p.getName() + "/" : "";
                    }
                    String owner = Build.VERSION.SDK_INT >= 29 ? str(cur, MediaStore.Audio.Media.OWNER_PACKAGE_NAME) : null;
                    boolean own = pkg.equals(owner) || (rel != null && rel.contains(FOLDER));
                    boolean isRecFlag = Build.VERSION.SDK_INT >= 31 && cur.getInt(cur.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_RECORDING)) != 0;
                    boolean isMusic = cur.getInt(cur.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_MUSIC)) != 0;
                    String hay = ((rel == null ? "" : rel) + " " + (name == null ? "" : name)).toLowerCase(Locale.ROOT);
                    boolean rec = own || isRecFlag || !isMusic
                        || hay.contains("record") || hay.contains("voice") || hay.contains("kayıt") || hay.contains("kayit")
                        || hay.contains("sound_recorder") || hay.contains("call") || hay.contains("ptt-") || hay.contains("aud-");
                    JSONObject o = new JSONObject();
                    o.put("id", "m:" + id);
                    o.put("mediaId", id);
                    JSONObject src = new JSONObject();
                    src.put("uri", ContentUris.withAppendedId(audioCollection(), id).toString());
                    o.put("src", src);
                    o.put("title", str(cur, MediaStore.Audio.Media.TITLE));
                    String artist = str(cur, MediaStore.Audio.Media.ARTIST);
                    o.put("artist", artist == null || "<unknown>".equals(artist) ? "" : artist);
                    o.put("album", nz(str(cur, MediaStore.Audio.Media.ALBUM)));
                    o.put("albumId", cur.getLong(cur.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)));
                    o.put("dur", dur);
                    o.put("size", cur.getLong(cur.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)));
                    o.put("mime", nz(str(cur, MediaStore.Audio.Media.MIME_TYPE)));
                    long added = cur.getLong(cur.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED));
                    long mod = cur.getLong(cur.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED));
                    o.put("date", (mod > 0 ? Math.min(mod, added > 0 ? added : mod) : added) * 1000L);
                    o.put("folder", nz(rel));
                    o.put("name", nz(name));
                    o.put("music", isMusic && !own && !isRecFlag);
                    o.put("rec", rec);
                    o.put("own", own);
                    out.put(o);
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) {
        } finally {
            if (cur != null) cur.close();
        }
    }

    static JSONObject fileItem(File f) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", "f:" + f.getName());
        JSONObject src = new JSONObject();
        src.put("path", f.getAbsolutePath());
        o.put("src", src);
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        o.put("title", dot > 0 ? n.substring(0, dot) : n);
        o.put("artist", "");
        o.put("album", "");
        o.put("albumId", -1);
        o.put("dur", AudioTools.durationMs(null, f));
        o.put("size", f.length());
        o.put("mime", mimeForName(n));
        o.put("date", f.lastModified());
        o.put("folder", FOLDER + "/");
        o.put("name", n);
        o.put("music", false);
        o.put("rec", true);
        o.put("own", true);
        return o;
    }

    private static String str(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        if (i < 0 || c.isNull(i)) return null;
        return c.getString(i);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    static String mimeForName(String n) {
        String l = n.toLowerCase(Locale.ROOT);
        if (l.endsWith(".m4a") || l.endsWith(".mp4") || l.endsWith(".aac")) return "audio/mp4";
        if (l.endsWith(".mp3")) return "audio/mpeg";
        if (l.endsWith(".ogg") || l.endsWith(".opus") || l.endsWith(".oga")) return "audio/ogg";
        if (l.endsWith(".wav")) return "audio/x-wav";
        if (l.endsWith(".flac")) return "audio/flac";
        if (l.endsWith(".amr")) return "audio/amr";
        if (l.endsWith(".webm") || l.endsWith(".weba")) return "audio/webm";
        if (l.endsWith(".3gp")) return "audio/3gpp";
        return "audio/mpeg";
    }

    /* ---------- creating files in Music/Video Defteri ---------- */

    /** A new, still-hidden file. Call {@link #publish} when its bytes are complete. */
    static final class Target {
        Uri uri;      // Android 10+
        File file;    // Android 7–9
        String name;
    }

    /** a ringtone: Ringtones/ on Android 10+, flagged so the system sound picker lists it */
    static Target createRingtone(Context c, String displayName, String mime) throws IOException {
        if (!scoped()) return create(c, displayName, mime);
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_RINGTONES);
        v.put(MediaStore.Audio.Media.IS_RINGTONE, 1);
        v.put(MediaStore.Audio.Media.IS_MUSIC, 0);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri u = c.getContentResolver().insert(audioCollection(), v);
        if (u == null) throw new IOException("insert failed");
        Target t = new Target();
        t.name = displayName;
        t.uri = u;
        return t;
    }

    static Target create(Context c, String displayName, String mime) throws IOException {
        Target t = new Target();
        t.name = displayName;
        if (scoped()) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/" + FOLDER);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri u = null;
            try { u = c.getContentResolver().insert(audioCollection(), v); } catch (Exception ignored) { }
            if (u == null) {
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
                u = c.getContentResolver().insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
            }
            if (u == null) throw new IOException("insert failed");
            t.uri = u;
        } else {
            File f = new File(ownDir(c), displayName);
            int i = 2;
            while (f.exists()) {
                int dot = displayName.lastIndexOf('.');
                String stem = dot > 0 ? displayName.substring(0, dot) : displayName;
                String ext = dot > 0 ? displayName.substring(dot) : "";
                f = new File(ownDir(c), stem + " (" + (i++) + ")" + ext);
            }
            t.file = f;
            t.name = f.getName();
        }
        return t;
    }

    static OutputStream open(Context c, Target t) throws IOException {
        if (t.uri != null) {
            OutputStream o = c.getContentResolver().openOutputStream(t.uri, "w");
            if (o == null) throw new IOException("open failed");
            return o;
        }
        return new FileOutputStream(t.file);
    }

    static JSONObject publish(Context c, Target t) {
        try {
            if (t.uri != null) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                c.getContentResolver().update(t.uri, v, null, null);
                long id = ContentUris.parseId(t.uri);
                JSONObject o = one(c, id);
                if (o != null) return o;
                // Downloads fallback or not yet indexed: minimal item
                JSONObject m = new JSONObject();
                m.put("id", "m:" + id);
                m.put("mediaId", id);
                JSONObject src = new JSONObject();
                src.put("uri", t.uri.toString());
                m.put("src", src);
                m.put("title", t.name.replaceAll("\\.[^.]+$", ""));
                m.put("artist", ""); m.put("album", ""); m.put("albumId", -1);
                m.put("dur", AudioTools.durationMs(c.getApplicationContext(), t.uri));
                m.put("size", 0); m.put("mime", mimeForName(t.name)); m.put("date", System.currentTimeMillis());
                m.put("folder", FOLDER + "/"); m.put("name", t.name); m.put("music", false); m.put("rec", true); m.put("own", true);
                return m;
            }
            return fileItem(t.file);
        } catch (Exception e) {
            return null;
        }
    }

    static void discard(Context c, Target t) {
        try {
            if (t.uri != null) c.getContentResolver().delete(t.uri, null, null);
            else if (t.file != null) t.file.delete();
        } catch (Exception ignored) { }
    }

    /** Copies any readable audio (shared in, picked, or a temp file) into Music/Video Defteri. */
    static JSONObject copyIn(Context c, Uri from, File fromFile, String displayName, String mime) throws IOException {
        if (displayName == null || displayName.isEmpty()) displayName = "Ses " + System.currentTimeMillis() + ".m4a";
        if (mime == null || mime.isEmpty() || !mime.startsWith("audio/")) mime = mimeForName(displayName);
        Target t = create(c, displayName, mime);
        InputStream in = null;
        OutputStream out = null;
        try {
            in = from != null ? c.getContentResolver().openInputStream(from) : new FileInputStream(fromFile);
            if (in == null) throw new IOException("read failed");
            out = open(c, t);
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close(); out = null;
            JSONObject o = publish(c, t);
            if (o == null) throw new IOException("publish failed");
            return o;
        } catch (IOException e) {
            discard(c, t);
            throw e;
        } finally {
            try { if (in != null) in.close(); } catch (IOException ignored) { }
            try { if (out != null) out.close(); } catch (IOException ignored) { }
        }
    }

    /** Fills a target created with {@link #create} from a finished temp file, then publishes it. */
    static JSONObject copyInTarget(Context c, Target t, File from) throws IOException {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(from);
            out = open(c, t);
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close(); out = null;
            return publish(c, t);
        } finally {
            try { if (in != null) in.close(); } catch (IOException ignored) { }
            try { if (out != null) out.close(); } catch (IOException ignored) { }
        }
    }

    static final String ORIGINALS = "Orijinaller";

    /**
     * Moves one of the app's own files into Music/Video Defteri/Orijinaller (kept for "undo"), or back.
     * Returns the new src, or null when the system does not allow it (the file then stays where it is).
     */
    static JSONObject moveOwn(Context c, JSONObject src, boolean toOriginals) {
        try {
            Uri u = srcUri(src);
            if (u != null) {
                if (Build.VERSION.SDK_INT < 29) return null;
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/" + FOLDER + (toOriginals ? "/" + ORIGINALS : ""));
                if (c.getContentResolver().update(u, v, null, null) <= 0) return null;
                return src;
            }
            File f = srcFile(src);
            if (f == null || !f.exists()) return null;
            File dir = toOriginals ? new File(ownDir(c), ORIGINALS) : ownDir(c);
            if (!dir.exists()) dir.mkdirs();
            File to = new File(dir, f.getName());
            int i = 2;
            while (to.exists()) {
                String n = f.getName(); int dot = n.lastIndexOf('.');
                to = new File(dir, (dot > 0 ? n.substring(0, dot) : n) + " (" + (i++) + ")" + (dot > 0 ? n.substring(dot) : ""));
            }
            if (!f.renameTo(to)) return null;
            JSONObject o = new JSONObject();
            o.put("path", to.getAbsolutePath());
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    /** item JSON for a src (after moving it back) */
    static JSONObject itemFor(Context c, JSONObject src) {
        try {
            Uri u = srcUri(src);
            if (u != null) return one(c, ContentUris.parseId(u));
            File f = srcFile(src);
            return f != null && f.exists() ? fileItem(f) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Deletes a file the app created. Returns false when the system must ask the user first. */
    static boolean deleteOwn(Context c, JSONObject src) {
        try {
            Uri u = srcUri(src);
            if (u != null) return c.getContentResolver().delete(u, null, null) > 0;
            File f = srcFile(src);
            return f != null && f.delete();
        } catch (SecurityException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    static String displayName(ContentResolver cr, Uri u) {
        String name = null;
        Cursor cur = null;
        try {
            cur = cr.query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cur != null && cur.moveToFirst()) name = cur.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        if (name == null) name = u.getLastPathSegment();
        return name != null ? name : "ses";
    }

    /** title / artist in the media library (what other players show) */
    static void tags(Context c, JSONObject item, String title, String artist) {
        try {
            JSONObject src = item.optJSONObject("src");
            Uri u = src != null ? srcUri(src) : null;
            if (u == null) return;
            ContentValues v = new ContentValues();
            if (!title.isEmpty()) v.put(MediaStore.Audio.Media.TITLE, title);
            if (!artist.isEmpty()) v.put(MediaStore.Audio.Media.ARTIST, artist);
            if (v.size() > 0) c.getContentResolver().update(u, v, null, null);
        } catch (Exception ignored) { }
    }

    static Uri srcUri(JSONObject src) {
        String u = src.optString("uri", "");
        return u.isEmpty() ? null : Uri.parse(u);
    }

    static File srcFile(JSONObject src) {
        String p = src.optString("path", "");
        return p.isEmpty() ? null : new File(p);
    }
}
