# Video Defteri 1.0 — önerilen kapsam (25 Ekim 2026, kullanıcı "başlayalım" dedi)

Hepsi internetsiz, MediaCodec/MediaExtractor/MediaMuxer ile (AudioTools.java'da ses tarafı hazır).

1. **Video kitaplığı:** telefondaki videolar, küçük resim, oynatıcı.
2. **Kes ve birleştir:** baş/son kırp, ortadan böl, videoları arka arkaya ekle; mümkünse yeniden kodlamadan (anahtar kare sınırında).
3. **Sesi düzenle:** videonun sesini çıkar → Ses Defteri araçları (Temizle, YZ gürültü silme, EQ, seviye) → videoya geri koy (görüntü kopyalanır, ses AAC).
4. **Müzik ekle:** arka plan müziği + ducking (var).
5. **Otomatik altyazı:** Whisper ile metne dök, SRT kaydet ya da videoya göm.
6. **Dışa aktar:** 1080p/720p MP4, paylaş.

Sonraya: yazı/çıkartma, filtreler, geçişler, hız, dikey/kare kırpma.

## 0.2 (25 Ekim 2026) — ilk video sürümü
- **Video sekmesi** (`web/body.html`, "Video Defteri: video tab" bloğu; `VP` durumu, localStorage `vproj`): telefondaki videolar (`vList`), + ile dosyadan seçme (`AND.pickVideos` → `__sdVids`), önizleme (`<video>` ← `/__vid?u=` Range destekli, `MainActivity.video`), küçük resimler (`/__thumb?u=&t=`), kırp/böl/sırala/çoğalt/sil, geri al.
- **Ses:** "Sesi düzenle" videoların sesini stüdyo zaman çizelgesine koyar (katman 1, sıra konumlarında) → tüm ses araçları; "Müzik ekle" müziği alt katmana koyar (−6 dB, ducking açık). Video değişirse `sig` uyuşmaz → "Sesi yeniden oluştur".
- **Altyazı:** `vSound` (son ses, WAV) → `transcribe` → düzenlenebilir satırlar, SRT paylaş, videoya yaz (GL ile).
- **Dışa aktar** (`VideoEdit.render`): ses (Timeline.render) → AAC; görüntü: biçimler aynı + anahtar karede başlıyorsa kopya, değilse MediaCodec çöz → OpenGL (sığdır, döndür, altyazı) → H.264 (1080p/720p/orijinal ≤1920). `VideoMux.join` birleştirir, Movies/Video Defteri'ye yazar.
- Bilinen sınırlar: önizlemede klip geçişinde kısa duraklama; B-kareli videolarda kopya yolunda kesim sonu küçük bozulma olabilir; HDR videolar SDR'a dönüşmez (renk soluk olabilir).
