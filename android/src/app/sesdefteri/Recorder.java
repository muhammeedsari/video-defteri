package app.sesdefteri;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import org.json.JSONObject;

/** Native recorder: AAC in an .m4a file inside Music/Video Defteri, kept alive by RecordService. */
final class Recorder {
    private static MediaRecorder mr;
    private static MediaLib.Target target;
    private static ParcelFileDescriptor pfd;
    private static String state = "idle";

    private Recorder() { }

    private static boolean callPause = true;

    /** opts: {q: std|high|stereo, src: auto|builtin|wired|usb, call: pause during calls} */
    static synchronized String start(Context c, String mode, String displayName, String optsJson) {
        JSONObject o;
        try { o = new JSONObject(optsJson == null ? "{}" : optsJson); } catch (Exception e) { o = new JSONObject(); }
        callPause = o.optBoolean("call", true);
        agc = o.optBoolean("agc", true);
        return start(c, mode, displayName, o.optString("q", "std"), o.optString("src", "auto"));
    }

    private static boolean agc = true;

    private static String start(Context c, String mode, String displayName, String q, String src) {
        JSONObject res = new JSONObject();
        try {
            if (!"idle".equals(state)) { res.put("ok", false); res.put("error", "busy"); return res.toString(); }
            boolean music = mode.startsWith("music");
            target = MediaLib.create(c, displayName, "audio/mp4");
            mr = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(c) : new MediaRecorder();
            // automatic gain off: VOICE_RECOGNITION is the source Android requires to be free of AGC
            int source = agc ? MediaRecorder.AudioSource.MIC : MediaRecorder.AudioSource.VOICE_RECOGNITION;
            if ("music".equals(mode)) {
                AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
                String unp = am != null ? am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) : null;
                source = "true".equals(unp) ? MediaRecorder.AudioSource.UNPROCESSED : MediaRecorder.AudioSource.CAMCORDER;
            }
            mr.setAudioSource(source);
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            boolean stereo = music || "stereo".equals(q), hi = stereo || "high".equals(q);
            mr.setAudioSamplingRate(hi ? 48000 : 44100);
            mr.setAudioEncodingBitRate(stereo ? 256000 : hi ? 160000 : 128000);
            mr.setAudioChannels(stereo ? 2 : 1);
            if (Build.VERSION.SDK_INT >= 28 && !"auto".equals(src)) {
                AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) for (android.media.AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                    int t = d.getType();
                    boolean m = "builtin".equals(src) ? t == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC
                        : "wired".equals(src) ? t == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET
                        : "usb".equals(src) && (t == android.media.AudioDeviceInfo.TYPE_USB_DEVICE || t == android.media.AudioDeviceInfo.TYPE_USB_HEADSET);
                    if (m) { mr.setPreferredDevice(d); break; }
                }
            }
            if (target.uri != null) {
                pfd = c.getContentResolver().openFileDescriptor(target.uri, "rw");
                if (pfd == null) throw new IllegalStateException("fd");
                mr.setOutputFile(pfd.getFileDescriptor());
            } else {
                mr.setOutputFile(target.file.getAbsolutePath());
            }
            mr.prepare();
            mr.start();
            state = "rec";
            startService(c, true);
            res.put("ok", true);
            res.put("name", target.name);
        } catch (Exception e) {
            cleanup(c, true);
            // some phones refuse the unprocessed/camcorder source: retry with the plain microphone
            if ("music".equals(mode)) return start(c, "music-mic", displayName, q, src);
            try { res.put("ok", false); res.put("error", String.valueOf(e.getMessage())); } catch (Exception ignored) { }
        }
        return res.toString();
    }

    static synchronized boolean pause() {
        if (!"rec".equals(state) || mr == null) return false;
        try { mr.pause(); state = "paused"; return true; } catch (Exception e) { return false; }
    }

    static synchronized boolean resume() {
        if (!"paused".equals(state) || mr == null) return false;
        try { mr.resume(); state = "rec"; return true; } catch (Exception e) { return false; }
    }

    /** Peak since the previous call, 0..1. */
    static synchronized float level() {
        if (mr == null || !"rec".equals(state)) return 0f;
        try { return Math.min(1f, mr.getMaxAmplitude() / 32767f); } catch (Exception e) { return 0f; }
    }

    static synchronized String stop(Context c) {
        JSONObject res = new JSONObject();
        try {
            if (mr == null) { res.put("ok", false); res.put("error", "idle"); return res.toString(); }
            boolean ok = true;
            try {
                if ("paused".equals(state)) { try { mr.resume(); } catch (Exception ignored) { } }
                mr.stop();
            } catch (RuntimeException e) {
                ok = false;
            }
            try { mr.release(); } catch (Exception ignored) { }
            mr = null;
            try { if (pfd != null) pfd.close(); } catch (Exception ignored) { }
            pfd = null;
            startService(c, false);
            state = "idle";
            if (!ok) {
                MediaLib.discard(c, target);
                target = null;
                res.put("ok", false);
                res.put("error", "empty");
                return res.toString();
            }
            JSONObject item = MediaLib.publish(c, target);
            target = null;
            if (item == null) { res.put("ok", false); res.put("error", "publish"); return res.toString(); }
            res.put("ok", true);
            res.put("item", item);
        } catch (Exception e) {
            try { res.put("ok", false); res.put("error", String.valueOf(e.getMessage())); } catch (Exception ignored) { }
        }
        return res.toString();
    }

    static synchronized void cancel(Context c) {
        cleanup(c, true);
    }

    static synchronized boolean active() {
        return !"idle".equals(state);
    }

    private static void cleanup(Context c, boolean discard) {
        if (mr != null) {
            try { mr.stop(); } catch (Exception ignored) { }
            try { mr.release(); } catch (Exception ignored) { }
        }
        mr = null;
        try { if (pfd != null) pfd.close(); } catch (Exception ignored) { }
        pfd = null;
        if (discard && target != null) MediaLib.discard(c, target);
        target = null;
        state = "idle";
        startService(c, false);
    }

    /* ---------- pause during phone calls (also VoIP calls), resume afterwards ---------- */
    private static boolean auto;
    private static android.os.Handler calls;
    private static Runnable poll;

    private static void watchCalls(final Context c, boolean on) {
        if (calls == null) calls = new android.os.Handler(android.os.Looper.getMainLooper());
        if (poll != null) calls.removeCallbacks(poll);
        poll = null;
        auto = false;
        if (!on || !callPause) return;
        final AudioManager am = (AudioManager) c.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        poll = new Runnable() {
            @Override public void run() {
                int m = am.getMode();
                boolean call = m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION || m == AudioManager.MODE_RINGTONE;
                synchronized (Recorder.class) {
                    if (mr == null) return;
                    if (call && "rec".equals(state)) { try { mr.pause(); state = "paused"; auto = true; } catch (Exception ignored) { } }
                    else if (!call && auto && "paused".equals(state)) { try { mr.resume(); state = "rec"; } catch (Exception ignored) { } auto = false; }
                }
                if (poll == this) calls.postDelayed(this, 700);
            }
        };
        calls.postDelayed(poll, 700);
    }

    /** {"state": rec|paused|idle, "call": paused because of a call} */
    static synchronized String info() {
        return "{\"state\":\"" + state + "\",\"call\":" + auto + "}";
    }

    static synchronized boolean resumeManual() { auto = false; return resume(); }

    private static void startService(Context c, boolean on) {
        watchCalls(c, on);
        Intent svc = new Intent(c, RecordService.class);
        try {
            if (on) {
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(svc);
                else c.startService(svc);
            } else {
                c.stopService(svc);
            }
        } catch (Exception ignored) { }
    }
}
