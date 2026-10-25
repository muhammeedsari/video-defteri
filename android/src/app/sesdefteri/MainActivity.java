package app.sesdefteri;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.app.RecoverableSecurityException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity implements Player.Listener {

    /* The app's pages are served from this https origin out of the APK's assets, so the
       WebView treats them as a secure context with no network involved. */
    static final String HOST = "appassets.androidplatform.net";
    static final String START = "https://" + HOST + "/index.html";
    private static final int REQ_MIC = 11;
    private static final int REQ_FILE = 12;
    private static final int REQ_MEDIA = 13;
    private static final int REQ_PICK = 14;
    private static final int REQ_DELETE = 15;
    private static final int REQ_VIDEO = 16;
    private volatile String videoBusy;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingPermission;
    private boolean pageReady = false;
    private String barMode = "page";
    private String pendingDeleteJob;

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    /* enhancement, speech-to-text, mixing: one at a time, apart from waveform work */
    private final ExecutorService studioPool = Executors.newSingleThreadExecutor();
    /* background waveform work runs on its own low-priority thread so it never holds up what is on screen */
    private final ExecutorService bgPool = Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
        @Override public Thread newThread(Runnable r) { Thread t = new Thread(r, "sd-bg"); t.setPriority(Thread.MIN_PRIORITY); return t; }
    });
    private final Map<String, AtomicBoolean> jobs = new ConcurrentHashMap<>();
    private final List<String> importedQueue = Collections.synchronizedList(new ArrayList<String>());
    private final Map<String, OutputStream> openFiles = new ConcurrentHashMap<>();
    private final Map<String, File> openFilePaths = new ConcurrentHashMap<>();
    private final Map<String, String> openFileMimes = new ConcurrentHashMap<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Player.I.init(this, this);
        web = new WebView(this);
        web.setBackgroundColor(isDark() ? 0xFF0F1412 : 0xFFEDF1EE);
        setContentView(web);
        applyBars("page");

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);
        s.setSupportZoom(false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return serve(req.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                pushImported();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() { handleWebPermission(request); }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("audio/*");
                if (params != null && params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(Intent.createChooser(pick, Str.get(MainActivity.this, "pick")), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    callback.onReceiveValue(null);
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "SesDefteriAndroid");
        handleIntent(getIntent());
        web.loadUrl(START);
    }

    /* ---------- JS callbacks ---------- */

    private void js(final String code) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() { if (web != null && pageReady) web.evaluateJavascript(code, null); }
        });
    }

    private static String q(String s) {
        return JSONObject.quote(s == null ? "" : s);
    }

    @Override
    public void onPlayerEvent(String json) {
        js("window.__sdPlayer && window.__sdPlayer(" + json + ")");
    }

    private void jobDone(String id, boolean ok, String resultJson) {
        jobs.remove(id);
        js("window.__sdJob && window.__sdJob(" + q(id) + "," + ok + "," + (resultJson == null ? "null" : resultJson) + ")");
    }

    private void jobChunk(String id, int from, byte[] data) {
        js("window.__sdJobChunk && window.__sdJobChunk(" + q(id) + "," + from + ",'" + android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP) + "')");
    }

    private void jobProgress(String id, double f) {
        js("window.__sdJobProgress && window.__sdJobProgress(" + q(id) + "," + f + ")");
    }

    /* ---------- asset server ---------- */

    private WebResourceResponse serve(Uri u) {
        if (u == null || !HOST.equals(u.getHost())) return null;
        String path = u.getPath();
        if (path == null || path.equals("/") || path.isEmpty()) path = "/index.html";
        Map<String, String> headers = new HashMap<>();
        if (path.startsWith("/__art/")) {
            try {
                long albumId = Long.parseLong(path.substring("/__art/".length()));
                byte[] jpg = AudioTools.albumArtJpeg(this, albumId);
                if (jpg == null) return notFound();
                headers.put("Cache-Control", "max-age=86400");
                return new WebResourceResponse("image/jpeg", null, 200, "OK", headers, new ByteArrayInputStream(jpg));
            } catch (Exception e) {
                return notFound();
            }
        }
        headers.put("Cache-Control", "no-cache");
        if (path.contains("..")) return notFound();
        try {
            InputStream in = getAssets().open("www" + path);
            String mime = mimeFor(path);
            String enc = mime.startsWith("text/") || mime.endsWith("javascript") || mime.endsWith("json") ? "utf-8" : null;
            return new WebResourceResponse(mime, enc, 200, "OK", headers, in);
        } catch (IOException e) {
            return notFound();
        }
    }

    private WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", new HashMap<String, String>(), null);
    }

    private static String mimeFor(String p) {
        String l = p.toLowerCase();
        if (l.endsWith(".html")) return "text/html";
        if (l.endsWith(".js")) return "text/javascript";
        if (l.endsWith(".css")) return "text/css";
        if (l.endsWith(".json")) return "application/json";
        if (l.endsWith(".woff2")) return "font/woff2";
        if (l.endsWith(".png")) return "image/png";
        if (l.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    /* ---------- permissions ---------- */

    private boolean has(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private String mediaPermission() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private void handleWebPermission(PermissionRequest request) {
        boolean audio = false;
        for (String r : request.getResources()) if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) audio = true;
        Uri origin = request.getOrigin();
        if (!audio || origin == null || !HOST.equals(origin.getHost())) { request.deny(); return; }
        if (has(Manifest.permission.RECORD_AUDIO)) { request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE}); return; }
        if (pendingPermission != null) pendingPermission.deny();
        pendingPermission = request;
        requestMic();
    }

    private void requestMic() {
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}, REQ_MIC);
        else requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_MIC) {
            boolean ok = has(Manifest.permission.RECORD_AUDIO);
            if (pendingPermission != null) {
                if (ok) pendingPermission.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                else pendingPermission.deny();
                pendingPermission = null;
            }
            js("window.__sdPerm && window.__sdPerm('mic'," + ok + ")");
        } else if (code == REQ_MEDIA) {
            boolean ok = has(mediaPermission());
            boolean blocked = !ok && !shouldShowRequestPermissionRationale(mediaPermission());
            js("window.__sdPerm && window.__sdPerm('media'," + ok + "," + blocked + ")");
        }
    }

    /* ---------- activity results ---------- */

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == REQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(result == RESULT_OK ? urisFrom(data) : null);
            fileCallback = null;
        } else if (code == REQ_VIDEO) {
            Uri[] uris = result == RESULT_OK ? urisFrom(data) : null;
            if (uris != null && uris.length > 0) importVideos(uris);
        } else if (code == REQ_PICK) {
            Uri[] uris = result == RESULT_OK ? urisFrom(data) : null;
            if (uris != null && uris.length > 0) importUris(uris, true);
        } else if (code == REQ_DELETE && pendingDeleteJob != null) {
            String id = pendingDeleteJob;
            pendingDeleteJob = null;
            jobDone(id, result == RESULT_OK, result == RESULT_OK ? "{\"deleted\":true}" : "{\"deleted\":false}");
        }
    }

    private Uri[] urisFrom(Intent data) {
        if (data == null) return null;
        ClipData clip = data.getClipData();
        if (clip != null && clip.getItemCount() > 0) {
            Uri[] uris = new Uri[clip.getItemCount()];
            for (int i = 0; i < clip.getItemCount(); i++) uris[i] = clip.getItemAt(i).getUri();
            return uris;
        }
        return data.getData() != null ? new Uri[]{data.getData()} : null;
    }

    /* ---------- audio shared into the app or picked: copied into Music/Video Defteri ---------- */

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        List<Uri> uris = new ArrayList<>();
        String action = intent.getAction();
        try {
            if (Intent.ACTION_SEND.equals(action)) {
                Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (u != null) uris.add(u);
                else if (intent.getClipData() != null && intent.getClipData().getItemCount() > 0) uris.add(intent.getClipData().getItemAt(0).getUri());
            } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
                ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (list != null) uris.addAll(list);
            } else if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
                uris.add(intent.getData());
            }
        } catch (Exception ignored) { }
        intent.setAction(null);
        if (!uris.isEmpty()) importUris(uris.toArray(new Uri[0]), false);
    }

    private void importUris(final Uri[] uris, final boolean picked) {
        js("window.__sdBusy && window.__sdBusy(" + q(uris.length > 1 ? uris.length + " dosya ekleniyor…" : "Dosya ekleniyor…") + ")");
        pool.execute(new Runnable() {
            @Override
            public void run() {
                JSONArray items = new JSONArray();
                int failed = 0;
                for (Uri u : uris) {
                    try {
                        String name = MediaLib.displayName(getContentResolver(), u);
                        String mime = getContentResolver().getType(u);
                        items.put(MediaLib.copyIn(MainActivity.this, u, null, name, mime));
                    } catch (Exception e) {
                        failed++;
                    }
                }
                String payload = "{\"items\":" + items.toString() + ",\"failed\":" + failed + ",\"picked\":" + picked + "}";
                importedQueue.add(payload);
                pushImported();
            }
        });
    }

    /** the sound of picked videos becomes new recordings (AAC in Music/Video Defteri) */
    private void importVideos(final Uri[] uris) {
        js("window.__sdBusy && window.__sdBusy(" + q(videoBusy == null ? "…" : videoBusy) + ")");
        studioPool.execute(new Runnable() {
            @Override
            public void run() {
                JSONArray items = new JSONArray();
                int failed = 0;
                for (Uri u : uris) {
                    try {
                        String name = MediaLib.displayName(getContentResolver(), u);
                        int dot = name == null ? -1 : name.lastIndexOf('.');
                        String stem = name == null ? "Video" : dot > 0 ? name.substring(0, dot) : name;
                        JSONObject src = new JSONObject();
                        src.put("uri", u.toString());
                        JSONArray keep = new JSONArray().put(new JSONArray().put(0).put(Long.MAX_VALUE / 4));
                        items.put(AudioTools.render(MainActivity.this, src, keep, safeFileName(stem) + ".m4a", null, null));
                    } catch (Exception e) {
                        failed++;
                    }
                }
                importedQueue.add("{\"items\":" + items.toString() + ",\"failed\":" + failed + ",\"picked\":true}");
                ui(new Runnable() { @Override public void run() { pushImported(); } });
            }
        });
    }

    private void pushImported() {
        if (!pageReady || importedQueue.isEmpty()) return;
        js("window.__sdImported && window.__sdImported()");
    }

    /* ---------- theme and system bars ---------- */

    private boolean systemDark() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /** "system", "light" or "dark", chosen in the app's settings. */
    private String themePref() {
        return Str.prefs(this).getString("theme", "system");
    }

    private boolean isDark() {
        String t = themePref();
        if ("dark".equals(t)) return true;
        if ("light".equals(t)) return false;
        return systemDark();
    }

    private void applyBars(String mode) {
        boolean dark = isDark();
        boolean stage = "stage".equals(mode);
        int color = stage ? (dark ? 0xFF0A100E : 0xFF111C18) : (dark ? 0xFF0F1412 : 0xFFEDF1EE);
        boolean lightIcons = stage || dark;
        Window w = getWindow();
        w.setStatusBarColor(color);
        w.setNavigationBarColor(color);
        View d = w.getDecorView();
        int flags = d.getSystemUiVisibility();
        if (lightIcons) {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        d.setSystemUiVisibility(flags);
    }

    @Override
    public void onConfigurationChanged(Configuration cfg) {
        super.onConfigurationChanged(cfg);
        boolean dark = isDark();
        web.setBackgroundColor(dark ? 0xFF0F1412 : 0xFFEDF1EE);
        applyBars(barMode);
        js("window.__sdTheme && window.__sdTheme('" + (dark ? "dark" : "light") + "')");
    }

    @Override
    protected void onResume() {
        super.onResume();
        js("window.__sdResume && window.__sdResume()");
    }

    /* ---------- back button ---------- */

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        Player.I.init(this, null);
        Timeline.stop();
        if (web != null) {
            web.removeJavascriptInterface("SesDefteriAndroid");
            web.destroy();
            web = null;
        }
        if (!Recorder.active()) { pool.shutdown(); bgPool.shutdownNow(); studioPool.shutdown(); }
        super.onDestroy();
    }

    /* ---------- JavaScript bridge ---------- */

    private static String safeFileName(String n) {
        String s = n == null ? "kayit" : n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", " ").trim();
        if (s.isEmpty()) s = "kayit";
        if (s.length() > 120) s = s.substring(s.length() - 120);
        return s;
    }

    private void ui(Runnable r) {
        runOnUiThread(r);
    }

    class Bridge {
        /* -- environment -- */
        @JavascriptInterface public boolean isDark() { return MainActivity.this.isDark(); }

        @JavascriptInterface public String caps() {
            JSONObject o = new JSONObject();
            try {
                o.put("native", true);
                o.put("sdk", Build.VERSION.SDK_INT);
                o.put("media", has(mediaPermission()));
                o.put("mic", has(Manifest.permission.RECORD_AUDIO));
                o.put("asr", Studio.asrAvailable());
                o.put("sep", Separator.available());
                o.put("mp3", Mp3.available());
                o.put("folder", MediaLib.scoped() ? "Music/Video Defteri" : "Android/data/app.videodefteri/files/Music/Video Defteri");
            } catch (Exception ignored) { }
            return o.toString();
        }

        @JavascriptInterface public String themePref() { return MainActivity.this.themePref(); }

        @JavascriptInterface public void setTheme(final String mode) {
            Str.prefs(MainActivity.this).edit().putString("theme", mode).apply();
            ui(new Runnable() {
                @Override public void run() {
                    web.setBackgroundColor(MainActivity.this.isDark() ? 0xFF0F1412 : 0xFFEDF1EE);
                    applyBars(barMode);
                }
            });
        }

        @JavascriptInterface public String getLang() { return Str.prefs(MainActivity.this).getString("lang", ""); }

        @JavascriptInterface public void setLang(String code) { Str.prefs(MainActivity.this).edit().putString("lang", code).apply(); }

        @JavascriptInterface public void setBars(final String mode) {
            ui(new Runnable() { @Override public void run() { barMode = mode; applyBars(mode); } });
        }

        @JavascriptInterface public void copyText(final String text) {
            ui(new Runnable() {
                @Override public void run() {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Video Defteri", text));
                }
            });
        }

        @JavascriptInterface public void requestMedia() {
            ui(new Runnable() {
                @Override public void run() {
                    if (has(mediaPermission())) { js("window.__sdPerm && window.__sdPerm('media',true,false)"); return; }
                    requestPermissions(new String[]{mediaPermission()}, REQ_MEDIA);
                }
            });
        }

        @JavascriptInterface public void requestMic() {
            ui(new Runnable() {
                @Override public void run() {
                    if (has(Manifest.permission.RECORD_AUDIO)) { js("window.__sdPerm && window.__sdPerm('mic',true)"); return; }
                    MainActivity.this.requestMic();
                }
            });
        }

        @JavascriptInterface public void openSettings() {
            ui(new Runnable() {
                @Override public void run() {
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
                    } catch (Exception ignored) { }
                }
            });
        }

        /* -- library -- */
        @JavascriptInterface public String listMedia() {
            return MediaLib.list(MainActivity.this, has(mediaPermission())).toString();
        }

        @JavascriptInterface public void pickAudio() {
            ui(new Runnable() {
                @Override public void run() {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("audio/*");
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    try { startActivityForResult(i, REQ_PICK); } catch (Exception ignored) { }
                }
            });
        }

        @JavascriptInterface public void pickVideo(final String busy) {
            videoBusy = busy;
            ui(new Runnable() {
                @Override public void run() {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("video/*");
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    try { startActivityForResult(i, REQ_VIDEO); } catch (Exception ignored) { }
                }
            });
        }

        @JavascriptInterface public void openSoundSettings() {
            ui(new Runnable() {
                @Override public void run() {
                    try { startActivity(new Intent(android.provider.Settings.ACTION_SOUND_SETTINGS)); } catch (Exception ignored) { }
                }
            });
        }

        @JavascriptInterface public void vibrate(int ms) {
            try {
                android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (v == null) return;
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(android.os.VibrationEffect.createOneShot(Math.max(5, ms), 60));
                else v.vibrate(Math.max(5, ms));
            } catch (Exception ignored) { }
        }

        @JavascriptInterface public String takeImported() {
            JSONArray a = new JSONArray();
            synchronized (importedQueue) {
                for (String s : importedQueue) { try { a.put(new JSONObject(s)); } catch (Exception ignored) { } }
                importedQueue.clear();
            }
            return a.toString();
        }

        @JavascriptInterface public void share(final String srcJson, final String title, final String mime) {
            ui(new Runnable() {
                @Override public void run() {
                    try {
                        JSONObject src = new JSONObject(srcJson);
                        Uri uri = MediaLib.srcUri(src);
                        if (uri == null) {
                            File f = MediaLib.srcFile(src);
                            if (f == null) return;
                            File dir = new File(getCacheDir(), "share");
                            dir.mkdirs();
                            File copy = new File(dir, f.getName());
                            java.io.FileInputStream in = new java.io.FileInputStream(f);
                            FileOutputStream out = new FileOutputStream(copy);
                            byte[] b = new byte[65536]; int n;
                            while ((n = in.read(b)) > 0) out.write(b, 0, n);
                            in.close(); out.close();
                            uri = new Uri.Builder().scheme("content").authority(ShareProvider.AUTHORITY).appendPath(copy.getName()).build();
                        }
                        Intent send = new Intent(Intent.ACTION_SEND);
                        send.setType(mime == null || mime.isEmpty() ? "audio/*" : mime);
                        send.putExtra(Intent.EXTRA_STREAM, uri);
                        send.putExtra(Intent.EXTRA_TITLE, title);
                        send.setClipData(ClipData.newRawUri(title, uri));
                        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(send, Str.get(MainActivity.this, "share")));
                    } catch (Exception ignored) { }
                }
            });
        }

        /** Shares a small text file (subtitles, transcript). */
        @JavascriptInterface public void shareText(final String name, final String text, final String mime) {
            ui(new Runnable() {
                @Override public void run() {
                    try {
                        File dir = new File(getCacheDir(), "share");
                        dir.mkdirs();
                        File f = new File(dir, Studio.safe(name));
                        FileOutputStream out = new FileOutputStream(f);
                        out.write(text.getBytes("UTF-8"));
                        out.close();
                        Uri uri = new Uri.Builder().scheme("content").authority(ShareProvider.AUTHORITY).appendPath(f.getName()).build();
                        Intent send = new Intent(Intent.ACTION_SEND);
                        send.setType(mime == null || mime.isEmpty() ? "text/plain" : mime);
                        send.putExtra(Intent.EXTRA_STREAM, uri);
                        send.putExtra(Intent.EXTRA_TITLE, f.getName());
                        send.setClipData(ClipData.newRawUri(f.getName(), uri));
                        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(send, Str.get(MainActivity.this, "share")));
                    } catch (Exception ignored) { }
                }
            });
        }

        /** Deletes a file. Files from other apps need the system's confirmation (Android 11+). */
        @JavascriptInterface public String deleteMedia(final String srcJson) {
            final String id = UUID.randomUUID().toString();
            ui(new Runnable() {
                @Override public void run() {
                    try {
                        JSONObject src = new JSONObject(srcJson);
                        Uri u = MediaLib.srcUri(src);
                        Player.I.releaseIfCurrentSrc(srcJson);
                        if (MediaLib.deleteOwn(MainActivity.this, src)) { jobDone(id, true, "{\"deleted\":true}"); return; }
                        if (u != null && Build.VERSION.SDK_INT >= 30) {
                            List<Uri> l = new ArrayList<>();
                            l.add(u);
                            PendingIntent pi = MediaStore.createDeleteRequest(getContentResolver(), l);
                            pendingDeleteJob = id;
                            startIntentSenderForResult(pi.getIntentSender(), REQ_DELETE, null, 0, 0, 0);
                            return;
                        }
                        if (u != null && Build.VERSION.SDK_INT == 29) {
                            try {
                                getContentResolver().delete(u, null, null);
                                jobDone(id, true, "{\"deleted\":true}");
                            } catch (RecoverableSecurityException rse) {
                                pendingDeleteJob = id;
                                IntentSender is = rse.getUserAction().getActionIntent().getIntentSender();
                                startIntentSenderForResult(is, REQ_DELETE, null, 0, 0, 0);
                            }
                            return;
                        }
                        jobDone(id, false, "{\"deleted\":false,\"reason\":\"denied\"}");
                    } catch (Exception e) {
                        jobDone(id, false, "{\"deleted\":false}");
                    }
                }
            });
            return id;
        }

        /* -- recorder -- */
        @JavascriptInterface public String recStart(String mode, String name, String opts) {
            if (!has(Manifest.permission.RECORD_AUDIO)) return "{\"ok\":false,\"error\":\"perm\"}";
            String res = Recorder.start(MainActivity.this, mode, safeFileName(name), opts);
            ui(new Runnable() { @Override public void run() { getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); } });
            return res;
        }
        @JavascriptInterface public boolean recPause() { return Recorder.pause(); }
        @JavascriptInterface public boolean recResume() { return Recorder.resumeManual(); }
        @JavascriptInterface public String recInfo() { return Recorder.info(); }
        @JavascriptInterface public float recLevel() { return Recorder.level(); }
        @JavascriptInterface public String recStop() {
            ui(new Runnable() { @Override public void run() { getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); } });
            return Recorder.stop(MainActivity.this);
        }
        @JavascriptInterface public void recCancel() {
            ui(new Runnable() { @Override public void run() { getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); } });
            Recorder.cancel(MainActivity.this);
        }

        /* -- player -- */
        @JavascriptInterface public void plQueue(final String json, final int index, final double startMs, final boolean autoplay) {
            ui(new Runnable() { @Override public void run() { Player.I.setQueue(json, index, (long) startMs, autoplay); } });
        }
        @JavascriptInterface public void plEnqueue(final String json, final boolean next) { ui(new Runnable() { @Override public void run() { Player.I.enqueue(json, next); } }); }
        @JavascriptInterface public void openUrl(final String url) {
            if (url == null || !url.startsWith("https://")) return;
            ui(new Runnable() { @Override public void run() {
                try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))); } catch (Exception ignored) { }
            } });
        }
        @JavascriptInterface public void plPlay() { ui(new Runnable() { @Override public void run() { Player.I.play(); } }); }
        @JavascriptInterface public void plPause() { ui(new Runnable() { @Override public void run() { Player.I.pause(); } }); }
        @JavascriptInterface public void plSeek(final double ms) { ui(new Runnable() { @Override public void run() { Player.I.seek((long) ms); } }); }
        @JavascriptInterface public void plRate(final double r) { ui(new Runnable() { @Override public void run() { Player.I.setRate((float) r); } }); }
        @JavascriptInterface public void plNext() { ui(new Runnable() { @Override public void run() { Player.I.next(); } }); }
        @JavascriptInterface public void plPrev() { ui(new Runnable() { @Override public void run() { Player.I.prev(); } }); }
        @JavascriptInterface public void plShuffle(final boolean on) { ui(new Runnable() { @Override public void run() { Player.I.setShuffle(on); } }); }
        @JavascriptInterface public void plRepeat(final int r) { ui(new Runnable() { @Override public void run() { Player.I.setRepeat(r); } }); }
        @JavascriptInterface public void plStop() { ui(new Runnable() { @Override public void run() { Player.I.stop(); } }); }
        @JavascriptInterface public String plState() { return Player.I.state(); }
        @JavascriptInterface public void plGain(final String id, final double db) { ui(new Runnable() { @Override public void run() { Player.I.setGain(id, db); } }); }
        @JavascriptInterface public void plEq(final String json) { ui(new Runnable() { @Override public void run() { Player.I.setEq(json); } }); }
        @JavascriptInterface public void plXfade(final int ms, final boolean gapless) { ui(new Runnable() { @Override public void run() { Player.I.setXfade(ms, gapless); } }); }
        @JavascriptInterface public void plLoop(final double a, final double b) { ui(new Runnable() { @Override public void run() { Player.I.setLoop((long) a, (long) b); } }); }
        @JavascriptInterface public void plSkips(final String id, final String json) { ui(new Runnable() { @Override public void run() { Player.I.setSkips(id, json); } }); }
        @JavascriptInterface public void plSleep(final double ms, final boolean end) { ui(new Runnable() { @Override public void run() { Player.I.setSleep((long) ms, end); } }); }
        @JavascriptInterface public String tlPlay(String json, double sec) { try { Timeline.play(json, sec); return ""; } catch (Exception e) { return String.valueOf(e.getMessage()); } }
        @JavascriptInterface public void tlUpdate(String json) { try { Timeline.update(json); } catch (Exception ignored) { } }
        @JavascriptInterface public void tlStop() { Timeline.stop(); }
        @JavascriptInterface public double tlPos() { return Timeline.pos(); }

        /* -- background jobs: waveform, editing, saving -- */
        @JavascriptInterface public String job(final String kind, final String argsJson) {
            final String id = UUID.randomUUID().toString();
            final AtomicBoolean cancel = new AtomicBoolean(false);
            jobs.put(id, cancel);
            boolean bg = false;
            try { bg = new JSONObject(argsJson).optBoolean("bg", false); } catch (Exception ignored) { }
            final boolean heavy = !"peaks".equals(kind) && !"commit".equals(kind) && !"wsClean".equals(kind) && !"tlClean".equals(kind) && !"roomTest".equals(kind) && !"loudness".equals(kind) && !"asrStatus".equals(kind) && !"asrDelete".equals(kind);
            if (heavy) WorkService.begin(MainActivity.this);
            (bg ? bgPool : heavy || "wsClean".equals(kind) || "tlClean".equals(kind) ? studioPool : pool).execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        JSONObject a = new JSONObject(argsJson);
                        AudioTools.Progress p = new AudioTools.Progress() {
                            @Override public void on(double f) { jobProgress(id, f); }
                        };
                        JSONObject res;
                        if ("peaks".equals(kind)) {
                            AudioTools.Chunks ch = new AudioTools.Chunks() {
                                @Override public void on(int from, byte[] data) { jobChunk(id, from, data); }
                            };
                            res = AudioTools.peaks(MainActivity.this, a.getJSONObject("src"), a.optLong("startMs", 0), p, ch, cancel);
                        } else if ("commit".equals(kind)) {
                            // a file the page assembled with beginFile/appendFile → Music/Video Defteri (re-encoded to .m4a)
                            String tok = a.getString("token");
                            File f = closeFile(tok);
                            if (f == null) throw new IllegalStateException("no file");
                            try {
                                JSONObject src = new JSONObject();
                                src.put("path", f.getAbsolutePath());
                                long ms = AudioTools.durationMs(MainActivity.this, f);
                                JSONArray keep = new JSONArray();
                                JSONArray all = new JSONArray();
                                all.put(0); all.put(ms > 0 ? ms : Long.MAX_VALUE / 4);
                                keep.put(all);
                                try {
                                    res = AudioTools.render(MainActivity.this, src, keep, safeFileName(a.getString("name")) + ".m4a", p, cancel);
                                } catch (Exception re) {
                                    res = MediaLib.copyIn(MainActivity.this, null, f, safeFileName(a.getString("name")) + "." + a.optString("ext", "webm"), a.optString("mime", ""));
                                }
                            } finally {
                                f.delete();
                            }
                        } else if ("wsInit".equals(kind)) {
                            res = Workspace.init(MainActivity.this, a.getJSONObject("src"), p, cancel);
                        } else if ("wsOp".equals(kind)) {
                            res = Workspace.op(MainActivity.this, a, p, cancel);
                        } else if ("tlAdd".equals(kind)) {
                            res = Timeline.add(MainActivity.this, a, p, cancel);
                        } else if ("fillers".equals(kind)) {
                            res = Workspace.fillers(MainActivity.this, a, p, cancel);
                        } else if ("keepRender".equals(kind)) {
                            res = Workspace.keepRender(MainActivity.this, a, p, cancel);
                        } else if ("asrDownload".equals(kind)) {
                            res = AsrModels.download(MainActivity.this, a, p, cancel);
                        } else if ("asrDelete".equals(kind)) {
                            res = AsrModels.delete(MainActivity.this, a);
                        } else if ("asrStatus".equals(kind)) {
                            res = AsrModels.status(MainActivity.this);
                        } else if ("loudness".equals(kind)) {
                            res = Workspace.loudness(MainActivity.this, a, p, cancel);
                        } else if ("ringtone".equals(kind)) {
                            res = Workspace.ringtone(MainActivity.this, a, p, cancel);
                        } else if ("chapters".equals(kind)) {
                            res = Workspace.chapters(MainActivity.this, a, p, cancel);
                        } else if ("roomTest".equals(kind)) {
                            res = RoomTest.run(p, cancel);
                        } else if ("diagnose".equals(kind)) {
                            res = Workspace.diagnose(MainActivity.this, a, p, cancel);
                        } else if ("clean".equals(kind)) {
                            res = Workspace.clean(MainActivity.this, a, p, cancel);
                        } else if ("tlSep".equals(kind)) {
                            res = Separator.split(MainActivity.this, a, p, cancel);
                        } else if ("tlRender".equals(kind)) {
                            res = Timeline.render(MainActivity.this, a, p, cancel);
                        } else if ("tlClean".equals(kind)) {
                            Timeline.clean(MainActivity.this, a.optJSONArray("keep"));
                            res = new JSONObject();
                        } else if ("wsExport".equals(kind)) {
                            res = Workspace.export(MainActivity.this, a, p, cancel);
                        } else if ("wsSave".equals(kind)) {
                            res = Workspace.save(MainActivity.this, a, p, cancel);
                        } else if ("wsClean".equals(kind)) {
                            Workspace.clean(MainActivity.this, a.optJSONArray("keep"));
                            res = new JSONObject();
                        } else if ("transcribe".equals(kind)) {
                            JSONObject tsrc = a.getJSONObject("src");
                            tsrc.put("_model", a.optString("model", ""));
                            res = Studio.transcribe(MainActivity.this, tsrc, a.optString("lang", ""), p, cancel);
                        } else if ("beats".equals(kind)) {
                            res = Studio.beats(MainActivity.this, a.getJSONObject("src"), p, cancel);
                        } else {
                            throw new IllegalArgumentException("unknown job");
                        }
                        jobDone(id, true, res.toString());
                    } catch (Throwable e) {
                        String msg = e instanceof OutOfMemoryError ? "memory" : String.valueOf(e.getMessage());
                        jobDone(id, false, "{\"error\":" + q(msg) + "}");
                    } finally {
                        if (heavy) WorkService.end(MainActivity.this);
                    }
                }
            });
            return id;
        }

        @JavascriptInterface public void cancelJob(String id) {
            AtomicBoolean c = jobs.get(id);
            if (c != null) c.set(true);
        }

        /* -- byte transfer from the page (moving v1 recordings out of the WebView) -- */
        @JavascriptInterface public String beginFile(String name, String mime) {
            try {
                File dir = new File(getCacheDir(), "incoming");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, UUID.randomUUID().toString() + "-" + safeFileName(name));
                String token = UUID.randomUUID().toString();
                openFiles.put(token, new FileOutputStream(f));
                openFilePaths.put(token, f);
                openFileMimes.put(token, mime == null ? "" : mime);
                return token;
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface public boolean appendFile(String token, String b64) {
            OutputStream out = openFiles.get(token);
            if (out == null) return false;
            try {
                out.write(android.util.Base64.decode(b64, android.util.Base64.DEFAULT));
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    private File closeFile(String token) {
        OutputStream out = openFiles.remove(token);
        if (out != null) { try { out.close(); } catch (IOException ignored) { } }
        openFileMimes.remove(token);
        return openFilePaths.remove(token);
    }
}
