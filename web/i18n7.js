/* Video Defteri 1.7.1 — merge, track mute badges, AI noise removal, "nothing to fix" (merged into SD_I18N). */
(function () {
const A = {
tr: { tlMerge: "Birleştir", tlNoMerge: "Birleştirmek için aynı katmanda sağında bir parça olmalı", tlMuted: "Katman {n} sessize alındı", tlUnmuted: "Katman {n} sesi açıldı",
  fxNone: "Bu seste \"{x}\" gerektiren bir sorun bulunamadı; ses değiştirilmedi.", fxNoneShort: "Sorun bulunamadı, dokunulmadı",
  op_aidenoise: "Gürültüyü yapay zekâyla sil", fxd_aidenoise: "En güçlü temizlik: sadece konuşma kalır (Sesi ayır modeli)" },
en: { tlMerge: "Merge", tlNoMerge: "To merge, there must be a clip to its right on the same track", tlMuted: "Track {n} muted", tlUnmuted: "Track {n} unmuted",
  fxNone: "Nothing in this audio needs \"{x}\"; the sound was left unchanged.", fxNoneShort: "Nothing found, left as is",
  op_aidenoise: "Remove noise with AI", fxd_aidenoise: "Strongest cleaning: only the voice remains (voice separation model)" },
de: { tlMerge: "Zusammenfügen", tlNoMerge: "Zum Zusammenfügen muss rechts auf derselben Spur ein Clip liegen", tlMuted: "Spur {n} stumm", tlUnmuted: "Spur {n} wieder hörbar",
  fxNone: "In diesem Audio gibt es nichts für „{x}“; der Ton blieb unverändert.", fxNoneShort: "Nichts gefunden, unverändert",
  op_aidenoise: "Rauschen per KI entfernen", fxd_aidenoise: "Stärkste Reinigung: nur die Stimme bleibt (Stimmtrennungsmodell)" },
fr: { tlMerge: "Fusionner", tlNoMerge: "Pour fusionner, il faut un clip à droite sur la même piste", tlMuted: "Piste {n} coupée", tlUnmuted: "Piste {n} réactivée",
  fxNone: "Rien dans ce son ne nécessite « {x} » ; il n’a pas été modifié.", fxNoneShort: "Rien trouvé, inchangé",
  op_aidenoise: "Supprimer le bruit par IA", fxd_aidenoise: "Nettoyage le plus fort : seule la voix reste (modèle de séparation)" },
es: { tlMerge: "Unir", tlNoMerge: "Para unir, debe haber un clip a su derecha en la misma pista", tlMuted: "Pista {n} silenciada", tlUnmuted: "Pista {n} activada",
  fxNone: "Nada en este audio necesita «{x}»; el sonido no se modificó.", fxNoneShort: "Nada encontrado, sin cambios",
  op_aidenoise: "Quitar ruido con IA", fxd_aidenoise: "La limpieza más fuerte: solo queda la voz (modelo de separación)" },
pt: { tlMerge: "Juntar", tlNoMerge: "Para juntar, precisa haver um clipe à direita na mesma faixa", tlMuted: "Faixa {n} silenciada", tlUnmuted: "Faixa {n} com som",
  fxNone: "Nada neste áudio precisa de “{x}”; o som não foi alterado.", fxNoneShort: "Nada encontrado, sem mudança",
  op_aidenoise: "Remover ruído com IA", fxd_aidenoise: "A limpeza mais forte: só a voz fica (modelo de separação)" },
ru: { tlMerge: "Объединить", tlNoMerge: "Для объединения справа на той же дорожке должен быть клип", tlMuted: "Дорожка {n} заглушена", tlUnmuted: "Дорожка {n} включена",
  fxNone: "В этом звуке нечего исправлять для «{x}»; звук не изменён.", fxNoneShort: "Ничего не найдено, без изменений",
  op_aidenoise: "Убрать шум с помощью ИИ", fxd_aidenoise: "Самая сильная очистка: остаётся только голос (модель разделения)" },
ar: { tlMerge: "دمج", tlNoMerge: "للدمج يجب وجود مقطع على يمينه في المسار نفسه", tlMuted: "تم كتم المسار {n}", tlUnmuted: "تم تشغيل صوت المسار {n}",
  fxNone: "لا شيء في هذا الصوت يحتاج «{x}»؛ لم يتغيّر الصوت.", fxNoneShort: "لم يُعثر على شيء، دون تغيير",
  op_aidenoise: "إزالة الضجيج بالذكاء الاصطناعي", fxd_aidenoise: "أقوى تنظيف: يبقى الصوت البشري فقط (نموذج الفصل)" },
zh: { tlMerge: "合并", tlNoMerge: "要合并，同一音轨右侧需要有片段", tlMuted: "音轨 {n} 已静音", tlUnmuted: "音轨 {n} 已取消静音",
  fxNone: "这段音频不需要“{x}”，声音未改变。", fxNoneShort: "未发现问题，未改动",
  op_aidenoise: "AI 去噪", fxd_aidenoise: "最强清理：只保留人声（人声分离模型）" }
};
for (const l in A) window.SD_I18N[l] = Object.assign(window.SD_I18N[l] || {}, A[l]);
})();
