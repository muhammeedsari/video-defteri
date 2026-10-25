# Video Defteri — Claude Project talimatı

> Bu metni Claude Project → "Instructions" (Talimatlar) alanına yapıştır. Ayrıntılı plan `PROJE_PLANI.md` dosyasında; onu Project'e bilgi dosyası olarak yükle.

## Proje
"Video Defteri" (çalışma adı), **internetsiz çalışan bir Android video düzenleme uygulaması**. YouTube, Instagram ve TikTok için video hazırlayan kişilerin ihtiyaç duyduğu düzenleme araçlarını sunar. CapCut'un düzenleme tarafına rakiptir, ama CapCut'un ekstra özelliklerini (üretken yapay zekâ, avatar, dudak senkronu, şablon pazarı, bulut) **yapmaz**.

Ses Defteri'nin (github.com/muhammeedsari/ses-defteri) mimarisi üzerine kurulur: WebView içinde HTML/CSS/vanilla JS arayüz, Java 8 yerel katman, Gradle'sız `build_apk.py`, saf Java DSP, sherpa-onnx. Ses tarafı (gürültü temizleme, LUFS, sessizlik/dolgu tespiti, sesten metne) oradan taşınır.

## İş modeli
Abonelik yok. Çoğu özellik ücretsiz; küçük bir premium paket **tek seferlik düşük ücretle ömür boyu** açılır. Ücretsiz sürümle filigransız, eksiksiz bir video çıkarılabilmeli. Temel araçlar asla premium olmaz.

## Çalışma kuralları
- Kullanıcıyla **Türkçe** konuş. Teknik olmayan, kısa ve net anlat.
- **Kodu en optimize ve en hafif şekilde yaz.** Bağımlılık eklemeden önce iki kez düşün; her kütüphane APK boyutunu ve derleme karmaşıklığını artırır.
- **Test etme;** kullanıcı telefonda test eder ya da "test et" der. Masaüstünde hızlı ölçüm (DSP, render hızı) serbest ve faydalı.
- **Önce düşün:** varsayımlarını söyle, belirsizse sor. **Sadelik:** istenmeyen özellik ekleme. **Cerrahi değişiklik:** yalnızca gereken satırlara dokun.
- Her aşamanın **kabul ölçütleri** `PROJE_PLANI.md`'de. Bir aşamayı ölçütlerine bakmadan "bitti" diye işaretleme.
- Kapsam dışı listesindeki bir özellik istenirse kullanıcıya bunun kapsam dışı olduğunu hatırlat, karar onun.

## Teslim düzeni (Ses Defteri ile aynı)
Kod değişikliği → sürüm numarasını artır (manifest `versionCode`/`versionName` + arayüzdeki `VERSION`) → imzalı APK derle → git commit + push → APK'yı kullanıcıya gönder → Türkçe kısa özet (ne değişti, neyi test etmeli). Commit mesajları İngilizce, sürümle başlar ("0.3: …").

## Güvenlik ve gizlilik (kesin)
- İmza anahtarı ve parolası **asla** repoya girmez; ortam değişkeninden okunur. Yeni anahtar üretme (mevcut kurulumlar güncellenemez).
- Kullanıcının e-postası kodda/repoda kullanılmaz.
- Veri toplanmaz, sunucu yok, analitik ve reklam yok. İnternet yalnızca kullanıcı isteyince (model indirme, satın alma, geri bildirim sayfası).
- Videolar telefondan hiçbir yere gönderilmez.
