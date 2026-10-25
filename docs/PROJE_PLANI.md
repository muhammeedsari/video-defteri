# Video Defteri — Proje planı

Sürüm: plan 1.0 · 25 Ekim 2026
Kaynak araştırma: "CapCut Düzenleme Özellikleri — Rakip Envanteri" belgesi (claude.ai artifact) ve kullanıcının CapCut Android ekran görüntüleri.

---

## 1. Ürün tanımı

**Ne:** İnternetsiz çalışan, hafif bir Android video düzenleme uygulaması.
**Kimin için:** YouTube (yatay ve Shorts), Instagram (Reels ve akış) ve TikTok için video hazırlayan içerik üreticileri. İlk kullanıcı: projenin sahibi (YouTube kanalı + bal markası için Reels/reklam videoları).
**Neden kazanır:** CapCut'u bırakanların şikayetleri editörün kendisi değil; abonelik, ödeme duvarı, filigran, reklam, gizlilik kaygısı ve yavaşlık. Biz:
- Temel ve orta seviye düzenlemenin tamamını ücretsiz veriyoruz, filigran yok.
- Premium tek seferlik ve ucuz.
- İnternetsiz çalışıyoruz; video telefondan çıkmıyor.
- Hafifiz; eski telefonlarda da akıcıyız.
- CapCut'ta ücretli ya da sınırlı olan ses araçlarını (gürültü azaltma, sesi çıkarma, sesi iyileştirme) Ses Defteri altyapısıyla ücretsiz veriyoruz.

**Bilinçli olarak yapmadıklarımız (kapsam dışı):**
Üretken yapay zekâ (medya oluşturma, YZ video, YZ ile genişletme/uzatma/yeniden düzenleme, YZ ile nesne silme), YZ avatar, dudak senkronizasyonu, göz teması, video çevirmeni/dublaj, rötuş/güzellik, arka plan kaldırma, kamera/hareket takibi, otomatik çerçeve, optik akış ağır çekim, şablon pazarı, büyük müzik kütüphanesi, bulut senkron, hesap/giriş.

---

## 2. Mimari

### 2.1 Ses Defteri'nden ne taşınır
| Parça | Ses Defteri'nde | Video Defteri'nde |
|---|---|---|
| Arayüz kabuğu | `web/` tek sayfa HTML/CSS/JS, WebView | Aynı yaklaşım; zaman çizelgesi, menüler, paneller WebView'de |
| JS ↔ Java köprüsü | `AND.*`, `job(kind,args)` / `runJob` | Aynı desen, yeni iş türleri (`vRender`, `vThumbs`, `vProxy` …) |
| İş kuyruğu + ön plan servisi | `pool`, `studioPool`, `WorkService` (1 sn gecikmeli başlatma) | Aynen; dışa aktarma ve proxy üretimi bu yoldan |
| DSP | `dsp/` (Loudness, TruePeak, Limiter, Biquad, Spectral, TimePitch, Beats, Mix …) | Kopyalanır, değiştirilmeden |
| YZ ses | `Denoiser` (DPDFNet), `Separator` (Spleeter), `asr/Transcriber` (Whisper + VAD) | Kopyalanır; altyazı için Transcriber |
| Konuşma analizi | `Speech` (duraklama, nefes, dolgu sesi) | Sessiz kısım kesme ve dolgu silme için |
| Ses çözme/kodlama | `AudioTools` (MediaCodec çözme, AAC + MediaMuxer) | Videodan ses çıkarma ve dışa aktarmada ses izi |
| Ses zaman çizelgesi | `Timeline.Engine` (karışım, ducking, canlı EQ) | Videonun ses karışımı için temel alınır |
| Derleme | `build_apk.py` (aapt → javac → dalvik-exchange → zipalign → apksigner) | Aynı; AAR desteği eklenir (bkz. 2.4) |
| i18n | `i18n*.js`, 9 dil | Başlangıçta tr + en, sonra diğerleri |

**Karar:** Ayrı uygulama, ayrı repo, ayrı paket adı. Ses Defteri'nin içine eklenmez, Ses Defteri hafif kalır. Ortak kod kopyalanır; ileride değişirse elle senkronlanır (ortak modül kurmak Gradle'sız yapıda gereksiz karmaşıklık).

### 2.2 Video motoru (yeni yazılacak kısım)
WebView, çok klipli videoyu akıcı önizleyemez. Bu yüzden önizleme ve render **yerel** yapılır:

```
WebView (arayüz, zaman çizelgesi, paneller)
   │  AND.vSetProject(json) / vSeek(t) / vPlay() / vPreviewRect(x,y,w,h)
   ▼
VideoEngine (Java)
   ├─ Decoders: her görünen klip için MediaCodec → SurfaceTexture (OES doku)
   ├─ Compositor (OpenGL ES 2.0): dönüşüm, kırpma, opaklık, filtre/LUT,
   │   ayarlar, geçişler, efekt shader'ları, yazı/çıkartma dokuları
   ├─ Önizleme: WebView'in altındaki TextureView'e çizer
   │   (WebView'de önizleme alanı şeffaf bırakılır)
   ├─ Ses: Timeline.Engine benzeri karışım → AudioTrack (ana saat sesi takip eder)
   └─ Dışa aktarma: aynı compositor → MediaCodec H.264 encoder (giriş Surface)
       + AAC ses → MediaMuxer → MP4
```

**Önerilen yol (karar 0.1'de kesinleşir): Android çerçeve API'leriyle kendi motorumuz.**
- Artısı: bağımlılık yok, APK küçük, Gradle'sız yapıya uyar, her şeyin kontrolü bizde.
- Eksisi: kare doğru arama (seek), çözücü havuzu ve senkronizasyon bizim işimiz.
- **Alternatif:** Google Media3 (ExoPlayer + Transformer + Effect). Daha az kod, ama AAR bağımlılıkları ve derleme betiğinde AAR desteği gerekir; APK büyür. Aşama 0'daki deneme kötü çıkarsa bu yola geçilir.

**Bilinen teknik riskler:**
- Telefonların aynı anda açabildiği donanım çözücü sayısı sınırlı (cihaza göre değişir). Önizlemede aynı anda en fazla 2 video çözülür (geçiş anı); bindirme (PIP) için proxy ve kare önbelleği kullanılır.
- Kare doğru arama: MediaCodec anahtar kareye (keyframe) atlar. Çözüm: düzenleme için **proxy dosyası** (düşük çözünürlük, sık anahtar kare, örn. 540p, her 0,5 sn'de bir anahtar kare) üretilir; dışa aktarma orijinalden yapılır.
- HDR ve 10-bit HEVC videolar: ilk sürümde SDR'a dönüştürülerek işlenir; renk doğruluğu sınırlı olabilir.
- Termal ve pil: dışa aktarma ön plan servisinde, ilerleme bildirimi ile.

### 2.3 Proje veri yapısı
Ses Defteri'nin `proj` yapısının genişletilmiş hali. Projeler `files/projects/<id>/project.json` olarak saklanır (localStorage değil; büyük projeler sığmaz).

```js
{
  id, name, created, updated, version: 1,
  preset: "yt" | "shorts" | "reels" | "igfeed" | "tiktok",   // tuval + güvenli alan + dışa aktarma ayarları
  canvas: { w: 1080, h: 1920, fps: 30, bg: "#000" | "blur" },
  tracks: [ { id, kind: "main"|"overlay"|"text"|"sticker"|"audio", mute, vol, locked } ],
  clips: [ {
    id, tr, src, proxy, kind: "video"|"image"|"audio"|"text"|"sticker",
    in, out, at,                  // kaynakta başlangıç/bitiş, zaman çizelgesinde yer (ms)
    speed: 1,                      // aşama 3'te: speedCurve [[t,v],...]
    reverse: false, freeze: null,
    transform: { x:0, y:0, scale:1, rot:0, flipH:false, flipV:false },
    crop: { l:0, t:0, r:0, b:0 },
    opacity: 1, blend: "normal",
    filter: { id, amount }, adjust: { exposure, contrast, saturation, temp, tint, highlights, shadows, vignette, sharpen },
    effects: [ { id, t0, t1, params } ],
    transIn: null, transOut: { id, dur },
    audio: { gain, fi, fo, mute, denoise, enhance, linked: true },
    text: { str, font, size, color, stroke, shadow, bg, align, anim: { in, out, loop } },
    keyframes: { "transform.scale": [ { t, v, ease } ], ... }   // aşama 3
  } ],
  captions: { trackId, style, words: [ { t0, t1, w } ] },
  markers: [ { t, label } ],
  export: { res: 1080, fps: 30, bitrate: "auto" }
}
```
Geri al/yinele: JSON anlık görüntüleri (Ses Defteri'deki `TL.undo/redo` gibi). Otomatik kayıt: her değişiklikten 2 sn sonra.

### 2.4 Derleme ve bağımlılıklar
- `build_apk.py` temel alınır.
- **Google Play Billing Library zorunlu** (premium satın alma için). AAR olarak gelir: betiğe "AAR aç → classes.jar'ı dex'e ekle → manifest girdilerini birleştir" adımı eklenir. Bu, Aşama 4'ten önce yapılır.
- Yazı tipleri: açık lisanslı (OFL) 6–8 yazı tipi APK'ya gömülür; Türkçe karakter desteği kontrol edilir.
- Filtreler: 3D LUT dosyaları (küçük PNG) APK'da.
- Ses efekti paketi: kendi ürettiğimiz ya da CC0 lisanslı 20–30 kısa ses (whoosh, pop, ding, tık, geçiş).
- Hedef APK boyutu: Whisper hariç **< 40 MB**.
- minSdk: 24 (Ses Defteri ile aynı) önerilir; 0.1'de cihaz testine göre gerekirse 26'ya çıkar.

---

## 3. Proje türü ön ayarları

Proje açılırken seçilir. Tuval, güvenli alan çizgileri ve dışa aktarma ayarları otomatik gelir. **Değerler `presets.json` dosyasında tutulur**, uygulama güncellemesi olmadan değiştirilebilir (platformlar bu değerleri sık değiştiriyor; kaynaklar bile farklı rakam veriyor).

| Ön ayar | Tuval | Uzunluk sınırı (Ekim 2026) | Güvenli alan notu |
|---|---|---|---|
| YouTube video | 1920×1080, 16:9 (4K premium) | 12 saat | — |
| YouTube Shorts | 1080×1920, 9:16 | 3 dk | Sağda aksiyon butonları, altta başlık |
| Instagram Reels | 1080×1920, 9:16 | 3 dk (uygulama içi) | Akışta ~4:5 kırpılır, altı açıklamayla kapanır |
| Instagram akış | 1080×1350 (4:5) / 1080×1080 (1:1) | — | — |
| TikTok | 1080×1920, 9:16 | 10 dk (uygulama içi) | Sağda aksiyon sütunu, altta açıklama ve ses |

- Varsayılan güvenli alan: dikey karede önemli yazı ortadaki yaklaşık **900×1400** alanda kalır. Önizlemede kesikli çizgiyle gösterilir, açılıp kapatılabilir.
- Süre sınırı aşılırsa uyarı (engellemez).
- Ortak dışa aktarma: MP4, H.264 (High), AAC 48 kHz, 30 veya 60 fps, 1080p için ~10–16 Mbps. Ses: yaklaşık −14 LUFS, tepe −1 dBTP (Ses Defteri'nin `Loudness` + `TruePeak` + `Limiter` sınıfları).

---

## 4. Özellik listesi ve aşamalar

Her aşama kendi içinde kullanılabilir bir uygulama üretir. Aşama bitmeden sonrakine geçilmez. **Kabul ölçütleri kullanıcının telefonunda doğrulanır.**

### Aşama 0 — Teknik deneme (spike), sürüm 0.1
Amaç: motor seçimini kanıtla. Arayüz çok basit olabilir.
- Galeriden 2 video seç, arka arkaya koy, aralarında 0,5 sn yumuşak geçiş.
- Önizleme: oynat/durdur, zaman çubuğunda sürükleyerek arama.
- 1080p dışa aktar, galeriye kaydet.
- Proxy üretimi (540p) ve proxy ile önizleme.

**Kabul ölçütleri:**
- [ ] Kullanıcının telefonunda önizleme 30 fps'te takılmadan oynuyor (geçiş anı dahil).
- [ ] Sürükleyerek aramada kare 200 ms içinde güncelleniyor.
- [ ] 60 sn'lik 1080p video, gerçek süresinin 2 katından kısa sürede dışa aktarılıyor.
- [ ] Ses ve görüntü dışa aktarılan dosyada senkron (1 kare içinde).
- [ ] Karar yazıldı: kendi motor mu, Media3 mü (bu dosyaya "Kararlar" bölümüne).

### Aşama 1 — MVP, sürüm 0.2–0.9
Her videoda kullanılan çekirdek. Ses tarafı Ses Defteri'nden hazır.

**Proje ve tuval**
- Proje listesi, yeni proje → ön ayar seçimi, otomatik kayıt, çökme sonrası kurtarma
- En boy oranı değiştirme, arka plan rengi veya bulanık arka plan
- Güvenli alan çizgileri
- Kapak karesi seçimi

**Klip düzenleme**
- Galeriden video/fotoğraf ekleme (çoklu seçim), zaman çizelgesinde sürükleyerek sıralama
- Böl, sil, çoğalt, değiştir (klibi başka medyayla değiştir, ayarları koru)
- Baştan/sondan kısaltma (tutamaçlarla), yakınlaştırılabilir zaman çizelgesi, mıknatıs/yapışma
- Sabit hız (0,25×–4×, ses perdesi korunarak — `TimePitch`)
- Kırp, döndür, aynala, dönüştür (iki parmakla ölçek/konum/döndürme), opaklık
- Geri al / yinele

**Yazı**
- Yazı katmanı: yazı tipi, boyut, renk, kontur, gölge, arka plan kutusu, hizalama
- Temel giriş/çıkış animasyonları: solma, kayma, pop, daktilo, zıplama

**Ses**
- Müzik ekleme (telefondaki ses dosyaları), seslendirme kaydı (video oynarken)
- Ses efekti paketi (20–30 kısa telifsiz ses)
- Ses düzeyi, giriş/çıkış solması, klip sesini kapat, görüntü ile sesi ayır (bağlantıyı kaldır)
- Videodan sesi çıkar — **sınırsız** (CapCut'ta 3 kullanım)
- Gürültü azaltma (spektral + DPDFNet) ve sesi iyileştir — **ücretsiz** (CapCut'ta gürültü azaltma ücretli)
- Tek tuşla −14 LUFS
- **Sessiz kısımları otomatik kes** (konuşmalı videolarda en büyük zaman kazancı; `Speech` sınıfı): kesilecek yerleri önce göster, kullanıcı onaylasın

**Dışa aktarma**
- 1080p, 30/60 fps, filigransız, galeriye kaydet + paylaş menüsü
- Ön plan servisinde, ilerleme yüzdesi, iptal

**Kabul ölçütleri:**
- [ ] Kullanıcı bir YouTube videosunu (yatay, 5–10 dk, konuşmalı) ve bir Reels'i (dikey, 30 sn, müzikli) baştan sona bu uygulamada düzenleyip yayınlayabiliyor.
- [ ] 10 dk'lık konuşma videosunda sessiz kısım kesme 1 dk'dan kısa sürüyor ve yanlış kesim yok denecek kadar az.
- [ ] Uygulama zorla kapatılınca proje son 2 sn'deki haliyle geri geliyor.
- [ ] 20 klipli projede zaman çizelgesi kaydırması akıcı.

### Aşama 2 — Hareket katmanı, sürüm 1.0
Reels/TikTok/Shorts videolarının "canlı" görünmesini sağlayan araçlar. **1.0 = ilk halka açık sürüm (Play Store).**

**Altyazı**
- Cihaz üstü otomatik altyazı (Whisper, Türkçe öncelikli; model isteğe bağlı indirilir)
- Kelime kelime vurgulu altyazı stilleri (3–5 stil), toplu biçimlendirme, altyazı izinde düzeltme
- Metinle düzenleme: transkriptten cümle sil → videodan da silinsin
- Dolgu kelimesi silme ("ııı", "şey")

**Geçişler (10):** kesme, yakınlaşma (zoom in/out), kaydırma (swipe/slide), dönme (spin), yumuşak geçiş (fade/dissolve), siyaha/beyaza geçiş, bulanık geçiş, glitch/RGB kayması, flash (beyaz patlama), hız rampalı geçiş.

**Efektler:** ani yakınlaşma (punch-in), kamera sarsıntısı, flash, glitch, bulanıklık/odak, VHS/retro gren, ışık sızıntısı, vinyet, bölünmüş ekran, önce-sonra (kaydırmalı).

**Renk:** 10–15 filtre (sıcak, soğuk, sinematik, siyah-beyaz, retro, canlı, ürün/yemek) yoğunluk ayarlı; temel ayarlar (pozlama, kontrast, doygunluk, sıcaklık, ton, parlak/gölge, vinyet, keskinlik).

**Diğer:** dondur, ters oynat, ritim (vuruş) işaretleri (`Beats`), alt bant (lower third) şablonları, küçük ok/emoji çıkartma paketi.

**Kabul ölçütleri:**
- [ ] Türkçe konuşmada otomatik altyazı, elle düzeltme gerektiren kelime oranı düşük (kullanıcı kendi videosunda değerlendirir).
- [ ] Kelime vurgusu konuşmayla senkron görünüyor (bkz. Risk R3).
- [ ] Efekt ve geçişler önizlemede 30 fps'i düşürmüyor.
- [ ] Play Store kapalı test yayınlandı.

### Aşama 3 — İleri kurgu, sürüm 1.x
- Bindirme (PIP) izi, çok iz
- Anahtar kare (konum, ölçek, döndürme, opaklık, ses düzeyi)
- Maske (doğrusal, ayna, daire, dikdörtgen; yumuşak kenar)
- Chroma key (yeşil perde)
- Hız eğrisi / velocity (hazır eğriler + elle)
- Karışım modları, HSL
- Ducking (konuşmada müziği kıs — Ses Defteri'de var)
- Sabitleme (CapCut'ta ücretli; telefon çekimlerinde sık ihtiyaç)
- Tek projeden çoklu dışa aktarma (16:9 + 9:16)
- 4K / 60 fps dışa aktarma

### Aşama 4 — Premium ve yayın
- Play Billing entegrasyonu (tek seferlik, tüketilmeyen ürün)
- Premium kilitleri (bkz. Bölüm 5), "Geri yükle" düğmesi
- Marka kiti: logo, yazı tipi, renk, intro/outro bir kez kaydedilir, tek dokunuşla eklenir
- Play Store mağaza sayfası, gizlilik politikası, ekran görüntüleri

Not: Aşama 3 ve 4 paralel yürüyebilir; premium kilitleri ancak premium özellikler hazır olunca anlamlı.

### Sonra düşünülecekler (planlı değil)
Uygulama içi çekim + teleprompter, platform güvenli alanlarının otomatik güncellenmesi, YouTube bölüm zaman damgası önerisi, iOS sürümü.

---

## 5. İş modeli

- **Abonelik yok.** Premium tek seferlik, ömür boyu, platform başına (iOS gelirse ayrı satın alma).
- **İlke:** Ücretsiz sürümle filigransız, eksiksiz bir video çıkarılabilmeli. Premium temel işi kilitlemez; zaman kazandırır ve kaliteyi artırır.

| Ücretsiz | Premium |
|---|---|
| Aşama 1'in tamamı | 4K ve 60 fps dışa aktarma |
| Filigransız 1080p dışa aktarma | Tek projeden çoklu dışa aktarma |
| Otomatik altyazı + temel stiller | Ek altyazı stilleri, geçiş ve efekt paketleri |
| 10 geçiş, temel efektler, filtreler | Marka kiti |
| Ön ayarlar ve güvenli alanlar | İleri kurgu: anahtar kare, maske, chroma key, hız eğrisi |

- **Fiyat önerisi:** Türkiye'de 149–199 TL (CapCut'un aylık ücreti 249,99–399,99 TL), yurtdışı 4,99–9,99 $. Play Console bölgesel fiyatlandırmasıyla ayarlanır.
- **Google payı:** 15% programına kayıtla yıllık ilk 1 milyon $ için %15. (ABD/AB/İngiltere'de 30 Haziran 2026'dan beri %10 hizmet + %5 faturalama.) Geliştirici hesabı tek seferlik 25 $.
- Satın alma Google hesabına bağlı; telefon değişince geri yüklenir; satın alma bilgisi cihazda önbelleğe alınır, internetsiz de premium açık kalır.

---

## 6. Riskler

| No | Risk | Etki | Önlem |
|---|---|---|---|
| R1 | Kendi motorumuzda önizleme akıcılığı / kare doğru arama | Yüksek | Aşama 0 spike + proxy dosyaları; olmazsa Media3 |
| R2 | Gradle'sız derlemede AAR (Billing, gerekirse Media3) | Orta | `build_apk.py`'ye AAR açma adımı; Aşama 4'ten önce |
| R3 | Whisper kelime düzeyinde zaman damgası vermeyebilir (segment düzeyinde verir) | Orta | Aşama 2 başında doğrula; gerekirse kelime zamanlarını hece/enerjiye göre dağıtma ya da zaman damgası veren başka bir sherpa-onnx modeli |
| R4 | Donanım çözücü sayısı sınırı (PIP, geçiş) | Orta | Aynı anda en fazla 2 canlı çözücü; diğerleri proxy/kare önbelleği |
| R5 | HDR / 10-bit HEVC telefon videoları | Orta | İlk sürümde SDR'a dönüştür; renk sapmasını kullanıcıya not et |
| R6 | Kapsamın şişmesi | Yüksek | Kapsam dışı listesi; her yeni fikir "Sonra düşünülecekler"e yazılır |
| R7 | Telif: müzik ve ses efektleri | Orta | Büyük kütüphane yok; yalnızca kendi/CC0 ses paketi + kullanıcının kendi dosyaları |

---

## 7. Açık kararlar (kullanıcı verecek)
1. Uygulama adı ve paket adı ("Video Defteri" çalışma adı).
2. Yeni GitHub reposu adı (öneri: `video-defteri`), herkese açık mı.
3. Motor: kendi motor mu, Media3 mü (Aşama 0 sonucuna göre).
4. Dağıtım: önce GitHub APK mı, doğrudan Play kapalı test mi.
5. Premium fiyatı.
6. Sabitleme Aşama 3'te mi kalsın, kapsam dışına mı alınsın.

## 8. Kararlar günlüğü
| Tarih | Karar |
|---|---|
| 25.10.2026 | Kapsam: yalnızca YouTube/Instagram/TikTok üreticisi ihtiyaçları; CapCut ekstraları yok |
| 25.10.2026 | İş modeli: abonelik yok, çoğu ücretsiz, premium tek seferlik ve ucuz |
| 25.10.2026 | Ses Defteri'nin içine değil, ayrı uygulama; ses altyapısı oradan kopyalanır |

## 9. Aşama 0 uygulama notları (0.3, 25 Ekim 2026)
- Kendi motor yolu denendi (Media3 değil): `VideoEngine.java` — `Dec` (MediaCodec → SurfaceTexture, kare doğru arama: anahtar kareden çöz, hedefe kadar göstermeden atla), `Comp` (OpenGL ES 2 birleştirici: sığdırma, döndürme, yumuşak geçiş, altyazı), `encode()` (aynı birleştirici → H.264, sabit 30 fps).
- Önizleme: WebView'in üstünde `TextureView` (sayfa `AND.vRect` ile yerini bildirir; açılır pencerelerde gizlenir). Saat = ses (`Timeline.play/pos`), ses yoksa duvar saati.
- Proxy: `files/vproxy/<anahtar>.mp4` (kısa kenar 540, 0,5 sn'de bir anahtar kare) + `.wav` (48 kHz ses; önizleme ve dışa aktarma sesi buradan).
- Geçiş: klip başına `tx` (0 / 0,5 sn), klipler birbirinin üstüne biner; ses de aynı sürede çapraz geçer.
- Proje hâlâ localStorage `vproj`'da; dosyaya taşıma Aşama 1'de.
- Kabul ölçütleri telefonda bekleniyor; karar satırı ölçümden sonra yazılacak.
