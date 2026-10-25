# Video Defteri — Claude için proje rehberi

> **Video Defteri, Ses Defteri 2.5'in kopyasıdır** (github.com/muhammeedsari/ses-defteri, tüm geçmişiyle). Hedef: Ses Defteri'nin ses özelliklerinin üstüne internetsiz video düzenleyici.
> - Yüklü uygulama kimliği **`app.videodefteri`** (`build_apk.py` → `aapt --rename-manifest-package`), Ses Defteri'nin yanına ayrı kurulur. Java paketi **`app.sesdefteri` olarak kaldı**: hazır JNI köprüleri (`libsdsep.so`, `libsdmp3.so`) bu pakete bağlı; paketi değiştirmek için önce `android/native/*.c` yeniden derlenmeli.
> - Uygulama adı ve klasör: "Video Defteri" (`Music/Video Defteri`). Sürüm 0.1 (versionCode 1); APK `android/VideoDefteri.apk`. Aynı imza anahtarı kullanılır.
> - Ses Defteri'deki yeni sürümler: `git remote add ses https://github.com/muhammeedsari/ses-defteri && git fetch ses && git merge ses/main` (ortak geçmiş var).
> - **Ana plan: `docs/PROJE_PLANI.md`** (aşamalar, kabul ölçütleri, kapsam dışı listesi, iş modeli) ve çalışma kuralları `docs/PROJE_TALIMATI.md`. Bir aşamayı kabul ölçütlerine bakmadan bitti sayma; kapsam dışı istekleri kullanıcıya hatırlat.
> - 0.2 notları: `docs/VIDEO-PLAN.md`.
> Aşağıdaki metin Ses Defteri içindir; çelişirse yukarıdaki geçerlidir.

Bu dosyayı her oturumun başında oku. Ayrıntılar: `docs/DURUM.md` (sürüm geçmişi, ne çalışıyor, ne kaldı) ve `docs/MIMARI.md` (kod haritası, köprüler, veri yapıları).

## Proje
Ses Defteri, **internetsiz çalışan bir Android uygulaması**: ses kaydı, müzik çalar ve çok katmanlı ses stüdyosu (düzenleme, gürültü temizliği, yapay zekâ ile ses ayırma/temizleme, sesten metne). Sahibi Türk; uygulama 9 dilde (tr, en, de, fr, es, pt, ru, ar, zh), varsayılan dil İngilizce (ilk açılışta dil seçilir).

- **Arayüz:** tek sayfalık HTML/CSS/vanilla JS, Android WebView içinde (`web/`). Çerçeve yok.
- **Yerel katman:** Java 8 (lambda/method reference YOK — dx/dalvik-exchange min-sdk 24 ile derlenir), `android/src/app/sesdefteri/`.
- **Ses işleme:** saf Java DSP (`dsp/`), masaüstü JVM'de de test edilebilir.
- **Yapay zekâ:** sherpa-onnx v1.13.8 (ONNX Runtime) — Whisper (sesten metne, isteğe bağlı indirilir), Spleeter (sesi ayır), DPDFNet-2 48 kHz (gelişmiş gürültü temizliği). Kendi JNI köprüleri: `libsdsep.so` (ayırma + gürültü temizleme), `libsdmp3.so` (LAME MP3).
- **Derleme:** Gradle YOK. `android/build_apk.py`: aapt → javac (-source 8, android-34.jar) → dalvik-exchange → aapt add (dex + .so) → zipalign → apksigner.
- **Sürüm:** şu an **2.5** (versionCode 21). minSdk 24, targetSdk 34. Paket adı `app.sesdefteri`.

## Kullanıcıyla çalışma kuralları (önemli)
- Kullanıcıyla **Türkçe** konuş. Teknik olmayan, kısa ve net anlat.
- Kullanıcının tercihi: **"kodu en optimize ve en hafif şekilde yaz; test etmene gerek yok, ben test ederim ya da 'test et' derim."** Cihazda test yok; yeni DSP algoritmalarında masaüstünde hızlı ölçüm yapmak serbest ve faydalı (bkz. `tests/desktop`).
- Her sürüm şöyle teslim edilir: kod değişikliği → sürüm numarası artır (AndroidManifest `versionCode` +1 ve `versionName`, `web/body.html` içindeki `const VERSION = '…'`) → imzalı APK derle → git commit + push → APK'yı kullanıcıya gönder → Türkçe kısa özet (ne değişti, neyi test etmeli). GitHub Release'i kullanıcı kendisi yayımlar.
- Commit mesajları İngilizce, kısa; sürüm numarasıyla başlar ("2.6: …").

## Güvenlik kuralları (kesin)
- **İmza anahtarı (`ses-defteri-imza.p12`, alias `sesdefteri`) ve parolası ASLA repoya girmez.** `build_apk.py` parolayı `SD_KS_PASS` ortam değişkeninden, anahtarı `SD_KS` (varsayılan: `android/ses-defteri-imza.p12`, gitignore'da) yolundan okur. Anahtar olmadan derleme durur; yeni anahtar üretmek (`SD_NEW_KEY=1`) mevcut kullanıcıların güncelleme kurmasını imkânsız kılar — yapma. Anahtar dosyası ve parolası kullanıcıda; yoksa kullanıcıdan iste.
- Kullanıcının e-postasını kodda/repoda kullanma. Geri bildirim bağlantısı: `https://github.com/muhammeedsari/ses-defteri/issues`.
- Gizlilik vaadi: veri toplanmaz, sunucu yok; internet yalnızca kullanıcı isteyince (model indirme, geri bildirim sayfası). Bunu bozan özellik ekleme (analitik, reklam vb.).

## Derleme
```bash
bash android/fetch_vendor.sh            # bir kez: ../vendor içine sherpa-onnx kütüphaneleri, modeller, fontlar (~200 MB)
# android-34.jar'ı vendor/android-34.jar olarak koy (Android SDK platforms/android-34/android.jar)
# araçlar (Ubuntu): apt install aapt zipalign apksigner dalvik-exchange openjdk-21-jdk python3-pil
SD_KS=/yol/ses-defteri-imza.p12 SD_KS_PASS=… python3 android/build_apk.py   # → android/SesDefteri.apk
```
- `build_apk.py`, `web/head.html + web/extra.css + "</style>" + web/body.html` birleştirerek `web/page.html` üretir, i18n*.js dosyalarını sayfaya gömer, fontları yerelleştirir.
- **Yeni i18n dosyası eklersen** iki yere yaz: `web/body.html` içindeki `<script src="i18nN.js">` satırları ve `build_apk.py` içindeki `for js in [...]` listesi.
- JS kontrolü (derlemeden önce): `page.html`'deki `<script>` bloklarını çıkarıp `node --check`; ayrıca `$('#id')` ile kullanılan ama HTML'de olmayan id var mı bak (bilinen zararsız: `wsTime`).

## Kod düzeni (özet)
- `web/body.html` (~4300 satır): tüm arayüz + JS. Modüller yorum başlıklarıyla ayrılmış (`/* ---------- … ---------- */`). Yeni özellikleri ilgili modülün yanına ya da sürüm bloğu olarak ekle.
- `web/i18n*.js`: her dosya 9 dili `window.SD_I18N`'e birleştirir. Yeni anahtarları yeni bir `i18nN.js` dosyasına koy (şu an son: `i18n15.js`), 9 dilin hepsini yaz. `t('anahtar', {n})` ile kullanılır.
- `android/src/app/sesdefteri/MainActivity.java`: WebView + `SesDefteriAndroid` JS köprüsü (`AND` in JS) + iş kuyruğu (`job(kind, argsJson)` → `runJob(kind, args, onProg)` JS'de).
- `Workspace.java`: tüm düzenleme işlemleri (`op(...)` içinde `case "denoise"` vb.), teşhis (`diagnose`), Temizle (`clean`), kaydet/dışa aktar.
- `Timeline.java`: çok katmanlı proje motoru (canlı karışım `AudioTrack`, render). Klipler 48 kHz 16-bit WAV, `files/proj`.
- Ayrıntılı harita `docs/MIMARI.md`'de.

## Bilinen tuzaklar
- Java'da `new Resampler(ch, (double) from, (double) to)` — int verirsen yanlış overload seçilir (geçmişte ciddi hata yaptı).
- Hızlı biten işlerde ön plan servisi: `WorkService` sadece 1 sn'den uzun işlerde başlar; başlamadan durdurulursa Android uygulamayı kapatır (2.3.2'de düzeltildi, bozma).
- `wsClean`/`tlClean` işleri `studioPool`'da (tek iş parçacığı) çalışır ki çalışan bir işin çıktısını silmesin.
- `JSONArray.optDouble(int)` masaüstü org.json shim'inde yok → `getDouble(i)` kullan.
- `Separator.model()` modelleri APK'dan dosyaya kopyalar (C API dosya yolu ister); bu yüzden Spleeter/DPDFNet telefonda iki kez yer tutar.
- Ağ: bu geliştirme ortamında Hugging Face ve Maven erişimi engelliydi; GitHub release indirmeleri çalışıyordu.
