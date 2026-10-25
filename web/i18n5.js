/* Video Defteri 1.6 — voice separation, ducking, recording onto the timeline, beats (merged into SD_I18N). */
(function () {
const A = {
tr: {
  tlSep: "Sesi ayır", tlBeats: "Ritim", tlRec: "Kayıt", tlRecStop: "Kaydı bitir", tlRecHint: "Diğer katmanlar çalarken konuşabilirsin; kulaklık takarsan müzik kayda karışmaz.",
  tlDuck: "Konuşma gelince kıs", tlDuckD: "Diğer katmanlarda ses varken bu katman otomatik kısılır (fon müziği için).", tlDuckAmt: "Kısma miktarı",
  sepNo: "Ses ayırma bu telefonda çalışmıyor.", sepD: "Yapay zekâ konuşmayı/vokali arka plandan ayırır. Uzun seslerde biraz sürebilir.",
  sepKeep: "Sesi koru", sepKeepD: "Müzik ve ortam sesleri atılır, konuşma kalır.", sepRemove: "Sesi kaldır", sepRemoveD: "Konuşma/vokal atılır, müzik kalır.",
  sepBoth: "İkisini de ayır", sepBothD: "Ses bu parçada kalır, müzik yeni bir katmana konur.", sepMusic: "müzik", sepVoice: "ses", sepDone: "Ses ayrıldı"
},
en: {
  tlSep: "Separate voice", tlBeats: "Beats", tlRec: "Record", tlRecStop: "Stop recording", tlRecHint: "You can talk while the other tracks play; with headphones the music stays out of the recording.",
  tlDuck: "Duck under speech", tlDuckD: "This track gets quieter automatically while the other tracks have sound (for background music).", tlDuckAmt: "Duck amount",
  sepNo: "Voice separation doesn't work on this phone.", sepD: "AI separates the speech/vocals from the background. Long audio can take a while.",
  sepKeep: "Keep voice", sepKeepD: "Music and room sound are removed, the voice stays.", sepRemove: "Remove voice", sepRemoveD: "Speech/vocals are removed, the music stays.",
  sepBoth: "Split both", sepBothD: "The voice stays in this clip, the music goes to a new track.", sepMusic: "music", sepVoice: "voice", sepDone: "Voice separated"
},
de: {
  tlSep: "Stimme trennen", tlBeats: "Beats", tlRec: "Aufnehmen", tlRecStop: "Aufnahme beenden", tlRecHint: "Du kannst sprechen, während die anderen Spuren laufen; mit Kopfhörern bleibt die Musik aus der Aufnahme.",
  tlDuck: "Bei Sprache absenken", tlDuckD: "Diese Spur wird automatisch leiser, solange auf den anderen Spuren etwas zu hören ist (für Hintergrundmusik).", tlDuckAmt: "Absenkung",
  sepNo: "Stimmtrennung funktioniert auf diesem Telefon nicht.", sepD: "KI trennt Sprache/Gesang vom Hintergrund. Bei langen Aufnahmen kann es dauern.",
  sepKeep: "Stimme behalten", sepKeepD: "Musik und Raumklang werden entfernt, die Stimme bleibt.", sepRemove: "Stimme entfernen", sepRemoveD: "Sprache/Gesang wird entfernt, die Musik bleibt.",
  sepBoth: "Beides trennen", sepBothD: "Die Stimme bleibt in diesem Clip, die Musik kommt auf eine neue Spur.", sepMusic: "Musik", sepVoice: "Stimme", sepDone: "Stimme getrennt"
},
fr: {
  tlSep: "Séparer la voix", tlBeats: "Temps", tlRec: "Enregistrer", tlRecStop: "Arrêter l’enregistrement", tlRecHint: "Tu peux parler pendant que les autres pistes jouent ; avec un casque, la musique ne passe pas dans l’enregistrement.",
  tlDuck: "Baisser sous la voix", tlDuckD: "Cette piste baisse automatiquement quand les autres pistes ont du son (pour une musique de fond).", tlDuckAmt: "Atténuation",
  sepNo: "La séparation de voix ne fonctionne pas sur ce téléphone.", sepD: "L’IA sépare la parole/le chant du fond. Pour un long son, cela peut prendre du temps.",
  sepKeep: "Garder la voix", sepKeepD: "La musique et l’ambiance sont retirées, la voix reste.", sepRemove: "Retirer la voix", sepRemoveD: "La parole/le chant est retiré, la musique reste.",
  sepBoth: "Séparer les deux", sepBothD: "La voix reste dans ce clip, la musique va sur une nouvelle piste.", sepMusic: "musique", sepVoice: "voix", sepDone: "Voix séparée"
},
es: {
  tlSep: "Separar voz", tlBeats: "Ritmo", tlRec: "Grabar", tlRecStop: "Terminar grabación", tlRecHint: "Puedes hablar mientras suenan las otras pistas; con auriculares la música no entra en la grabación.",
  tlDuck: "Bajar con la voz", tlDuckD: "Esta pista baja sola mientras las otras pistas tienen sonido (para música de fondo).", tlDuckAmt: "Cuánto bajar",
  sepNo: "La separación de voz no funciona en este teléfono.", sepD: "La IA separa la voz/el canto del fondo. En audios largos puede tardar.",
  sepKeep: "Conservar voz", sepKeepD: "Se quitan la música y el ambiente; queda la voz.", sepRemove: "Quitar voz", sepRemoveD: "Se quita la voz/el canto; queda la música.",
  sepBoth: "Separar ambos", sepBothD: "La voz queda en este clip y la música va a una pista nueva.", sepMusic: "música", sepVoice: "voz", sepDone: "Voz separada"
},
pt: {
  tlSep: "Separar voz", tlBeats: "Batidas", tlRec: "Gravar", tlRecStop: "Parar gravação", tlRecHint: "Você pode falar enquanto as outras faixas tocam; com fones a música não entra na gravação.",
  tlDuck: "Abaixar com a voz", tlDuckD: "Esta faixa abaixa sozinha enquanto as outras faixas têm som (para música de fundo).", tlDuckAmt: "Quanto abaixar",
  sepNo: "A separação de voz não funciona neste telefone.", sepD: "A IA separa a fala/o vocal do fundo. Em áudios longos pode demorar.",
  sepKeep: "Manter voz", sepKeepD: "Música e ambiente são removidos, a voz fica.", sepRemove: "Remover voz", sepRemoveD: "A fala/o vocal é removido, a música fica.",
  sepBoth: "Separar os dois", sepBothD: "A voz fica neste clipe e a música vai para uma nova faixa.", sepMusic: "música", sepVoice: "voz", sepDone: "Voz separada"
},
ru: {
  tlSep: "Отделить голос", tlBeats: "Ритм", tlRec: "Запись", tlRecStop: "Остановить запись", tlRecHint: "Можно говорить, пока играют другие дорожки; в наушниках музыка не попадёт в запись.",
  tlDuck: "Приглушать под голос", tlDuckD: "Дорожка автоматически становится тише, пока на других дорожках есть звук (для фоновой музыки).", tlDuckAmt: "Насколько приглушать",
  sepNo: "Разделение голоса не работает на этом телефоне.", sepD: "ИИ отделяет речь/вокал от фона. Длинный звук может обрабатываться долго.",
  sepKeep: "Оставить голос", sepKeepD: "Музыка и фон убираются, голос остаётся.", sepRemove: "Убрать голос", sepRemoveD: "Речь/вокал убирается, музыка остаётся.",
  sepBoth: "Разделить оба", sepBothD: "Голос остаётся в этом клипе, музыка уходит на новую дорожку.", sepMusic: "музыка", sepVoice: "голос", sepDone: "Голос отделён"
},
ar: {
  tlSep: "فصل الصوت", tlBeats: "الإيقاع", tlRec: "تسجيل", tlRecStop: "إيقاف التسجيل", tlRecHint: "يمكنك التحدث أثناء تشغيل المسارات الأخرى؛ مع سماعات الرأس لا تدخل الموسيقى في التسجيل.",
  tlDuck: "خفض عند الكلام", tlDuckD: "ينخفض هذا المسار تلقائيًا ما دام في المسارات الأخرى صوت (للموسيقى الخلفية).", tlDuckAmt: "مقدار الخفض",
  sepNo: "فصل الصوت لا يعمل على هذا الهاتف.", sepD: "يفصل الذكاء الاصطناعي الكلام/الغناء عن الخلفية. قد يستغرق الصوت الطويل بعض الوقت.",
  sepKeep: "إبقاء الصوت", sepKeepD: "تُزال الموسيقى وأصوات المكان ويبقى الصوت.", sepRemove: "إزالة الصوت", sepRemoveD: "يُزال الكلام/الغناء وتبقى الموسيقى.",
  sepBoth: "فصل الاثنين", sepBothD: "يبقى الصوت في هذا المقطع وتنتقل الموسيقى إلى مسار جديد.", sepMusic: "موسيقى", sepVoice: "صوت", sepDone: "تم فصل الصوت"
},
zh: {
  tlSep: "分离人声", tlBeats: "节拍", tlRec: "录音", tlRecStop: "停止录音", tlRecHint: "其他音轨播放时可以说话；戴耳机时音乐不会录进去。",
  tlDuck: "说话时压低", tlDuckD: "其他音轨有声音时，这条音轨会自动变小（适合背景音乐）。", tlDuckAmt: "压低幅度",
  sepNo: "此手机不支持人声分离。", sepD: "AI 会把说话/人声与背景分开。较长的音频可能需要一些时间。",
  sepKeep: "保留人声", sepKeepD: "去掉音乐和环境声，保留人声。", sepRemove: "去除人声", sepRemoveD: "去掉说话/人声，保留音乐。",
  sepBoth: "两者都分离", sepBothD: "人声留在此片段，音乐放到新音轨。", sepMusic: "音乐", sepVoice: "人声", sepDone: "已分离人声"
}
};
for (const l in A) window.SD_I18N[l] = Object.assign(window.SD_I18N[l] || {}, A[l]);
})();
