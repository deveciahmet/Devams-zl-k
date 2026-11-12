package com.deveciahmet.devamsizlik

import java.time.LocalTime

// Ana ekranın "Bugün" kartı ve ana ekran widget'ı için hesaplamalar. Android'e bağımlı değil.

/** Şu an süren ya da sıradaki ders. */
data class ClassAt(val session: Session, val ongoing: Boolean)

/** Yapılmayacak (iptal/tatil) dersleri atlayarak şu an süren ya da sıradaki ders. */
fun currentOrNextClass(snap: Snapshot): ClassAt? {
    for (s in snap.sessions) {
        if (!s.end.isAfter(snap.now) || snap.isCancelled(s)) continue
        return ClassAt(s, ongoing = !s.start.isAfter(snap.now))
    }
    return null
}

/** Bir dersin (bütün yoklamalarının) durumu, tek kelimeyle. */
enum class SessionState { UPCOMING, ONGOING, UNANSWERED, ATTENDED, ABSENT, PARTIAL, CANCELLED }

fun sessionState(snap: Snapshot, s: Session): SessionState {
    val polls = (1..(snap.config(s.slot.code).polls.coerceIn(1, MAX_POLLS))).map { snap.status(pollKey(s.key, it)) }
    return when {
        polls.all { it == Status.CANCELLED } -> SessionState.CANCELLED
        polls.all { it == Status.ATTENDED || it == Status.CANCELLED } && polls.any { it == Status.ATTENDED } ->
            SessionState.ATTENDED
        polls.all { it == Status.ABSENT || it == Status.CANCELLED } && polls.any { it == Status.ABSENT } ->
            SessionState.ABSENT
        polls.any { it != null } -> SessionState.PARTIAL
        !s.start.isAfter(snap.now) && s.end.isAfter(snap.now) -> SessionState.ONGOING
        s.end.isAfter(snap.now) -> SessionState.UPCOMING
        else -> SessionState.UNANSWERED
    }
}

/** Bugünkü dersler (saat sırasıyla). */
fun todaysClasses(snap: Snapshot): List<Session> = snap.sessions.filter { it.date == snap.today }

enum class Level { DANGER, WARN, INFO }

/**
 * [code]: ilgili ders; [unanswered]: işaretlenmemiş yoklamalar uyarısı (dokununca doldurma ekranı açılır).
 * [short]: durum başta, ders adı sonda ("Hakkın bitti · Isı Geçişi"): dar yerlerde (widget) ad kesilse bile durum okunur.
 */
data class Alert(
    val level: Level,
    val text: String,
    val code: String? = null,
    val unanswered: Boolean = false,
    val short: String = text,
)

/** Önemli durumlar: hakkı biten / azalan dersler ve işaretlenmemiş yoklamalar (en önemlisi başta). */
fun alerts(snap: Snapshot): List<Alert> {
    val out = ArrayList<Alert>()
    val threshold = snap.notify.warnThreshold
    for (s in snap.stats.sortedWith(compareBy<CourseStats>({ it.remaining }, { it.code }))) {
        out += when {
            s.remaining < 0 -> Alert(Level.DANGER, "${s.title}: sınırı ${-s.remaining} ${s.unit} aştın", s.code,
                short = "Sınır aşıldı · ${s.title}")
            s.remaining == 0 -> Alert(Level.DANGER, "${s.title}: hakkın bitti", s.code, short = "Hakkın bitti · ${s.title}")
            s.remaining <= threshold -> Alert(Level.WARN, "${s.title}: son ${s.remaining} hak", s.code,
                short = "Son ${s.remaining} hak · ${s.title}")
            else -> continue
        }
    }
    val n = snap.unansweredPast.size
    if (n > 0) out += Alert(Level.INFO, "$n yoklama işaretlenmemiş", unanswered = true)
    return out
}

/** "Bugün 15:30–17:29 · MKB A363" ya da süren ders için "13:29'a kadar · MKB A501" */
fun classDetail(snap: Snapshot, c: ClassAt): String {
    val slot = c.session.slot
    val time = if (c.ongoing) "${slot.end.format(timeFmt)}'a kadar"
    else "${relativeDay(snap.today, c.session.date)} ${timeRange(slot.start, slot.end)}"
    return if (slot.place.isBlank()) time else "$time · ${slot.place}"
}

/** Widget'ta gösterilecek her şey; düzen (RemoteViews) bundan kurulur. */
data class WidgetData(
    /** "Şu an derste", "Sıradaki ders", "Dönem bitti" … */
    val label: String,
    val title: String,
    val detail: String,
    val classCode: String?,
    /** Sıradaki sınava kalan gün: "5", "Yarın", "Bugün"; sınav yoksa null. */
    val examBig: String?,
    val examSmall: String,
    val examTitle: String,
    val messages: List<Alert>,
)

fun widgetData(snap: Snapshot, maxMessages: Int = 2): WidgetData {
    val next = currentOrNextClass(snap)
    val week = snap.currentWeek
    val (label, title, detail) = when {
        snap.slots.isEmpty() -> Triple("Ders programı", "Program boş", "Kepler'den eklemek için dokun")
        next == null -> Triple("Dönem bitti", snap.termName, "Ayarlar → Yeni dönem başlat")
        next.ongoing -> Triple("Şu an derste", snap.title(next.session.slot.code), classDetail(snap, next))
        week < 1 -> Triple("Dönem başlamadı • Sıradaki ders", snap.title(next.session.slot.code), classDetail(snap, next))
        else -> Triple("Sıradaki ders", snap.title(next.session.slot.code), classDetail(snap, next))
    }

    val exam = snap.upcomingExams.firstOrNull()
    val days = exam?.let { daysUntil(snap.today, it.date) }
    val examBig = days?.let { if (it >= 2) "$it" else daysLeftText(it) }
    val examSmall = when {
        days == null -> "Sınav ekle"
        days >= 2 -> "gün kaldı"
        else -> exam.time?.format(timeFmt).orEmpty()
    }

    // Sıradaki dersin uyarısı en üstte: "kaçırma" hatırlatması gibi işe yarar.
    val nextCode = next?.session?.slot?.code
    val all = alerts(snap).sortedBy { if (it.code != null && it.code == nextCode) 0 else 1 }
    val messages = all.take(maxMessages).ifEmpty {
        if (snap.stats.isEmpty()) emptyList() else listOf(Alert(Level.INFO, "Bütün derslerde devamsızlık hakkın var 👍"))
    }
    return WidgetData(
        label = label, title = title, detail = detail, classCode = nextCode,
        examBig = examBig, examSmall = examSmall, examTitle = exam?.let { snap.examTitle(it) }.orEmpty(),
        messages = messages,
    )
}

/** Widget'ın bir sonraki yenilenmesi gereken an: bir dersin başlaması/bitmesi ya da gece yarısı. */
fun nextWidgetRefresh(snap: Snapshot): java.time.LocalDateTime {
    val midnight = snap.today.plusDays(1).atTime(LocalTime.MIDNIGHT).plusMinutes(1)
    val boundary = snap.sessions.asSequence()
        .flatMap { sequenceOf(it.start, it.end) }
        .filter { it.isAfter(snap.now) }
        .minOrNull()
    val exam = snap.upcomingExams.firstOrNull()?.let { e -> e.time?.let { e.date.atTime(it).plusHours(3).plusMinutes(1) } }
        ?.takeIf { it.isAfter(snap.now) }
    return listOfNotNull(midnight, boundary, exam).min()
}
