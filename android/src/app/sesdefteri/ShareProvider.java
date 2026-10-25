package app.sesdefteri;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Hands a prepared file in cache/share to the app the user picked in the share sheet (read-only). */
public class ShareProvider extends ContentProvider {
    static final String AUTHORITY = "app.videodefteri.share";

    @Override
    public boolean onCreate() {
        return true;
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null) throw new FileNotFoundException();
        File dir = new File(getContext().getCacheDir(), "share");
        File f = new File(dir, name);
        try {
            if (!f.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)) throw new FileNotFoundException();
        } catch (IOException e) {
            throw new FileNotFoundException();
        }
        if (!f.exists()) throw new FileNotFoundException();
        return f;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File f;
        try { f = fileFor(uri); } catch (FileNotFoundException e) { return null; }
        String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
            else row[i] = null;
        }
        c.addRow(row);
        return c;
    }

    @Override
    public String getType(Uri uri) {
        String n = uri.getLastPathSegment();
        if (n == null) return "application/octet-stream";
        n = n.toLowerCase();
        if (n.endsWith(".webm")) return "audio/webm";
        if (n.endsWith(".m4a") || n.endsWith(".mp4")) return "audio/mp4";
        if (n.endsWith(".ogg") || n.endsWith(".opus")) return "audio/ogg";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".aac")) return "audio/aac";
        if (n.endsWith(".flac")) return "audio/flac";
        return "application/octet-stream";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }

    @Override
    public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
