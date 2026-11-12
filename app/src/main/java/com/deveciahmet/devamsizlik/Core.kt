package com.deveciahmet.devamsizlik

import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

// Bu dosyada Android'e bağımlı hiçbir şey yok: hesaplamaların hepsi burada ve birim testleriyle sınanıyor.

/** Şimdiki zaman. Testlerde sabit bir saate ayarlanabilsin diye tek yerden alınır. */
object AppClock {
    @Volatile
    var clock: Clock = Clock.systemDefaultZone()
    fun now(): LocalDateTime = LocalDateTime.now(clock)
    fun today(): LocalDate = LocalDate.now(clock)
}

/** Bir derste en fazla kaç yoklama alınabilir. */
const val MAX_POLLS = 4

enum class Status { ATTENDED, ABSENT, CANCELLED }

/** Devamsızlık hakkının nasıl hesaplanacağı. */
enum class LimitMode {
    /** Genel ayarlardaki devam şartı yüzdesi kullanılır. */
    DEFAULT,

    /** Derse özel devam şartı yüzdesi. */
    PERCENT,

    /** Doğrudan "kaç kez gelmeme hakkı var". */
    COUNT,
}

/** Haftalık programdaki tek bir ders saati. day: 1 = Pazartesi ... 7 = Pazar */
data class Slot(
    val id: String,
    val code: String,
    val day: Int,
    val start: LocalTime,
    val end: LocalTime,
    /** Bina ve derslik, örn. "MKB A501" (bilinmiyorsa boş). */
    val place: String = "",
)

/** Derse özel ayarlar (ders koduna göre saklanır). */
data class CourseConfig(
    val name: String = "",
    /** Dönemde kaç kez yapılacak; null = programdan otomatik hesapla. */
    val totalSessions: Int? = null,
    val mode: LimitMode = LimitMode.DEFAULT,
    /** PERCENT modunda devam şartı (%). */
    val requiredPercent: Int = 70,
    /** COUNT modunda gelmeme hakkı. */
    val allowedCount: Int = 3,
    /** Her derste kaç yoklama alınıyor. */
    val polls: Int = 1,
)

/** Belirli bir tarihteki tek bir ders. */
data class Session(val slot: Slot, val date: LocalDate, val week: Int) {
    val key: String get() = "${slot.id}|$date"
    val start: LocalDateTime get() = LocalDateTime.of(date, slot.start)
    val end: LocalDateTime get() = LocalDateTime.of(date, slot.end)
}

/** Bir dersteki tek bir yoklama (bildirimle sorulan tek bir soru). */
data class Poll(val session: Session, val index: Int, val count: Int, val time: LocalDateTime) {
    val key: String get() = pollKey(session.key, index)
    val code: String get() = session.slot.code
}

/** 1. yoklamanın anahtarı, eski sürümün kayıtları bozulmasın diye dersin kendi anahtarıdır. */
fun pollKey(sessionKey: String, index: Int): String = if (index <= 1) sessionKey else "$sessionKey|$index"

data class CourseStats(
    val code: String,
    val name: String,
    val pollsPerSession: Int,
    /** Programdan hesaplanan ders sayısı. */
    val sessionCount: Int,
    /** Toplam yoklama: elle girilen ders sayısı × yoklama ya da (programdaki yoklamalar − yapılmayanlar). */
    val total: Int,
    val manualTotal: Boolean,
    val attended: Int,
    val absent: Int,
    val cancelled: Int,
    val unanswered: Int,
    val upcoming: Int,
    val allowed: Int,
) {
    val remaining: Int get() = allowed - absent
    val absentPercent: Double get() = if (total <= 0) 0.0 else absent * 100.0 / total
    val exhausted: Boolean get() = remaining <= 0
    val title: String get() = name.ifBlank { code }
    val unit: String get() = if (pollsPerSession > 1) "yoklama" else "ders"
}

data class NotifySettings(
    /** Cevapsız soruyu kaç dakika sonra tekrar sorsun (0 = kapalı). */
    val reminderMinutes: Int = 30,
    val weeklyEnabled: Boolean = true,
    /** 1 = Pazartesi ... 7 = Pazar */
    val weeklyDay: Int = 1,
    val weeklyTime: LocalTime = LocalTime.of(8, 0),
    val warnEnabled: Boolean = true,
    /** Kalan hak bu sayıya inince uyar. */
    val warnThreshold: Int = 1,
    /** Riskli derslerden kaç dakika önce hatırlatsın (0 = kapalı). */
    val preclassMinutes: Int = 60,
    /** Sınavdan bir gün önce akşam hatırlatsın. */
    val examReminder: Boolean = true,
)

/** Elle eklenen bir sınav (vize, final, quiz …). */
data class Exam(
    val id: String,
    /** Dersin kodu; programda olmayan bir sınav için boş olabilir. */
    val code: String,
    /** "Vize", "Final", "Quiz" … */
    val title: String,
    val date: LocalDate,
    val time: LocalTime? = null,
    val place: String = "",
) {
    /** Sınav günü geçtiyse ya da bugünkü sınavın üzerinden 3 saat geçtiyse geçmiş sayılır. */
    fun isPast(now: LocalDateTime): Boolean {
        val today = now.toLocalDate()
        if (date.isBefore(today)) return true
        if (date.isAfter(today) || time == null) return false
        return LocalDateTime.of(date, time).plusHours(3).isBefore(now)
    }
}

/** Sınav hatırlatmasının saati (sınavdan önceki akşam). */
val EXAM_REMINDER_TIME: LocalTime = LocalTime.of(20, 0)

enum class EventType { ASK, REMIND, PRECLASS, WEEKLY, EXAM }

data class Event(val time: LocalDateTime, val type: EventType, val key: String)

data class WeekSummary(
    val total: Int,
    val attended: Int,
    val absent: Int,
    val cancelled: Int,
    val unanswered: Int,
    val absentByCourse: Map<String, Int>,
)

// ---------------- Biçimlendirme ----------------

val TR: Locale = Locale.forLanguageTag("tr-TR")
private val dayShort = listOf("Pzt", "Sal", "Çar", "Per", "Cum", "Cmt", "Paz")
private val dayLong = listOf("Pazartesi", "Salı", "Çarşamba", "Perşembe", "Cuma", "Cumartesi", "Pazar")
fun dayShortName(day: Int): String = dayShort[(day - 1).coerceIn(0, 6)]
fun dayLongName(day: Int): String = dayLong[(day - 1).coerceIn(0, 6)]
val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", TR)
val dateLongFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", TR)
val dateDayFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM EEEE", TR)

fun timeRange(start: LocalTime, end: LocalTime): String = "${start.format(timeFmt)}–${end.format(timeFmt)}"

/** Bugünden bir tarihe kaç gün var (geçmişse eksi). */
fun daysUntil(today: LocalDate, date: LocalDate): Long = ChronoUnit.DAYS.between(today, date)

/** "Bugün", "Yarın", "5 gün" */
fun daysLeftText(days: Long): String = when {
    days < 0 -> "Geçti"
    days == 0L -> "Bugün"
    days == 1L -> "Yarın"
    else -> "$days gün"
}

/** Bir tarihi bugüne göre okunaklı yazar: "Bugün", "Yarın", "Perşembe", "17 Kas Salı". */
fun relativeDay(today: LocalDate, date: LocalDate): String = when (val d = daysUntil(today, date)) {
    0L -> "Bugün"
    1L -> "Yarın"
    -1L -> "Dün"
    in 2L..6L -> dayLongName(date.dayOfWeek.value)
    else -> "${date.format(dateFmt)} ${dayShortName(date.dayOfWeek.value)}"
}

/** Dönemin başlangıç tarihine göre İTÜ biçiminde varsayılan ad: "2026-2027 Güz Dönemi". */
fun defaultTermName(start: LocalDate): String {
    val y = start.year
    return when (start.monthValue) {
        in 8..12 -> "$y-${y + 1} Güz Dönemi"
        in 1..5 -> "${y - 1}-$y Bahar Dönemi"
        else -> "${y - 1}-$y Yaz Dönemi"
    }
}

// ---------------- Hesaplamalar ----------------

private val CODE_RE = Regex("^(\\p{L}{2,5})\\s*(\\d{2,4}\\p{L}{0,2})$")

/** "mak335" → "MAK 335". Ders koduna benzemeyen metinlere (ör. ders adı) dokunmaz, sadece boşlukları düzeltir. */
fun normalizeCode(raw: String): String {
    val s = raw.trim().replace(Regex("\\s+"), " ")
    val m = CODE_RE.matchEntire(s) ?: return s
    return "${m.groupValues[1]} ${m.groupValues[2]}".uppercase(Locale.ROOT)
}

fun mondayOf(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

/** Verilen gün dönemin kaçıncı haftası (1 tabanlı; dönem öncesi ≤ 0). */
fun weekNumber(semesterStart: LocalDate, date: LocalDate): Int =
    Math.floorDiv(ChronoUnit.DAYS.between(mondayOf(semesterStart), date), 7L).toInt() + 1

/** Dönemin son günü (son haftanın Pazar'ı). */
fun semesterEnd(semesterStart: LocalDate, weeks: Int): LocalDate =
    mondayOf(semesterStart).plusWeeks(weeks.coerceAtLeast(1).toLong()).minusDays(1)

/**
 * Yoklama saatleri. k yoklama varsa 1., 2., … yoklama dersin başlamasından 1, 2, … saat sonra,
 * sonuncusu ders bitiminde sorulur. Ders bunun için kısaysa yoklamalar eşit aralıklarla dağıtılır.
 */
fun pollTimes(start: LocalTime, end: LocalTime, count: Int): List<LocalTime> {
    val k = count.coerceIn(1, MAX_POLLS)
    if (k == 1) return listOf(end)
    val dur = ChronoUnit.MINUTES.between(start, end)
    val hourly = (k - 1) * 60L < dur
    val mids = (1 until k).map { i -> start.plusMinutes(if (hourly) i * 60L else i * dur / k) }
    return mids + end
}

fun generateSessions(semesterStart: LocalDate, weeks: Int, slots: List<Slot>): List<Session> {
    val monday = mondayOf(semesterStart)
    val out = ArrayList<Session>(weeks.coerceAtLeast(0) * slots.size)
    for (w in 0 until weeks) {
        val m = monday.plusWeeks(w.toLong())
        for (s in slots) out += Session(s, m.plusDays((s.day - 1).toLong()), w + 1)
    }
    out.sortBy { it.start }
    return out
}

fun buildPolls(sessions: List<Session>, configs: Map<String, CourseConfig>): List<Poll> {
    val out = ArrayList<Poll>(sessions.size)
    for (s in sessions) {
        val k = (configs[s.slot.code]?.polls ?: 1).coerceIn(1, MAX_POLLS)
        pollTimes(s.slot.start, s.slot.end, k).forEachIndexed { i, t ->
            out += Poll(s, i + 1, k, LocalDateTime.of(s.date, t))
        }
    }
    return out
}

/**
 * Tatil günlerindeki yoklamalar, elle başka bir cevap verilmediyse "yapılmadı" sayılır.
 * Böylece tatilden sonra eklenen dersler ya da arttırılan yoklamalar da kendiliğinden tatile uyar.
 */
fun effectiveStatuses(polls: List<Poll>, explicit: Map<String, Status>, holidays: Set<LocalDate>): Map<String, Status> {
    if (holidays.isEmpty()) return explicit
    val out = HashMap(explicit)
    for (p in polls) if (p.session.date in holidays && p.key !in explicit) out[p.key] = Status.CANCELLED
    return out
}

/** Ardışık tatil günlerini [ilk gün, son gün] aralıklarına böler. */
fun holidayRanges(dates: Collection<LocalDate>): List<Pair<LocalDate, LocalDate>> {
    val out = ArrayList<Pair<LocalDate, LocalDate>>()
    for (d in dates.toSortedSet()) {
        val last = out.lastOrNull()
        if (last != null && last.second.plusDays(1) == d) out[out.lastIndex] = last.first to d else out += d to d
    }
    return out
}

/** Devam şartı %p ise en fazla ⌊toplam × (100 − p) / 100⌋ devamsızlık yapılabilir. */
fun allowedAbsences(total: Int, cfg: CourseConfig, defaultRequired: Int): Int = when (cfg.mode) {
    LimitMode.DEFAULT -> total * (100 - defaultRequired.coerceIn(0, 100)) / 100
    LimitMode.PERCENT -> total * (100 - cfg.requiredPercent.coerceIn(0, 100)) / 100
    LimitMode.COUNT -> cfg.allowedCount
}.coerceAtLeast(0)

fun computeStats(
    polls: List<Poll>,
    status: (String) -> Status?,
    configs: Map<String, CourseConfig>,
    defaultRequired: Int,
    now: LocalDateTime,
): List<CourseStats> = polls.groupBy { it.code }.map { (code, list) ->
    var att = 0
    var abs = 0
    var can = 0
    var una = 0
    var up = 0
    for (p in list) {
        when (status(p.key)) {
            Status.ATTENDED -> att++
            Status.ABSENT -> abs++
            Status.CANCELLED -> can++
            null -> if (p.time.isAfter(now)) up++ else una++
        }
    }
    val cfg = configs[code] ?: CourseConfig()
    val k = list.first().count
    val sessionCount = list.asSequence().map { it.session.key }.distinct().count()
    val manual = cfg.totalSessions
    val total = if (manual != null) manual * k else list.size - can
    CourseStats(
        code = code, name = cfg.name, pollsPerSession = k, sessionCount = sessionCount,
        total = total, manualTotal = manual != null,
        attended = att, absent = abs, cancelled = can, unanswered = una, upcoming = up,
        allowed = allowedAbsences(total, cfg, defaultRequired),
    )
}

/** (from, to] aralığındaki yoklamaların özeti. */
fun summarize(polls: List<Poll>, status: (String) -> Status?, from: LocalDateTime, to: LocalDateTime): WeekSummary {
    var total = 0
    var att = 0
    var abs = 0
    var can = 0
    var una = 0
    val byCourse = LinkedHashMap<String, Int>()
    for (p in polls) {
        if (!p.time.isAfter(from) || p.time.isAfter(to)) continue
        total++
        when (status(p.key)) {
            Status.ATTENDED -> att++
            Status.ABSENT -> {
                abs++
                byCourse[p.code] = (byCourse[p.code] ?: 0) + 1
            }
            Status.CANCELLED -> can++
            null -> una++
        }
    }
    return WeekSummary(total, att, abs, can, una, byCourse)
}

/**
 * Zamanlanacak bütün bildirimler:
 * - ASK: her cevaplanmamış yoklamanın saatinde soru
 * - REMIND: cevap gelmezse belirli süre sonra bir kez daha
 * - PRECLASS: hakkı azalan (riskli) derslerden önce "kaçırma" hatırlatması
 * - WEEKLY: haftalık özet
 * - EXAM: sınavdan önceki akşam hatırlatma
 */
fun computeEvents(
    polls: List<Poll>,
    status: (String) -> Status?,
    riskyCodes: Set<String>,
    ns: NotifySettings,
    semesterStart: LocalDate,
    weeks: Int,
    exams: List<Exam> = emptyList(),
): List<Event> {
    val out = ArrayList<Event>()
    for (p in polls) {
        if (status(p.key) != null) continue
        out += Event(p.time, EventType.ASK, p.key)
        if (ns.reminderMinutes > 0) out += Event(p.time.plusMinutes(ns.reminderMinutes.toLong()), EventType.REMIND, p.key)
    }
    if (ns.warnEnabled && ns.preclassMinutes > 0 && riskyCodes.isNotEmpty()) {
        for (p in polls) {
            if (p.index != 1 || p.code !in riskyCodes || status(p.key) == Status.CANCELLED) continue
            out += Event(p.session.start.minusMinutes(ns.preclassMinutes.toLong()), EventType.PRECLASS, p.session.key)
        }
    }
    if (ns.weeklyEnabled) {
        val monday = mondayOf(semesterStart)
        for (w in 0..weeks) {
            val date = monday.plusWeeks(w.toLong()).plusDays((ns.weeklyDay.coerceIn(1, 7) - 1).toLong())
            out += Event(LocalDateTime.of(date, ns.weeklyTime), EventType.WEEKLY, "weekly")
        }
    }
    if (ns.examReminder) {
        for (e in exams) out += Event(LocalDateTime.of(e.date.minusDays(1), EXAM_REMINDER_TIME), EventType.EXAM, e.id)
    }
    return out
}

// ---------------- Verinin hesaplanmış hali ----------------

/** Belirli bir andaki bütün verinin hesaplanmış hali. Arayüz, bildirimler ve widget bunun üzerinden çalışır. */
class Snapshot(
    val now: LocalDateTime,
    val semesterStart: LocalDate,
    val weeks: Int,
    val requiredPercent: Int,
    val slots: List<Slot>,
    val configs: Map<String, CourseConfig>,
    val sessions: List<Session>,
    val polls: List<Poll>,
    /** Etkin cevaplar: elle verilenler + tatil günlerindeki "yapılmadı"lar. */
    val statuses: Map<String, Status>,
    val stats: List<CourseStats>,
    val notify: NotifySettings,
    val exams: List<Exam> = emptyList(),
    val holidays: Set<LocalDate> = emptySet(),
    val termName: String = defaultTermName(semesterStart),
) {
    fun status(key: String): Status? = statuses[key]
    fun config(code: String): CourseConfig = configs[code] ?: CourseConfig()
    fun stat(code: String): CourseStats? = stats.firstOrNull { it.code == code }
    fun title(code: String): String = config(code).name.ifBlank { code }
    val today: LocalDate get() = now.toLocalDate()
    val currentWeek: Int get() = weekNumber(semesterStart, today)
    val endDate: LocalDate get() = semesterEnd(semesterStart, weeks)
    val courseCodes: List<String> get() = slots.map { it.code }.distinct().sorted()
    val unansweredPast: List<Poll> by lazy { polls.filter { !it.time.isAfter(now) && statuses[it.key] == null } }

    /** Dersin bütün yoklamaları "yapılmadı" mı (iptal ya da tatil)? */
    fun isCancelled(s: Session): Boolean = statuses[pollKey(s.key, 1)] == Status.CANCELLED

    /** Bugünden itibaren sınavlar, en yakını başta. */
    val upcomingExams: List<Exam> by lazy {
        exams.filter { !it.isPast(now) }.sortedWith(compareBy<Exam>({ it.date }, { it.time ?: LocalTime.MIN }))
    }
    val pastExams: List<Exam> by lazy {
        exams.filter { it.isPast(now) }.sortedWith(compareByDescending<Exam> { it.date }.thenByDescending { it.time })
    }

    /** "Isı Geçişi · Vize" */
    fun examTitle(e: Exam): String = if (e.code.isBlank()) e.title else "${title(e.code)} · ${e.title}"
}

fun buildSnapshot(
    now: LocalDateTime,
    semesterStart: LocalDate,
    weeks: Int,
    requiredPercent: Int,
    slots: List<Slot>,
    configs: Map<String, CourseConfig>,
    explicit: Map<String, Status>,
    notify: NotifySettings = NotifySettings(),
    exams: List<Exam> = emptyList(),
    holidays: Set<LocalDate> = emptySet(),
    termName: String = "",
): Snapshot {
    val sessions = generateSessions(semesterStart, weeks, slots)
    val polls = buildPolls(sessions, configs)
    val statuses = effectiveStatuses(polls, explicit, holidays)
    val stats = computeStats(polls, { statuses[it] }, configs, requiredPercent, now)
    return Snapshot(
        now, semesterStart, weeks, requiredPercent, slots, configs, sessions, polls, statuses, stats, notify,
        exams, holidays, termName.ifBlank { defaultTermName(semesterStart) },
    )
}
