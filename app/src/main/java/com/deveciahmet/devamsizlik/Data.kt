package com.deveciahmet.devamsizlik

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.UUID
import kotlin.math.floor

enum class Status { ATTENDED, ABSENT, CANCELLED }

/** Haftalık programdaki tek bir ders saati. day: 1 = Pazartesi ... 7 = Pazar */
data class Slot(
    val id: String,
    val code: String,
    val day: Int,
    val start: LocalTime,
    val end: LocalTime,
)

/** Belirli bir tarihteki tek bir ders oturumu. */
data class Session(val slot: Slot, val date: LocalDate, val week: Int) {
    val key: String get() = "${slot.id}|$date"
    val start: LocalDateTime get() = LocalDateTime.of(date, slot.start)
    val end: LocalDateTime get() = LocalDateTime.of(date, slot.end)
}

data class CourseStats(
    val code: String,
    val total: Int,        // dönem boyunca yapılacak (iptal hariç) toplam ders
    val attended: Int,
    val absent: Int,
    val cancelled: Int,
    val unanswered: Int,   // geçmiş ama işaretlenmemiş
    val upcoming: Int,     // henüz yapılmamış
    val allowed: Int,      // en fazla kaç devamsızlık hakkı var
) {
    val remaining: Int get() = allowed - absent
    val absentPercent: Double get() = if (total == 0) 0.0 else absent * 100.0 / total
    val exhausted: Boolean get() = remaining <= 0
}

val TR: Locale = Locale("tr", "TR")
private val dayShort = listOf("Pzt", "Sal", "Çar", "Per", "Cum", "Cmt", "Paz")
private val dayLong = listOf("Pazartesi", "Salı", "Çarşamba", "Perşembe", "Cuma", "Cumartesi", "Pazar")
fun dayShortName(day: Int) = dayShort[day - 1]
fun dayLongName(day: Int) = dayLong[day - 1]
val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", TR)
val dateLongFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", TR)

object Repo {
    private const val SETTINGS = "settings"
    private const val ATTENDANCE = "attendance"

    // Bugün (4 Kasım 2026) 6. hafta -> 1. haftanın Pazartesi'si 28 Eylül 2026.
    private val DEFAULT_START: LocalDate = LocalDate.of(2026, 9, 28)

    private fun t(s: String) = LocalTime.parse(s)

    /** İTÜ OBS'deki ders programı. */
    val defaultSlots = listOf(
        Slot("mak335-pzt", "MAK 335", 1, t("10:30"), t("13:29")),
        Slot("mak303-pzt", "MAK 303", 1, t("15:30"), t("17:29")),
        Slot("mak309-sal", "MAK 309", 2, t("10:30"), t("13:29")),
        Slot("itb220-sal", "ITB 220", 2, t("14:30"), t("17:29")),
        Slot("mak305-car", "MAK 305", 3, t("08:30"), t("10:29")),
        Slot("mak303-per", "MAK 303", 4, t("08:30"), t("10:29")),
        Slot("mak305-per", "MAK 305", 4, t("10:30"), t("12:29")),
        Slot("mak317-per", "MAK 317", 4, t("12:30"), t("14:29")),
        Slot("mak304e-per", "MAK 304E", 4, t("15:30"), t("17:29")),
        Slot("beb104-cum", "BEB 104", 5, t("10:30"), t("12:29")),
        Slot("mak118-cum", "MAK 118", 5, t("14:00"), t("16:59")),
    )

    fun settings(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)

    fun attendance(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(ATTENDANCE, Context.MODE_PRIVATE)

    // ---------- Ayarlar ----------

    fun semesterStart(ctx: Context): LocalDate =
        settings(ctx).getString("start", null)?.let(LocalDate::parse) ?: DEFAULT_START

    fun setSemesterStart(ctx: Context, date: LocalDate) {
        val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        settings(ctx).edit().putString("start", monday.toString()).apply()
    }

    fun weeks(ctx: Context): Int = settings(ctx).getInt("weeks", 13)
    fun setWeeks(ctx: Context, w: Int) = settings(ctx).edit().putInt("weeks", w.coerceIn(1, 30)).apply()

    /** Devamsızlık sınırı (%). İTÜ'de %70 devam zorunluluğu -> en fazla %30 devamsızlık. */
    fun limitPercent(ctx: Context): Int = settings(ctx).getInt("limit", 30)
    fun setLimitPercent(ctx: Context, p: Int) =
        settings(ctx).edit().putInt("limit", p.coerceIn(0, 100)).apply()

    /** Bugün kaçıncı haftadayız (1 tabanlı; dönem dışında 0 veya weeks+1'i geçebilir). */
    fun currentWeek(ctx: Context, today: LocalDate = LocalDate.now()): Int =
        Math.floorDiv(ChronoUnit.DAYS.between(semesterStart(ctx), today), 7L).toInt() + 1

    fun setCurrentWeek(ctx: Context, week: Int, today: LocalDate = LocalDate.now()) {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        setSemesterStart(ctx, monday.minusWeeks((week - 1).toLong()))
    }

    // ---------- Ders programı ----------

    fun slots(ctx: Context): List<Slot> {
        val json = settings(ctx).getString("slots", null) ?: return defaultSlots
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Slot(o.getString("id"), o.getString("code"), o.getInt("day"),
                    t(o.getString("start")), t(o.getString("end")))
            }
        } catch (e: Exception) {
            defaultSlots
        }
    }

    private fun saveSlots(ctx: Context, list: List<Slot>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id); put("code", it.code); put("day", it.day)
                put("start", it.start.toString()); put("end", it.end.toString())
            })
        }
        settings(ctx).edit().putString("slots", arr.toString()).apply()
    }

    fun addSlot(ctx: Context, code: String, day: Int, start: LocalTime, end: LocalTime) {
        saveSlots(ctx, slots(ctx) + Slot(UUID.randomUUID().toString(), code.trim().uppercase(TR), day, start, end))
    }

    fun removeSlot(ctx: Context, id: String) = saveSlots(ctx, slots(ctx).filterNot { it.id == id })

    fun resetSlots(ctx: Context) = settings(ctx).edit().remove("slots").apply()

    // ---------- Oturumlar & yoklama ----------

    fun sessions(ctx: Context): List<Session> {
        val start = semesterStart(ctx)
        val slots = slots(ctx)
        val result = ArrayList<Session>()
        for (w in 0 until weeks(ctx)) {
            val monday = start.plusWeeks(w.toLong())
            for (s in slots) result += Session(s, monday.plusDays((s.day - 1).toLong()), w + 1)
        }
        result.sortBy { it.start }
        return result
    }

    fun findSession(ctx: Context, key: String): Session? = sessions(ctx).firstOrNull { it.key == key }

    fun status(ctx: Context, key: String): Status? =
        attendance(ctx).getString(key, null)?.let { runCatching { Status.valueOf(it) }.getOrNull() }

    fun setStatus(ctx: Context, key: String, status: Status?) {
        val e = attendance(ctx).edit()
        if (status == null) e.remove(key) else e.putString(key, status.name)
        e.commit()
    }

    fun stats(ctx: Context, now: LocalDateTime = LocalDateTime.now()): List<CourseStats> {
        val limit = limitPercent(ctx)
        return sessions(ctx).groupBy { it.slot.code }.map { (code, list) ->
            var att = 0; var abs = 0; var can = 0; var una = 0; var up = 0
            for (s in list) {
                when (status(ctx, s.key)) {
                    Status.ATTENDED -> att++
                    Status.ABSENT -> abs++
                    Status.CANCELLED -> can++
                    null -> if (s.end.isAfter(now)) up++ else una++
                }
            }
            val total = list.size - can
            CourseStats(code, total, att, abs, can, una, up, floor(total * limit / 100.0).toInt())
        }
    }

    fun unansweredPast(ctx: Context, now: LocalDateTime = LocalDateTime.now()): List<Session> =
        sessions(ctx).filter { !it.end.isAfter(now) && status(ctx, it.key) == null }
}
