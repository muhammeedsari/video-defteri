/* Video Defteri 1.8 — MP3, file names, trash, settings (merged into SD_I18N). */
(function () {
const L = "LAME (LGPL) · sherpa-onnx (Apache-2.0) · ONNX Runtime (MIT) · Whisper (MIT) · Spleeter (MIT) · Silero VAD (MIT)";
const A = {
tr: {
  delTrash: "“{title}” çöp kutusuna taşınır; {n} gün içinde geri yükleyebilirsin.", toTrash: "Çöp kutusuna taşındı", trash: "Çöp kutusu",
  trashD: "Buradaki kayıtlar {n} gün sonra kalıcı olarak silinir.", trashEmpty: "Çöp kutusu boş.", trashLeft: "{n} gün kaldı", restore: "Geri yükle", restored: "Geri yüklendi", trashAll: "Hepsini sil",
  tlSaveAs: "Kayıtlara kaydet ({f})", tlShareAs: "Paylaş ({f})", tag_clean: "temiz", tag_edit: "duzenlendi", tplRecWord: "Kayit",
  setRec: "Kayıt", setQuality: "Kayıt kalitesi", q_std: "Standart (44,1 kHz mono)", q_high: "Yüksek (48 kHz mono)", q_stereo: "Müzik (48 kHz stereo)",
  setMic: "Mikrofon", mic_auto: "Otomatik", mic_builtin: "Telefonun mikrofonu", mic_wired: "Kablolu kulaklık", mic_usb: "USB mikrofon",
  setCall: "Aramada duraklat", setCallD: "Arama gelince kayıt durur, bitince devam eder.",
  setClean: "Temizlik", setStrength: "Varsayılan şiddet", setAuto: "Kayıt bitince otomatik temizle", setAutoD: "Teşhis ve düzeltme kendiliğinden yapılır; sen dinleyip kaydedersin.",
  setExport: "Dışa aktarma", setFmt: "Varsayılan biçim", setName: "Dosya adı", tplEx: "Örnek: {x}", tplSample: "Röportaj", tplCustom: "Özel… ({name} {date} {time} {tag})",
  setStorage: "Depolama", setTrash: "Silinen kayıtlar", trash_30: "30 gün çöp kutusunda kalsın", trash_7: "7 gün çöp kutusunda kalsın", trash_0: "Hemen silinsin",
  licenses: L
},
en: {
  delTrash: "“{title}” goes to the trash; you can restore it within {n} days.", toTrash: "Moved to trash", trash: "Trash",
  trashD: "Recordings here are deleted for good after {n} days.", trashEmpty: "The trash is empty.", trashLeft: "{n} days left", restore: "Restore", restored: "Restored", trashAll: "Delete all",
  tlSaveAs: "Save to recordings ({f})", tlShareAs: "Share ({f})", tag_clean: "clean", tag_edit: "edited", tplRecWord: "Recording",
  setRec: "Recording", setQuality: "Recording quality", q_std: "Standard (44.1 kHz mono)", q_high: "High (48 kHz mono)", q_stereo: "Music (48 kHz stereo)",
  setMic: "Microphone", mic_auto: "Automatic", mic_builtin: "Phone microphone", mic_wired: "Wired headset", mic_usb: "USB microphone",
  setCall: "Pause during calls", setCallD: "Recording stops when a call comes in and continues afterwards.",
  setClean: "Cleaning", setStrength: "Default strength", setAuto: "Clean automatically after recording", setAutoD: "Diagnosis and fixes run by themselves; you listen and save.",
  setExport: "Export", setFmt: "Default format", setName: "File name", tplEx: "Example: {x}", tplSample: "Interview", tplCustom: "Custom… ({name} {date} {time} {tag})",
  setStorage: "Storage", setTrash: "Deleted recordings", trash_30: "Keep in trash for 30 days", trash_7: "Keep in trash for 7 days", trash_0: "Delete right away",
  licenses: L
},
de: {
  delTrash: "„{title}“ kommt in den Papierkorb; du kannst es {n} Tage lang wiederherstellen.", toTrash: "In den Papierkorb verschoben", trash: "Papierkorb",
  trashD: "Aufnahmen hier werden nach {n} Tagen endgültig gelöscht.", trashEmpty: "Der Papierkorb ist leer.", trashLeft: "noch {n} Tage", restore: "Wiederherstellen", restored: "Wiederhergestellt", trashAll: "Alle löschen",
  tlSaveAs: "In Aufnahmen speichern ({f})", tlShareAs: "Teilen ({f})", tag_clean: "sauber", tag_edit: "bearbeitet", tplRecWord: "Aufnahme",
  setRec: "Aufnahme", setQuality: "Aufnahmequalität", q_std: "Standard (44,1 kHz mono)", q_high: "Hoch (48 kHz mono)", q_stereo: "Musik (48 kHz stereo)",
  setMic: "Mikrofon", mic_auto: "Automatisch", mic_builtin: "Telefonmikrofon", mic_wired: "Kabel-Headset", mic_usb: "USB-Mikrofon",
  setCall: "Bei Anrufen pausieren", setCallD: "Die Aufnahme stoppt bei einem Anruf und läuft danach weiter.",
  setClean: "Säubern", setStrength: "Standardstärke", setAuto: "Nach der Aufnahme automatisch säubern", setAutoD: "Diagnose und Korrekturen laufen von selbst; du hörst rein und speicherst.",
  setExport: "Export", setFmt: "Standardformat", setName: "Dateiname", tplEx: "Beispiel: {x}", tplSample: "Interview", tplCustom: "Eigenes… ({name} {date} {time} {tag})",
  setStorage: "Speicher", setTrash: "Gelöschte Aufnahmen", trash_30: "30 Tage im Papierkorb", trash_7: "7 Tage im Papierkorb", trash_0: "Sofort löschen",
  licenses: L
},
fr: {
  delTrash: "« {title} » va dans la corbeille ; tu peux le restaurer pendant {n} jours.", toTrash: "Placé dans la corbeille", trash: "Corbeille",
  trashD: "Les enregistrements ici sont supprimés définitivement après {n} jours.", trashEmpty: "La corbeille est vide.", trashLeft: "{n} jours restants", restore: "Restaurer", restored: "Restauré", trashAll: "Tout supprimer",
  tlSaveAs: "Enregistrer dans les enregistrements ({f})", tlShareAs: "Partager ({f})", tag_clean: "propre", tag_edit: "modifie", tplRecWord: "Enregistrement",
  setRec: "Enregistrement", setQuality: "Qualité d’enregistrement", q_std: "Standard (44,1 kHz mono)", q_high: "Haute (48 kHz mono)", q_stereo: "Musique (48 kHz stéréo)",
  setMic: "Micro", mic_auto: "Automatique", mic_builtin: "Micro du téléphone", mic_wired: "Casque filaire", mic_usb: "Micro USB",
  setCall: "Pause pendant les appels", setCallD: "L’enregistrement s’arrête lors d’un appel et reprend ensuite.",
  setClean: "Nettoyage", setStrength: "Intensité par défaut", setAuto: "Nettoyer automatiquement après l’enregistrement", setAutoD: "Diagnostic et corrections se font seuls ; tu écoutes et enregistres.",
  setExport: "Export", setFmt: "Format par défaut", setName: "Nom du fichier", tplEx: "Exemple : {x}", tplSample: "Interview", tplCustom: "Personnalisé… ({name} {date} {time} {tag})",
  setStorage: "Stockage", setTrash: "Enregistrements supprimés", trash_30: "30 jours dans la corbeille", trash_7: "7 jours dans la corbeille", trash_0: "Supprimer tout de suite",
  licenses: L
},
es: {
  delTrash: "«{title}» va a la papelera; puedes restaurarla durante {n} días.", toTrash: "Movida a la papelera", trash: "Papelera",
  trashD: "Las grabaciones de aquí se borran para siempre tras {n} días.", trashEmpty: "La papelera está vacía.", trashLeft: "quedan {n} días", restore: "Restaurar", restored: "Restaurada", trashAll: "Borrar todo",
  tlSaveAs: "Guardar en grabaciones ({f})", tlShareAs: "Compartir ({f})", tag_clean: "limpia", tag_edit: "editada", tplRecWord: "Grabacion",
  setRec: "Grabación", setQuality: "Calidad de grabación", q_std: "Estándar (44,1 kHz mono)", q_high: "Alta (48 kHz mono)", q_stereo: "Música (48 kHz estéreo)",
  setMic: "Micrófono", mic_auto: "Automático", mic_builtin: "Micrófono del teléfono", mic_wired: "Auriculares con cable", mic_usb: "Micrófono USB",
  setCall: "Pausar durante llamadas", setCallD: "La grabación se detiene con una llamada y sigue después.",
  setClean: "Limpieza", setStrength: "Intensidad predeterminada", setAuto: "Limpiar automáticamente al terminar", setAutoD: "El diagnóstico y las correcciones se hacen solos; tú escuchas y guardas.",
  setExport: "Exportar", setFmt: "Formato predeterminado", setName: "Nombre del archivo", tplEx: "Ejemplo: {x}", tplSample: "Entrevista", tplCustom: "Personalizado… ({name} {date} {time} {tag})",
  setStorage: "Almacenamiento", setTrash: "Grabaciones borradas", trash_30: "30 días en la papelera", trash_7: "7 días en la papelera", trash_0: "Borrar al instante",
  licenses: L
},
pt: {
  delTrash: "“{title}” vai para a lixeira; você pode restaurá-la por {n} dias.", toTrash: "Movida para a lixeira", trash: "Lixeira",
  trashD: "As gravações aqui são apagadas de vez após {n} dias.", trashEmpty: "A lixeira está vazia.", trashLeft: "faltam {n} dias", restore: "Restaurar", restored: "Restaurada", trashAll: "Apagar tudo",
  tlSaveAs: "Salvar nas gravações ({f})", tlShareAs: "Compartilhar ({f})", tag_clean: "limpa", tag_edit: "editada", tplRecWord: "Gravacao",
  setRec: "Gravação", setQuality: "Qualidade da gravação", q_std: "Padrão (44,1 kHz mono)", q_high: "Alta (48 kHz mono)", q_stereo: "Música (48 kHz estéreo)",
  setMic: "Microfone", mic_auto: "Automático", mic_builtin: "Microfone do telefone", mic_wired: "Fone com fio", mic_usb: "Microfone USB",
  setCall: "Pausar em chamadas", setCallD: "A gravação para quando chega uma chamada e continua depois.",
  setClean: "Limpeza", setStrength: "Intensidade padrão", setAuto: "Limpar automaticamente após gravar", setAutoD: "Diagnóstico e correções acontecem sozinhos; você ouve e salva.",
  setExport: "Exportação", setFmt: "Formato padrão", setName: "Nome do arquivo", tplEx: "Exemplo: {x}", tplSample: "Entrevista", tplCustom: "Personalizado… ({name} {date} {time} {tag})",
  setStorage: "Armazenamento", setTrash: "Gravações apagadas", trash_30: "30 dias na lixeira", trash_7: "7 dias na lixeira", trash_0: "Apagar na hora",
  licenses: L
},
ru: {
  delTrash: "«{title}» попадёт в корзину; восстановить можно в течение {n} дн.", toTrash: "Перемещено в корзину", trash: "Корзина",
  trashD: "Записи здесь удаляются навсегда через {n} дн.", trashEmpty: "Корзина пуста.", trashLeft: "осталось {n} дн.", restore: "Восстановить", restored: "Восстановлено", trashAll: "Удалить всё",
  tlSaveAs: "Сохранить в записи ({f})", tlShareAs: "Поделиться ({f})", tag_clean: "chistaya", tag_edit: "izmenena", tplRecWord: "Zapis",
  setRec: "Запись", setQuality: "Качество записи", q_std: "Стандарт (44,1 кГц моно)", q_high: "Высокое (48 кГц моно)", q_stereo: "Музыка (48 кГц стерео)",
  setMic: "Микрофон", mic_auto: "Автоматически", mic_builtin: "Микрофон телефона", mic_wired: "Проводная гарнитура", mic_usb: "USB-микрофон",
  setCall: "Пауза во время звонков", setCallD: "Запись останавливается при звонке и продолжается после него.",
  setClean: "Очистка", setStrength: "Сила по умолчанию", setAuto: "Очищать автоматически после записи", setAutoD: "Диагностика и исправления выполняются сами; вы слушаете и сохраняете.",
  setExport: "Экспорт", setFmt: "Формат по умолчанию", setName: "Имя файла", tplEx: "Пример: {x}", tplSample: "Интервью", tplCustom: "Свой… ({name} {date} {time} {tag})",
  setStorage: "Хранение", setTrash: "Удалённые записи", trash_30: "Хранить в корзине 30 дней", trash_7: "Хранить в корзине 7 дней", trash_0: "Удалять сразу",
  licenses: L
},
ar: {
  delTrash: "سيُنقل «{title}» إلى سلة المهملات؛ يمكنك استعادته خلال {n} يومًا.", toTrash: "نُقل إلى سلة المهملات", trash: "سلة المهملات",
  trashD: "تُحذف التسجيلات هنا نهائيًا بعد {n} يومًا.", trashEmpty: "سلة المهملات فارغة.", trashLeft: "باقٍ {n} يوم", restore: "استعادة", restored: "تمت الاستعادة", trashAll: "حذف الكل",
  tlSaveAs: "حفظ في التسجيلات ({f})", tlShareAs: "مشاركة ({f})", tag_clean: "clean", tag_edit: "edited", tplRecWord: "Recording",
  setRec: "التسجيل", setQuality: "جودة التسجيل", q_std: "عادية (44.1 كيلوهرتز أحادي)", q_high: "عالية (48 كيلوهرتز أحادي)", q_stereo: "موسيقى (48 كيلوهرتز ستيريو)",
  setMic: "الميكروفون", mic_auto: "تلقائي", mic_builtin: "ميكروفون الهاتف", mic_wired: "سماعة سلكية", mic_usb: "ميكروفون USB",
  setCall: "إيقاف مؤقت أثناء المكالمات", setCallD: "يتوقف التسجيل عند ورود مكالمة ويستأنف بعدها.",
  setClean: "التنظيف", setStrength: "القوة الافتراضية", setAuto: "تنظيف تلقائي بعد التسجيل", setAutoD: "يجري التشخيص والإصلاح وحدهما؛ أنت تستمع وتحفظ.",
  setExport: "التصدير", setFmt: "الصيغة الافتراضية", setName: "اسم الملف", tplEx: "مثال: {x}", tplSample: "مقابلة", tplCustom: "مخصّص… ({name} {date} {time} {tag})",
  setStorage: "التخزين", setTrash: "التسجيلات المحذوفة", trash_30: "تبقى 30 يومًا في السلة", trash_7: "تبقى 7 أيام في السلة", trash_0: "حذف فوري",
  licenses: L
},
zh: {
  delTrash: "“{title}”将移入回收站，{n} 天内可以恢复。", toTrash: "已移入回收站", trash: "回收站",
  trashD: "这里的录音会在 {n} 天后被永久删除。", trashEmpty: "回收站是空的。", trashLeft: "还剩 {n} 天", restore: "恢复", restored: "已恢复", trashAll: "全部删除",
  tlSaveAs: "保存到录音（{f}）", tlShareAs: "分享（{f}）", tag_clean: "clean", tag_edit: "edited", tplRecWord: "Recording",
  setRec: "录音", setQuality: "录音质量", q_std: "标准（44.1 kHz 单声道）", q_high: "高（48 kHz 单声道）", q_stereo: "音乐（48 kHz 立体声）",
  setMic: "麦克风", mic_auto: "自动", mic_builtin: "手机麦克风", mic_wired: "有线耳机", mic_usb: "USB 麦克风",
  setCall: "通话时暂停", setCallD: "来电时录音暂停，通话结束后继续。",
  setClean: "清理", setStrength: "默认强度", setAuto: "录音结束后自动清理", setAutoD: "诊断和修复自动完成，你只需试听并保存。",
  setExport: "导出", setFmt: "默认格式", setName: "文件名", tplEx: "示例：{x}", tplSample: "采访", tplCustom: "自定义…（{name} {date} {time} {tag}）",
  setStorage: "存储", setTrash: "已删除的录音", trash_30: "在回收站保留 30 天", trash_7: "在回收站保留 7 天", trash_0: "立即删除",
  licenses: L
}
};
for (const l in A) window.SD_I18N[l] = Object.assign(window.SD_I18N[l] || {}, A[l]);
})();
