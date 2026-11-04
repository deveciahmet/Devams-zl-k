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
)

enum class EventType { ASK, REMIND, PRECLASS, WEEKLY }

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
 */
fun computeEvents(
    polls: List<Poll>,
    status: (String) -> Status?,
    riskyCodes: Set<String>,
    ns: NotifySettings,
    semesterStart: LocalDate,
    weeks: Int,
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
    return out
}
