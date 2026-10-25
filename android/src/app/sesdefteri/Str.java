package app.sesdefteri;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** The few texts the phone shows outside the page (notifications, choosers), in the app's language. */
final class Str {
    private static final String[] KEYS = {"recTitle", "recText", "chRec", "chPlay", "share", "pick", "prev", "play", "pause", "next", "chWork", "workTitle", "workText"};
    private static final Map<String, String[]> T = new HashMap<>();

    static {
        T.put("tr", new String[]{"Video Defteri kaydediyor", "Kayda dönmek için dokun", "Kayıt", "Çalan ses", "Paylaş", "Ses dosyası seç", "Önceki", "Oynat", "Duraklat", "Sonraki", "İşlemler", "Video Defteri çalışıyor", "İyileştirme, metin dökümü veya miks sürüyor"});
        T.put("en", new String[]{"Video Defteri is recording", "Tap to return to the recording", "Recording", "Now playing", "Share", "Choose audio files", "Previous", "Play", "Pause", "Next", "Processing", "Video Defteri is working", "Enhancing, transcribing or mixing"});
        T.put("de", new String[]{"Video Defteri nimmt auf", "Tippen, um zur Aufnahme zurückzukehren", "Aufnahme", "Wiedergabe", "Teilen", "Audiodateien auswählen", "Zurück", "Abspielen", "Pause", "Weiter", "Verarbeitung", "Video Defteri arbeitet", "Verbessern, Transkribieren oder Mischen läuft"});
        T.put("fr", new String[]{"Video Defteri enregistre", "Touchez pour revenir à l'enregistrement", "Enregistrement", "Lecture en cours", "Partager", "Choisir des fichiers audio", "Précédent", "Lire", "Pause", "Suivant", "Traitement", "Video Defteri travaille", "Amélioration, transcription ou mixage en cours"});
        T.put("es", new String[]{"Video Defteri está grabando", "Toca para volver a la grabación", "Grabación", "Reproduciendo", "Compartir", "Elegir archivos de audio", "Anterior", "Reproducir", "Pausa", "Siguiente", "Procesamiento", "Video Defteri está trabajando", "Mejorando, transcribiendo o mezclando"});
        T.put("pt", new String[]{"Video Defteri está gravando", "Toque para voltar à gravação", "Gravação", "Tocando agora", "Compartilhar", "Escolher arquivos de áudio", "Anterior", "Tocar", "Pausar", "Próxima", "Processamento", "Video Defteri está trabalhando", "Melhorando, transcrevendo ou mixando"});
        T.put("ru", new String[]{"Video Defteri записывает", "Нажмите, чтобы вернуться к записи", "Запись", "Сейчас играет", "Поделиться", "Выбрать аудиофайлы", "Назад", "Играть", "Пауза", "Далее", "Обработка", "Video Defteri работает", "Улучшение, расшифровка или сведение"});
        T.put("ar", new String[]{"Video Defteri يسجّل الآن", "اضغط للعودة إلى التسجيل", "التسجيل", "قيد التشغيل", "مشاركة", "اختر ملفات صوتية", "السابق", "تشغيل", "إيقاف مؤقت", "التالي", "المعالجة", "Video Defteri يعمل", "جارٍ التحسين أو التفريغ النصي أو المزج"});
        T.put("zh", new String[]{"Video Defteri 正在录音", "点按返回录音", "录音", "正在播放", "分享", "选择音频文件", "上一首", "播放", "暂停", "下一首", "处理", "Video Defteri 正在处理", "正在优化、转写或混音"});
    }

    private Str() { }

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("sesdefteri", Context.MODE_PRIVATE);
    }

    static String lang(Context c) {
        String l = prefs(c).getString("lang", "");
        if (T.containsKey(l)) return l;
        return "en";   // until the user picks a language on first start
    }

    static String get(Context c, String key) {
        String[] row = T.get(lang(c));
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return row[i];
        return key;
    }
}
