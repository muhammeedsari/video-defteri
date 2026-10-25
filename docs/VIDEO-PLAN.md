# Video Defteri 1.0 — önerilen kapsam (25 Ekim 2026, kullanıcı "başlayalım" dedi)

Hepsi internetsiz, MediaCodec/MediaExtractor/MediaMuxer ile (AudioTools.java'da ses tarafı hazır).

1. **Video kitaplığı:** telefondaki videolar, küçük resim, oynatıcı.
2. **Kes ve birleştir:** baş/son kırp, ortadan böl, videoları arka arkaya ekle; mümkünse yeniden kodlamadan (anahtar kare sınırında).
3. **Sesi düzenle:** videonun sesini çıkar → Ses Defteri araçları (Temizle, YZ gürültü silme, EQ, seviye) → videoya geri koy (görüntü kopyalanır, ses AAC).
4. **Müzik ekle:** arka plan müziği + ducking (var).
5. **Otomatik altyazı:** Whisper ile metne dök, SRT kaydet ya da videoya göm.
6. **Dışa aktar:** 1080p/720p MP4, paylaş.

Sonraya: yazı/çıkartma, filtreler, geçişler, hız, dikey/kare kırpma.
