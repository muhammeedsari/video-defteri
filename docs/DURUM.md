# Ses Defteri — Proje durumu (2.5 itibarıyla, 25 Ekim 2026)

## 1. Amaç ve teknik yığın
Android için **internetsiz** ses kaydı + müzik çalar + ses stüdyosu. Hedef: YouTube/podcast yapanlar, toplantı ve sesli not kaydedenler, müzik dinleyenler. Kullanıcı kitlesi öncelikle Türkiye, ama arayüz 9 dilde.

| Katman | Teknoloji |
|---|---|
| Arayüz | HTML + CSS + vanilla JS, tek sayfa (`web/`), Android WebView |
| Yerel | Java 8 (lambda yok), Android API 24–34, Gradle yok |
| DSP | Saf Java (`dsp/`): FFT, STFT gürültü azaltma (Wiener + min. istatistik takibi), biquad EQ, limiter, LUFS, true-peak, time-stretch/pitch (formant korumalı), beat takibi, tık onarımı, de-esser, de-clip, yankı azaltma |
| Yapay zekâ | sherpa-onnx 1.13.8 + ONNX Runtime: Whisper base/small int8 (indirilir), Silero VAD, Spleeter 2-stem fp16, DPDFNet-2 48 kHz |
| Yerel köprüler | `libsdsep.so` (sherpa C API → ayırma + akışlı gürültü temizleme), `libsdmp3.so` (LAME 3.99.5, sadece arm64) — zig ile derlendi, prebuilt repoda |
| Derleme | `android/build_apk.py` (aapt, javac, dalvik-exchange, zipalign, apksigner) |
| Dağıtım | İmzalı APK → kullanıcı GitHub Releases'te yayımlar. Play Store hazırlığı bekliyor |

## 2. Tamamlanan ve çalışan bileşenler (sürüm sürüm)
Kullanıcı her sürümü telefonda test ediyor; aşağıdakiler teslim edildi. ("Doğrulama" sütunu: masaüstü ölçümü yapıldı mı.)

- **1.3–1.4:** Kayıtlar listesi, kategoriler, işaretler/notlar, dalga formu, oynatıcı, Sesi düzenle sekmesi (adım adım işlemler).
- **1.5:** Çok katmanlı zaman çizelgesi (taşı, kısalt, böl, çoğalt, ses düzeyi, soldurma, efekt, geri al/yinele, canlı karışım, dışa aktarma). En fazla 6 katman.
- **1.6:** Sesi ayır (Spleeter; konuşmayı koru / kaldır / ikisini ayır), ducking (konuşma gelince müziği kıs), zaman çizelgesine kayıt, ritim işaretleri. 1.6.1: yeniden örnekleme hatası düzeltildi.
- **1.7:** Kayıt amacı (YouTube/Podcast/Toplantı/Sesli not/Müzik), oda testi, canlı uyarılar (kırpılma vb.), aramada otomatik duraklatma, **Temizle** (teşhis → önerilen düzeltmeler → tek seferde uygula → önce/sonra). 1.7.1: YZ gürültü silme, parça birleştirme, katman sessize alma rozeti, "sorun bulunamadı" bildirimi.
- **1.8:** MP3 dışa aktarma (LAME), dosya adı şablonu, çöp kutusu, Ayarlar (kayıt kalitesi, mikrofon, temizlik şiddeti, biçim, depolama, lisanslar).
- **1.9:** Duraklama kısaltma, nefes kısma, patlamış ses (clipping) onarımı, 5 bantlı EQ + hazır ayarlar, toplu temizleme.
- **2.0:** Mıknatıs/yapışma (kılavuz çizgi, titreşim), işaretçiler, punch-in, bölümler, zil sesi yapma, videodan ses alma.
- **2.1:** Çalar: eşit ses yüksekliği (LUFS), sessizliği atla, A-B döngüsü, EQ, yumuşak geçiş, boşluksuz çalma, uyku zamanlayıcı. Yankı azaltma, kompresör, reverb, delay, MP3 etiketleri + kapak, oda gürültüsü hafızası.
- **2.2:** Sesten metne iyileştirmeleri (dil = uygulama dili, tanıma öncesi seviye/80 Hz filtre, Whisper small indirme, arka planda metne dökme), metinden düzenleme, tekrar çekim ve dolgu sesi ("ııı") tespiti, konuya göre bölümler.
- **2.3:** Kopyala/kes/yapıştır (projeler arası), sessizlik ekle (ripple), ağız sesi temizleme, mono→stereo + dışa aktarmada kanal seçimi, çalma listeleri + "sıradaki/sıraya ekle", kısa sesleri yoksay, otomatik kazanç (AGC) seçimi, kayıttan sonra uzun sessizlikleri kısalt, açılış sekmesi, duraklama eşiği ayarı, gizlilik metni + geri bildirim bağlantısı (`PRIVACY.md`).
  - Doğrulama: ağız sesi dedektörü yapay testte 25 ağız sesinden 19'unu buldu, temiz konuşmada 1 yanlış.
- **2.3.1:** İlk açılışta dil seçme ekranı (İngilizce önseçili); sesten metne dili uygulama diliyle birlikte değişir; dil seçilmezse varsayılan İngilizce (yerel bildirim metinleri dahil).
- **2.3.2:** Hızlı biten işlemlerde uygulamanın kapanması düzeltildi (WorkService yarışı). EQ artık canlı ve tahribatsız (klip ayarı, "Dinle" düğmesi). Çalar EQ önceliği yükseltildi (1000). Yatay taşma/yakınlaştırma/esneme kapatıldı. Kayıt amacı çipleri sığmazsa alt satıra iner.
  - Doğrulama: canlı EQ masaüstünde ölçüldü (+6 dB/−6 dB bantlar doğru).
- **2.4:** DPDFNet-2 (48 kHz) ile **gelişmiş YZ gürültü temizliği** (Hafif/Dengeli/Güçlü). "Gürültüyü buradan öğren" (oynatma çizgisindeki 1 sn). Değişen gürültüyü takip eden spektral gürültü azaltma. Kelime arası sessizlik (noise gate). **Bas güçlendir / Tiz güçlendir** ayrı tuşlar (canlı, tahribatsız). Klip başına **"Bu parçada yapılanlar"** listesi.
  - Doğrulama (masaüstü, sentetik gürültü, SDR): 5 dB'lik gürültülü ses → eski spektral 8,7 dB, DPDFNet 13,5 dB. Değişen trafik gürültüsü: 7,7 → eski 9,1 / takipli 10,2 / DPDFNet 15,8 dB. Hız: masaüstünde ~0,4–0,55× gerçek zaman.
- **2.5:** Sesten metne **tamamen isteğe bağlı**: Whisper base (~95 MB) ve small (~375 MB) Ayarlar'dan ya da ilk "Metne dök"te indirilir/silinir; APK 168 → 74 MB. Spleeter kaldı: "Sesi ayır" + ayrı bir "Ses ayırma ile gürültü sil" efekti. DPDFNet "Gelişmiş YZ gürültü temizliği" olarak duruyor (Temizle ekranı bunu kullanır).
  - Repo artık kendi başına derlenebilir: `android/fetch_vendor.sh` + taşınabilir `build_apk.py`; temiz kopyadan derlenen APK 2.5 ile birebir aynı içerikte ve aynı imzalı (doğrulandı).

- **2.6:** Ses seviyesi çizgisi (klip başına `env: [[dosya zamanı, dB]]`, en alt −40 dB = sessiz; `Timeline.Engine` canlı + render), otomatik çapraz geçiş (aynı katmanda kısmi örtüşme, eşit güç `xi/xo`; Ayarlar > Stüdyo `tlXf`), **Videoya koy** (`VideoMux.java`: görüntü yeniden kodlanmadan kopyalanır, karışım AAC, Movies/Ses Defteri). Cihazda denenmedi.
- **2.7:** **Sesi netleştir** (op `clarify`, `dsp/Clarity.java`: 80 Hz HP, 300 Hz kesme, 3 kHz artırma, 8 kHz raf, hafif exciter + kompresör + limiter; miktarlar ortalama sese göre ölçülür), **Ses efektleri** (op `voicefx`, `dsp/VoiceFx.java`: radyo, telefon, megafon, robot; ince/kalın = TimePitch ±5 yarım ton, formant yok). Cihazda denenmedi.

## 3. En son yazılan ve çalışan kod (2.4–2.5)
- `android/src/app/sesdefteri/Denoiser.java` — DPDFNet akışlı gürültü temizleme düğümü (`Stage`): stereo → orta kanal, 1920 örnek gecikme telafisi, orijinalden `keep` oranı geri karıştırma (Hafif −12 dB, Dengeli −23 dB, Güçlü −34 dB), sonunda sessizlik + flush.
- `Workspace.java`:
  - `aiDenoise(...)` (op `aidenoise`, DPDFNet; 48 kHz dışı sesler yeniden örneklenir)
  - `sepDenoise(...)` (op `sepdenoise`, Spleeter vokal kanalı)
  - `gate(...)` + `Gate` sınıfı (op `gate`; 5 ms ileri bakış, 120 ms tutma)
  - `learnNoise(...)` (op `denoise` + `learn:[t0,t1]`)
  - `chans(...)` (dışa aktarmada mono/stereo, argüman `chans`)
  - `case "mouth"`, `case "stereo"`
  - `clean()` içinde duraklama eşiği (`pmax/pkeep`)
- `dsp/Spectral.java` — `tracking(fs)`: bin başına min. istatistik (2,5 sn, 8 alt pencere), statik profilin altına düşmez.
- `Timeline.java` — `Engine` içinde klip başına canlı EQ (`eq` 5 bant + `bass` 150 Hz raf + `treble` 5 kHz raf), önizlemede yumuşak sınırlama (tanh, −1 dB).
- `AsrModels.java` — base/small indir (Hugging Face + hf-mirror, kaldığı yerden devam), `pick()` indirilmiş olanı seçer, yoksa `asr-missing` hatası. `Studio.transcribe` artık sadece indirilen dosyalardan yükler.
- `WorkService.java` — gecikmeli başlatma (1 sn) + yalnızca ön plana geçtikten sonra durdurma.
- `web/body.html` — `tlEq`, `tlTone`, `tlFxList`, `tlCopy/tlPaste/tlInsertSilence`, çalma listeleri (`playlistsHTML`, `songMenu`, `plAdd`…), `enqueue`, `silenceCut`, `openPrivacy/openFeedback`, `needLang/showLangPick`, `hasAsr/asrOffer/asrModelUi/asrDownload`.
- `web/i18n13.js` (2.3 + 2.3.2), `i18n14.js` (2.4), `i18n15.js` (2.5).

## 4. Bitmemiş / riskli / sıradaki işler
### Kullanıcının telefonda doğrulaması gerekenler (bilgi bekleniyor)
- **Whisper base indirme adresi** (`huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main/base-encoder.int8.onnx` …) geliştirme ortamından denenemedi. Small bu yolla çalışıyordu. Hata olursa: modelleri kullanıcının GitHub release sayfasına koyup `AsrModels.hosts()`'a ekle.
- **DPDFNet telefonda hız ve ses kalitesi** (robotik/boğuk mu, gürültü kalıyor mu, 1 dk ses kaç sn). Yavaşsa: GTCRN (16 kHz, çok hızlı) "Hızlı" seçeneği olarak eklenebilir (`speech-enhancement-models/gtcrn_simple.onnx`, aynı C API, `cfg.model.gtcrn.model`).
- 2.3.2 düzeltmeleri: kanalları düzelt tekrarında çökme, EQ hatası, sayfa taşması — kullanıcı onayı bekleniyor.
- Kayıttan sonra sessizlik kısaltma (`silenceCut`), AGC kapalı kayıt (VOICE_RECOGNITION kaynağı), çalma listeleri — cihazda denenmedi.

### Önerilen, kullanıcı onayı bekleyen
- **2.6: "Sesi netleştir" tek tuşu** — analiz edip sırayla: boğukluğu (200–400 Hz) azalt, anlaşılırlık (2–4 kHz) artır, hafif parlaklık/harmonik, kompresör, seviye; Hafif/Dengeli/Güçlü + önce/sonra + "yapılanlar" listesi. Kullanıcıya önerildi, cevap bekleniyor.

### Planlanan (yol haritası)
- **Google Play yayını:** kullanıcı geliştirici hesabı açacak (25 $). Yeni kişisel hesapta 12 test kişisi × 14 gün kapalı test zorunlu (alternatif: şirket hesabı + D-U-N-S). Yapılacak kod işi: **AAB çıktısı** (aapt2 proto + bundletool; ABI bölme ile indirme ~63 MB), mağaza metinleri (9 dil), veri güvenliği formu ("veri toplanmıyor"), mikrofon/ön plan servisi izin açıklamaları, `PRIVACY.md` bağlantısı. Kullanıcıya test davet metni teklif edildi.
- **Premium (sonra):** tek seferlik "Pro" kilidi (abonelik değil). Aday premium özellikler: YZ gürültü silme/ayırma, toplu temizleme, gelişmiş metin modeli, bölümler, MP3 kapak/etiket. Engel: Play Billing kütüphanesi (Maven bu ortamda engelliydi).
- **iOS:** şimdilik yok (Swift ile yerel katmanın yeniden yazılması + Mac + Apple hesabı gerekir).
- Telefonda iki kez yer tutma: Spleeter ve DPDFNet modelleri APK'dan dosyaya kopyalanıyor (C API dosya yolu istiyor). İstenirse ilk kullanımda indirme yapılabilir (kullanıcı Spleeter'ın APK'da kalmasını istedi).

### Bilinen küçük eksikler
- `wsTime` id'si JS'de kullanılıyor ama sayfada yok (zararsız, eski düzenleyiciden kalma).
- `libsdmp3.so` sadece arm64; armeabi-v7a cihazlarda MP3 yerine M4A'ya düşer.
- Gate (kelime arası sessizlik) Temizle listesinde varsayılan kapalı.
- Masaüstü test org.json shim'i (`tests/desktop/jsonshim`) `new JSONArray(String)` ayrıştırmasını desteklemiyor (testte `.put()` kullan).
