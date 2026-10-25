"""Builds Video Defteri.apk: bundles the web app into assets, compiles the Java shell, signs the APK."""
import os, re, shutil, subprocess, pathlib
from PIL import Image, ImageDraw

# installed app id; the Java package stays app.sesdefteri because the prebuilt JNI bridges are bound to it
APP_ID = "app.videodefteri"
HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent
WEB = pathlib.Path(os.environ.get("SD_WEB", ROOT / "web"))
# everything that is not in git (native libraries, models, fonts, android.jar): see android/fetch_vendor.sh
VENDOR = pathlib.Path(os.environ.get("SD_VENDOR", ROOT / "vendor"))
FONTSRC = VENDOR / "fonts"
ANDROID_JAR = pathlib.Path(os.environ.get("SD_ANDROID_JAR", VENDOR / "android-34.jar"))
LITE = os.environ.get("LITE") == "1"   # without speech-to-text (small download)
OUT = HERE / "out"
KS = pathlib.Path(os.environ.get("SD_KS", HERE / "ses-defteri-imza.p12"))   # the signing key is NEVER committed
KS_PASS = os.environ.get("SD_KS_PASS", "")  # signing password is not stored in the repository

def run(cmd, **kw):
    r = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if r.returncode != 0:
        print(r.stdout[-3000:], r.stderr[-3000:])
        raise SystemExit(f"failed: {cmd[0]}")
    return r.stdout

# ---------- fonts (offline) ----------
def build_fonts(dest: pathlib.Path):
    dest.mkdir(parents=True, exist_ok=True)
    want = {
        "ibm-plex-sans": ["400", "500", "600"],
        "ibm-plex-mono": ["400", "500"],
        "bricolage-grotesque": ["600", "700"],
    }
    css = []
    for fam, weights in want.items():
        pkg = next(FONTSRC.glob(f"fontsource-{fam}-*/package"))
        for w in weights:
            text = (pkg / f"{w}.css").read_text()
            for block in re.findall(r"/\* ([a-z0-9-]+) \*/\s*(@font-face \{.*?\})", text, re.S):
                label, rule = block
                if not (label.endswith(f"-latin-{w}-normal") or label.endswith(f"-latin-ext-{w}-normal")
                        or label.endswith(f"-cyrillic-{w}-normal") or label.endswith(f"-cyrillic-ext-{w}-normal")):
                    continue
                fname = f"{label}.woff2"
                shutil.copy(pkg / "files" / fname, dest / fname)
                rule = re.sub(r"src: [^;]+;", f"src: url({fname}) format('woff2');", rule)
                css.append(rule)
    (dest / "fonts.css").write_text("\n".join(css) + "\n")

def assemble_page():
    """web/page.html = head.html (first part, ends inside <style>) + extra.css + </style> + body.html"""
    head = (WEB / "head.html").read_text(encoding="utf-8")
    css = (WEB / "extra.css").read_text(encoding="utf-8")
    body = (WEB / "body.html").read_text(encoding="utf-8")
    (WEB / "page.html").write_text(head + css + "</style>\n\n" + body, encoding="utf-8")

def web_page(local_fonts=True, keep_sw=True):
    assemble_page()
    page = (WEB / "page.html").read_text(encoding="utf-8")
    title = re.search(r"<title>.*?</title>\n", page).group(0)
    page = page.replace(title, "", 1)
    page = page.replace('<meta name="claude:viewport-fit-sides" content="cover">\n', "", 1)
    page = page.replace("https://cdn.jsdelivr.net/npm/fix-webm-duration@1.0.6/fix-webm-duration.js", "fix-webm-duration.js")
    if local_fonts:
        page = re.sub(r'<link rel="preconnect"[^>]*>\n', "", page)
        page = re.sub(r'<link rel="stylesheet" href="https://fonts.googleapis.com[^"]*">', '<link rel="stylesheet" href="fonts/fonts.css">', page)
    for js in ["i18n.js", "i18n2.js", "i18n3.js", "i18n4.js", "i18n5.js", "i18n6.js", "i18n7.js", "i18n8.js", "i18n9.js", "i18n10.js", "i18n11.js", "i18n12.js", "i18n13.js", "i18n14.js", "i18n15.js", "i18n16.js", "i18n17.js", "i18n18.js"]:
        page = page.replace(f"<script src=\"{js}\"></script>", "<script>\n" + (WEB / js).read_text(encoding="utf-8") + "\n</script>")
    head = f"""<!doctype html>
<html lang="tr">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover">
{title.strip()}
<style>:root{{color-scheme:light;padding-top:env(safe-area-inset-top,0px);padding-bottom:env(safe-area-inset-bottom,0px)}}body{{margin:0;font:14px system-ui,sans-serif;-webkit-user-select:none;user-select:none}}input,textarea{{-webkit-user-select:text;user-select:text}}img{{max-width:100%}}[hidden]{{display:none!important}}</style>
</head>
<body>
"""
    return head + page + "\n</body>\n</html>\n"

# ---------- icons ----------
GREEN, WHITE, AMBER = (29, 110, 90), (255, 255, 255), (242, 184, 61)
def draw_mark(d, off, k):
    for x, y, h in [(3.5, 10, 4), (7, 7, 10), (10.5, 4, 16), (14, 8.5, 7), (17.5, 10.5, 3)]:
        d.rounded_rectangle([off + x * k, off + y * k, off + (x + 2) * k, off + (y + h) * k], radius=k, fill=WHITE)
    d.polygon([(off + 12.5 * k, off + 4.2 * k), (off + 17.7 * k, off + 6.1 * k), (off + 12.5 * k, off + 8.0 * k)], fill=AMBER)

def fg_icon(px):  # adaptive foreground, 108dp canvas, mark inside the 66dp safe zone
    s = 4; W = px * s
    im = Image.new("RGBA", (W, W), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    box = W * 58 / 108; off = (W - box) / 2
    draw_mark(d, off, box / 24)
    return im.resize((px, px), Image.LANCZOS)

def legacy_icon(px):
    s = 4; W = px * s
    im = Image.new("RGBA", (W, W), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    pad = W * 0.04
    d.rounded_rectangle([pad, pad, W - pad, W - pad], radius=int(W * 0.22), fill=GREEN)
    box = W * 0.62; off = (W - box) / 2
    draw_mark(d, off, box / 24)
    return im.resize((px, px), Image.LANCZOS)

def main():
    shutil.rmtree(OUT, ignore_errors=True)
    for sub in ["gen", "obj", "dex"]:
        (OUT / sub).mkdir(parents=True)

    # assets
    www = HERE / "assets" / "www"
    shutil.rmtree(www, ignore_errors=True); www.mkdir(parents=True)
    (www / "index.html").write_text(web_page(), encoding="utf-8")
    shutil.copy(VENDOR / "fix-webm-duration.js", www / "fix-webm-duration.js")
    build_fonts(www / "fonts")
    # speech-to-text models (Whisper base int8 + Silero VAD)
    asr = HERE / "assets" / "asr"
    asr.mkdir(parents=True, exist_ok=True)
    for old in asr.glob("base-*"): old.unlink()
    if LITE:
        shutil.rmtree(asr, ignore_errors=True)
    for f in ([] if LITE else ["silero_vad.onnx"]):   # Whisper itself is downloaded in the app on request
        if not (asr / f).exists() or (asr / f).stat().st_size != (VENDOR / "asr" / f).stat().st_size:
            shutil.copy(VENDOR / "asr" / f, asr / f)
    # voice separation (Spleeter 2 stems, fp16)
    sep = HERE / "assets" / "sep"
    sep.mkdir(parents=True, exist_ok=True)
    if LITE:
        shutil.rmtree(sep, ignore_errors=True)
    for f in ([] if LITE else ["vocals.fp16.onnx", "accompaniment.fp16.onnx"]):
        if not (sep / f).exists() or (sep / f).stat().st_size != (VENDOR / "sepm" / f).stat().st_size:
            shutil.copy(VENDOR / "sepm" / f, sep / f)
    # speech denoiser (DPDFNet-2, 48 kHz, DeepFilterNet family)
    if not LITE and not (sep / "dpdfnet.onnx").exists():
        shutil.copy(VENDOR / "dn" / "dpdfnet2_48khz_hr.onnx", sep / "dpdfnet.onnx")

    # icons
    res = HERE / "res"
    for dpi, mult in {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}.items():
        (res / f"drawable-{dpi}").mkdir(exist_ok=True)
        (res / f"mipmap-{dpi}").mkdir(exist_ok=True)
        fg_icon(int(108 * mult)).save(res / f"drawable-{dpi}" / "ic_launcher_fg.png")
        legacy_icon(int(48 * mult)).save(res / f"mipmap-{dpi}" / "ic_launcher.png")

    # R.java
    run(["aapt", "package", "-f", "-m", "-J", str(OUT / "gen"), "-M", str(HERE / "AndroidManifest.xml"),
         "-S", str(res), "-I", str(ANDROID_JAR)])
    # compile
    srcs = [str(p) for p in (HERE / "src").rglob("*.java")] + [str(p) for p in (OUT / "gen").rglob("*.java")]
    run(["javac", "-nowarn", "-Xlint:-options", "-source", "8", "-target", "8", "-encoding", "UTF-8",
         "-bootclasspath", str(ANDROID_JAR), "-d", str(OUT / "obj")] + srcs)
    # dex
    run(["dalvik-exchange", "--dex", "--min-sdk-version=24", f"--output={OUT / 'dex' / 'classes.dex'}", str(OUT / "obj")])
    # package
    unsigned = OUT / "app-unsigned.apk"
    run(["aapt", "package", "-f", "-M", str(HERE / "AndroidManifest.xml"), "-S", str(res), "-A", str(HERE / "assets"),
         "-I", str(ANDROID_JAR), "--rename-manifest-package", APP_ID, "-F", str(unsigned)])
    run(["aapt", "add", str(unsigned), "classes.dex"], cwd=str(OUT / "dex"))
    # native speech-to-text libraries
    libs = OUT / "libs"
    names = []
    for abi in ([] if LITE else ["arm64-v8a", "armeabi-v7a"]):
        (libs / "lib" / abi).mkdir(parents=True, exist_ok=True)
        for so in ["libsherpa-onnx-jni.so", "libonnxruntime.so", "libsherpa-onnx-c-api.so", "libsdsep.so", "libsdmp3.so"]:
            if not (VENDOR / "jniLibs" / abi / so).exists(): continue   # the MP3 encoder is only built for 64-bit ARM
            shutil.copy(VENDOR / "jniLibs" / abi / so, libs / "lib" / abi / so)
            names.append(f"lib/{abi}/{so}")
    if names: run(["aapt", "add", str(unsigned)] + names, cwd=str(libs))
    aligned = OUT / "app-aligned.apk"
    run(["zipalign", "-f", "-p", "4", str(unsigned), str(aligned)])
    # signing key (kept so future updates install over this version)
    if not KS.exists() and os.environ.get("SD_NEW_KEY") != "1":
        raise SystemExit("signing key missing: put ses-defteri-imza.p12 next to build_apk.py (or SD_KS=...) and set SD_KS_PASS. "
                         "A new key (SD_NEW_KEY=1) makes updates impossible to install over existing versions.")
    if not KS.exists():
        run(["keytool", "-genkeypair", "-keystore", str(KS), "-storetype", "PKCS12", "-storepass", KS_PASS,
             "-keypass", KS_PASS, "-alias", "sesdefteri", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
             "-dname", "CN=Ses Defteri, O=Ses Defteri, C=TR"])
    final = HERE / ("VideoDefteri-hafif.apk" if LITE else "VideoDefteri.apk")
    run(["apksigner", "sign", "--ks", str(KS), "--ks-pass", f"pass:{KS_PASS}", "--ks-key-alias", "sesdefteri",
         "--min-sdk-version", "24", "--out", str(final), str(aligned)])
    print(run(["apksigner", "verify", "--verbose", "--min-sdk-version", "24", str(final)]))
    print(run(["aapt", "dump", "badging", str(final)]).splitlines()[:6])
    print("size", final.stat().st_size)

if __name__ == "__main__":
    main()
