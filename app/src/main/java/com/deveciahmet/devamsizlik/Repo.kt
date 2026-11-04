package com.deveciahmet.devamsizlik

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

/** Belirli bir andaki bütün verinin hesaplanmış hali. Arayüz ve bildirimler bunun üzerinden çalışır. */
class Snapshot(
    val now: LocalDateTime,
    val semesterStart: LocalDate,
    val weeks: Int,
    val requiredPercent: Int,
    val slots: List<Slot>,
    val configs: Map<String, CourseConfig>,
    val sessions: List<Session>,
    val polls: List<Poll>,
    val statuses: Map<String, Status>,
    val stats: List<CourseStats>,
    val notify: NotifySettings,
) {
    fun status(key: String): Status? = statuses[key]
    fun config(code: String): CourseConfig = configs[code] ?: CourseConfig()
    fun stat(code: String): CourseStats? = stats.firstOrNull { it.code == code }
    fun title(code: String): String = config(code).name.ifBlank { code }
    val currentWeek: Int get() = weekNumber(semesterStart, now.toLocalDate())
    val courseCodes: List<String> get() = slots.map { it.code }.distinct().sorted()
    val unansweredPast: List<Poll> by lazy { polls.filter { !it.time.isAfter(now) && statuses[it.key] == null } }
}

object Repo {
    private const val SETTINGS = "settings"
    private const val ATTENDANCE = "attendance"

    // 4 Kasım 2026 6. hafta → 1. haftanın Pazartesi'si 28 Eylül 2026.
    private val DEFAULT_START: LocalDate = LocalDate.of(2026, 9, 28)

    private fun t(s: String): LocalTime = LocalTime.parse(s)

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

    // ---------------- Dönem ----------------

    fun semesterStart(ctx: Context): LocalDate =
        settings(ctx).getString("start", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: DEFAULT_START

    fun setSemesterStart(ctx: Context, date: LocalDate) =
        settings(ctx).edit().putString("start", mondayOf(date).toString()).apply()

    fun weeks(ctx: Context): Int = settings(ctx).getInt("weeks", 13)
    fun setWeeks(ctx: Context, w: Int) = settings(ctx).edit().putInt("weeks", w.coerceIn(1, 30)).apply()

    /** Varsayılan devam şartı (%). Eski sürüm "en fazla devamsızlık %" saklıyordu; ondan çevrilir. */
    fun requiredPercent(ctx: Context): Int {
        val p = settings(ctx)
        if (p.contains("required")) return p.getInt("required", 70)
        if (p.contains("limit")) return 100 - p.getInt("limit", 30)
        return 70
    }

    fun setRequiredPercent(ctx: Context, v: Int) =
        settings(ctx).edit().putInt("required", v.coerceIn(0, 100)).remove("limit").apply()

    fun setCurrentWeek(ctx: Context, week: Int, today: LocalDate = AppClock.today()) =
        setSemesterStart(ctx, mondayOf(today).minusWeeks((week - 1).toLong()))

    // ---------------- Ders programı ----------------

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

    fun saveSlots(ctx: Context, list: List<Slot>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id); put("code", it.code); put("day", it.day)
                put("start", it.start.toString()); put("end", it.end.toString())
            })
        }
        settings(ctx).edit().putString("slots", arr.toString()).apply()
    }

    fun newSlot(code: String, day: Int, start: LocalTime, end: LocalTime) =
        Slot(UUID.randomUUID().toString(), normalizeCode(code), day, start, end)

    fun addSlot(ctx: Context, slot: Slot) = saveSlots(ctx, slots(ctx) + slot)

    /** Ders saatini günceller. Günü değiştiyse eski yoklama kayıtları da yeni güne taşınır. */
    fun updateSlot(ctx: Context, old: Slot, new: Slot) {
        saveSlots(ctx, slots(ctx).map { if (it.id == old.id) new else it })
        val delta = (new.day - old.day).toLong()
        if (delta == 0L) return
        val att = attendance(ctx)
        val e = att.edit()
        for ((k, v) in att.all) {
            if (v !is String || !k.startsWith("${old.id}|")) continue
            val parts = k.split("|")
            val d = runCatching { LocalDate.parse(parts[1]) }.getOrNull() ?: continue
            val nk = (listOf(parts[0], d.plusDays(delta).toString()) + parts.drop(2)).joinToString("|")
            e.remove(k)
            e.putString(nk, v)
        }
        e.commit()
    }

    fun removeSlot(ctx: Context, id: String) = saveSlots(ctx, slots(ctx).filterNot { it.id == id })

    fun resetSlots(ctx: Context) = settings(ctx).edit().remove("slots").apply()

    /**
     * Yapay zekâdan gelen programı kaydeder. Mevcut programda aynı ders/gün/başlangıç saatindeki kayıtlar
     * aynı kimlikle tutulur, böylece yoklama geçmişi kaybolmaz. Dönen değer: kaydedilen ders saati sayısı.
     */
    fun importSlots(ctx: Context, entries: List<ImportedSlot>, replace: Boolean): Int {
        val existing = slots(ctx)
        val used = HashSet<String>()
        val incoming = entries.map { e ->
            val match = existing.firstOrNull {
                it.id !in used && it.code.equals(e.code, ignoreCase = true) && it.day == e.day && it.start == e.start
            }
            if (match != null) {
                used += match.id
                match.copy(code = e.code, end = e.end)
            } else {
                newSlot(e.code, e.day, e.start, e.end)
            }
        }
        val result = if (replace) incoming else existing.filterNot { it.id in used } + incoming
        saveSlots(ctx, result)

        val cfgs = courseConfigs(ctx).toMutableMap()
        var changed = false
        for (e in entries) {
            if (e.name.isBlank()) continue
            val c = cfgs[e.code] ?: CourseConfig()
            if (c.name.isBlank()) {
                cfgs[e.code] = c.copy(name = e.name)
                changed = true
            }
        }
        if (changed) saveCourseConfigs(ctx, cfgs)
        return incoming.size
    }

    // ---------------- Ders ayarları ----------------

    fun courseConfigs(ctx: Context): Map<String, CourseConfig> {
        val json = settings(ctx).getString("courses", null) ?: return emptyMap()
        return try {
            val o = JSONObject(json)
            val out = HashMap<String, CourseConfig>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val c = o.getJSONObject(k)
                out[k] = CourseConfig(
                    name = c.optString("name", ""),
                    totalSessions = if (c.has("total")) c.optInt("total").takeIf { it > 0 } else null,
                    mode = runCatching { LimitMode.valueOf(c.optString("mode", "DEFAULT")) }.getOrDefault(LimitMode.DEFAULT),
                    requiredPercent = c.optInt("percent", 70).coerceIn(0, 100),
                    allowedCount = c.optInt("count", 3).coerceAtLeast(0),
                    polls = c.optInt("polls", 1).coerceIn(1, MAX_POLLS),
                )
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun saveCourseConfigs(ctx: Context, map: Map<String, CourseConfig>) {
        val o = JSONObject()
        for ((code, c) in map) {
            o.put(code, JSONObject().apply {
                put("name", c.name)
                c.totalSessions?.let { put("total", it) }
                put("mode", c.mode.name)
                put("percent", c.requiredPercent)
                put("count", c.allowedCount)
                put("polls", c.polls)
            })
        }
        settings(ctx).edit().putString("courses", o.toString()).apply()
    }

    /** Ders ayarlarını kaydeder; kod değiştiyse programdaki bütün saatlerini de yeni koda taşır. Yeni kodu döner. */
    fun saveCourse(ctx: Context, oldCode: String, newCode: String, cfg: CourseConfig): String {
        val code = normalizeCode(newCode)
        if (code != oldCode) saveSlots(ctx, slots(ctx).map { if (it.code == oldCode) it.copy(code = code) else it })
        val map = courseConfigs(ctx).toMutableMap()
        map.remove(oldCode)
        map[code] = cfg
        saveCourseConfigs(ctx, map)
        return code
    }

    /**
     * Yoklama sayısı arttırıldığında geçmiş derslerdeki yeni yoklamaları 1. yoklamanın cevabıyla doldurur
     * (çoğunlukla derse giren her iki saatine de girmiştir).
     */
    fun fillNewPolls(ctx: Context, code: String, oldPolls: Int, now: LocalDateTime = AppClock.now()) {
        val snap = snapshot(ctx, now)
        val e = attendance(ctx).edit()
        var any = false
        for (p in snap.polls) {
            if (p.code != code || p.index <= oldPolls || p.time.isAfter(now) || snap.status(p.key) != null) continue
            val first = snap.status(pollKey(p.session.key, 1)) ?: continue
            e.putString(p.key, first.name)
            any = true
        }
        if (any) e.commit()
    }

    // ---------------- Yoklama kayıtları ----------------

    fun statusMap(ctx: Context): Map<String, Status> {
        val out = HashMap<String, Status>()
        for ((k, v) in attendance(ctx).all) {
            val s = (v as? String)?.let { runCatching { Status.valueOf(it) }.getOrNull() } ?: continue
            out[k] = s
        }
        return out
    }

    fun status(ctx: Context, key: String): Status? =
        attendance(ctx).getString(key, null)?.let { runCatching { Status.valueOf(it) }.getOrNull() }

    fun setStatus(ctx: Context, key: String, status: Status?) = setStatuses(ctx, listOf(key), status)

    fun setStatuses(ctx: Context, keys: Collection<String>, status: Status?) {
        val e = attendance(ctx).edit()
        for (k in keys) if (status == null) e.remove(k) else e.putString(k, status.name)
        e.commit()
    }

    // ---------------- Bildirim ayarları ----------------

    fun notifySettings(ctx: Context): NotifySettings {
        val p = settings(ctx)
        return NotifySettings(
            reminderMinutes = p.getInt("n_reminder", 30).coerceAtLeast(0),
            weeklyEnabled = p.getBoolean("n_weekly", true),
            weeklyDay = p.getInt("n_weekly_day", 1).coerceIn(1, 7),
            weeklyTime = p.getString("n_weekly_time", null)?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
                ?: LocalTime.of(8, 0),
            warnEnabled = p.getBoolean("n_warn", true),
            warnThreshold = p.getInt("n_warn_threshold", 1).coerceIn(1, 10),
            preclassMinutes = p.getInt("n_preclass", 60).coerceAtLeast(0),
        )
    }

    fun setNotifySettings(ctx: Context, n: NotifySettings) {
        settings(ctx).edit()
            .putInt("n_reminder", n.reminderMinutes)
            .putBoolean("n_weekly", n.weeklyEnabled)
            .putInt("n_weekly_day", n.weeklyDay)
            .putString("n_weekly_time", n.weeklyTime.toString())
            .putBoolean("n_warn", n.warnEnabled)
            .putInt("n_warn_threshold", n.warnThreshold)
            .putInt("n_preclass", n.preclassMinutes)
            .apply()
    }

    // ---------------- Zamanlayıcı imleci ----------------

    /** En son hangi ana kadar bildirimlerin işlendiği. */
    fun cursor(ctx: Context): LocalDateTime? =
        settings(ctx).getString("cursor", null)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

    fun setCursor(ctx: Context, t: LocalDateTime) = settings(ctx).edit().putString("cursor", t.toString()).apply()

    // ---------------- Hepsi bir arada ----------------

    fun snapshot(ctx: Context, now: LocalDateTime = AppClock.now()): Snapshot {
        val start = semesterStart(ctx)
        val weeks = weeks(ctx)
        val req = requiredPercent(ctx)
        val slots = slots(ctx)
        val configs = courseConfigs(ctx)
        val sessions = generateSessions(start, weeks, slots)
        val polls = buildPolls(sessions, configs)
        val statuses = statusMap(ctx)
        val stats = computeStats(polls, { statuses[it] }, configs, req, now)
        return Snapshot(now, start, weeks, req, slots, configs, sessions, polls, statuses, stats, notifySettings(ctx))
    }
}
