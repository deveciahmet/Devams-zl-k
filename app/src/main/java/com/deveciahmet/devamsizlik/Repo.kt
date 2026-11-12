package com.deveciahmet.devamsizlik

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

object Repo {
    const val SETTINGS = "settings"
    const val ATTENDANCE = "attendance"

    // 4 Kasım 2026 6. hafta → 1. haftanın Pazartesi'si 28 Eylül 2026.
    private val DEFAULT_START: LocalDate = LocalDate.of(2026, 9, 28)

    private fun t(s: String): LocalTime = LocalTime.parse(s)

    /** İTÜ OBS'deki ders programı (ilk kurulumda, kayıtlı program yokken kullanılır). */
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

    /** Dönemin adı ("2026-2027 Güz Dönemi"); kaydedilmemişse başlangıç tarihinden çıkarılır. */
    fun termName(ctx: Context): String = settings(ctx).getString("term_name", null).orEmpty()
        .ifBlank { defaultTermName(semesterStart(ctx)) }

    fun setTermName(ctx: Context, name: String) = settings(ctx).edit().putString("term_name", name.trim()).apply()

    // ---------------- Ders programı ----------------

    fun slots(ctx: Context): List<Slot> {
        val json = settings(ctx).getString("slots", null) ?: return defaultSlots
        return runCatching { slotsFromJson(JSONArray(json)) }.getOrDefault(defaultSlots)
    }

    fun slotsFromJson(arr: JSONArray): List<Slot> = (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        Slot(o.getString("id"), o.getString("code"), o.getInt("day"),
            t(o.getString("start")), t(o.getString("end")), o.optString("place", ""))
    }

    fun slotsToJson(list: List<Slot>): JSONArray = JSONArray().apply {
        list.forEach {
            put(JSONObject().apply {
                put("id", it.id); put("code", it.code); put("day", it.day)
                put("start", it.start.toString()); put("end", it.end.toString())
                if (it.place.isNotBlank()) put("place", it.place)
            })
        }
    }

    fun saveSlots(ctx: Context, list: List<Slot>) =
        settings(ctx).edit().putString("slots", slotsToJson(list).toString()).apply()

    fun newSlot(code: String, day: Int, start: LocalTime, end: LocalTime, place: String = "") =
        Slot(UUID.randomUUID().toString(), normalizeCode(code), day, start, end, place.trim())

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

    /** Dersi programdan tamamen kaldırır (bütün saatleri ve ayarları). */
    fun deleteCourse(ctx: Context, code: String) {
        saveSlots(ctx, slots(ctx).filterNot { it.code == code })
        val map = courseConfigs(ctx).toMutableMap()
        if (map.remove(code) != null) saveCourseConfigs(ctx, map)
    }

    /**
     * Gelen programı kaydeder. Mevcut programda aynı ders/gün/başlangıç saatindeki kayıtlar aynı kimlikle tutulur,
     * böylece yoklama geçmişi kaybolmaz. [overwriteNames]: ders adları kayıtlı adların üzerine yazılsın mı
     * (İTÜ'den gelen adlar için evet). Dönen değer: kaydedilen ders saati sayısı.
     */
    fun importSlots(ctx: Context, entries: List<ImportedSlot>, replace: Boolean, overwriteNames: Boolean = false): Int {
        val existing = slots(ctx)
        val used = HashSet<String>()
        val incoming = entries.map { e ->
            val match = existing.firstOrNull {
                it.id !in used && it.code.equals(e.code, ignoreCase = true) && it.day == e.day && it.start == e.start
            }
            if (match != null) {
                used += match.id
                match.copy(code = e.code, end = e.end, place = e.place.ifBlank { match.place })
            } else {
                newSlot(e.code, e.day, e.start, e.end, e.place)
            }
        }
        val result = if (replace) incoming else existing.filterNot { it.id in used } + incoming
        saveSlots(ctx, result)

        val cfgs = courseConfigs(ctx).toMutableMap()
        var changed = false
        for (e in entries) {
            if (e.name.isBlank()) continue
            val c = cfgs[e.code] ?: CourseConfig()
            if (c.name.isBlank() || (overwriteNames && c.name != e.name)) {
                cfgs[e.code] = c.copy(name = e.name)
                changed = true
            }
        }
        if (changed) saveCourseConfigs(ctx, cfgs)
        return incoming.size
    }

    /**
     * İTÜ'nün açılan dersler listesinden gelen bilgiyi mevcut programa işler: adı boş derslere adını,
     * derslik bilgisi olmayan ders saatlerine (şubesi belli olduğunda) binayı ve dersliği yazar.
     * Dönen değer: bilgisi güncellenen ders sayısı.
     */
    fun applyCatalog(ctx: Context, catalog: Map<String, List<ItuSection>>): Int {
        val changed = HashSet<String>()
        val cfgs = courseConfigs(ctx).toMutableMap()
        for (code in slots(ctx).map { it.code }.distinct()) {
            val name = catalog[code]?.firstOrNull()?.name ?: continue
            val c = cfgs[code] ?: CourseConfig()
            if (c.name.isBlank()) {
                cfgs[code] = c.copy(name = name)
                changed += code
            }
        }
        if (changed.isNotEmpty()) saveCourseConfigs(ctx, cfgs)

        val slots = slots(ctx)
        val updated = slots.map { s ->
            if (s.place.isNotBlank()) return@map s
            val place = placeFor(catalog[s.code].orEmpty(), s.day, s.start) ?: return@map s
            changed += s.code
            s.copy(place = place)
        }
        if (updated != slots) saveSlots(ctx, updated)
        return changed.size
    }

    // ---------------- Ders ayarları ----------------

    fun courseConfigs(ctx: Context): Map<String, CourseConfig> {
        val json = settings(ctx).getString("courses", null) ?: return emptyMap()
        return runCatching { configsFromJson(JSONObject(json)) }.getOrDefault(emptyMap())
    }

    fun configsFromJson(o: JSONObject): Map<String, CourseConfig> {
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
        return out
    }

    fun configsToJson(map: Map<String, CourseConfig>): JSONObject {
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
        return o
    }

    private fun saveCourseConfigs(ctx: Context, map: Map<String, CourseConfig>) =
        settings(ctx).edit().putString("courses", configsToJson(map).toString()).apply()

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
        val explicit = statusMap(ctx)
        val e = attendance(ctx).edit()
        var any = false
        for (p in snap.polls) {
            if (p.code != code || p.index <= oldPolls || p.time.isAfter(now) || explicit[p.key] != null) continue
            if (p.session.date in snap.holidays) continue
            val first = explicit[pollKey(p.session.key, 1)] ?: continue
            e.putString(p.key, first.name)
            any = true
        }
        if (any) e.commit()
    }

    // ---------------- Yoklama kayıtları ----------------

    /** Elle verilmiş cevaplar (tatiller hariç). */
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

    // ---------------- Tatiller ----------------

    fun holidays(ctx: Context): Set<LocalDate> {
        val json = settings(ctx).getString("holidays", null) ?: return emptySet()
        return runCatching { datesFromJson(JSONArray(json)) }.getOrDefault(emptySet())
    }

    fun datesFromJson(arr: JSONArray): Set<LocalDate> =
        (0 until arr.length()).mapNotNull { runCatching { LocalDate.parse(arr.getString(it)) }.getOrNull() }.toSortedSet()

    fun datesToJson(dates: Collection<LocalDate>): JSONArray = JSONArray().apply { dates.sorted().forEach { put(it.toString()) } }

    private fun saveHolidays(ctx: Context, dates: Collection<LocalDate>) =
        settings(ctx).edit().putString("holidays", datesToJson(dates).toString()).apply()

    /**
     * Günleri tatil yapar: o günlerdeki bütün dersler "yapılmadı" sayılır. O günler için daha önce verilmiş
     * cevaplar silinir ki tatil hepsine uygulansın.
     */
    fun addHolidays(ctx: Context, dates: Collection<LocalDate>) {
        if (dates.isEmpty()) return
        val days = dates.map { it.toString() }.toSet()
        val att = attendance(ctx)
        val e = att.edit()
        var any = false
        for (k in att.all.keys) {
            if (k.split("|").getOrNull(1) in days) {
                e.remove(k)
                any = true
            }
        }
        if (any) e.commit()
        saveHolidays(ctx, holidays(ctx) + dates)
    }

    fun removeHolidays(ctx: Context, dates: Collection<LocalDate>) = saveHolidays(ctx, holidays(ctx) - dates.toSet())

    // ---------------- Sınavlar ----------------

    fun exams(ctx: Context): List<Exam> {
        val json = settings(ctx).getString("exams", null) ?: return emptyList()
        return runCatching { examsFromJson(JSONArray(json)) }.getOrDefault(emptyList())
    }

    fun examsFromJson(arr: JSONArray): List<Exam> = (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val date = runCatching { LocalDate.parse(o.getString("date")) }.getOrNull() ?: return@mapNotNull null
        Exam(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            code = o.optString("code", ""),
            title = o.optString("title", ""),
            date = date,
            time = o.optString("time", "").takeIf { it.isNotBlank() }?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
            place = o.optString("place", ""),
        )
    }

    fun examsToJson(list: List<Exam>): JSONArray = JSONArray().apply {
        list.forEach { e ->
            put(JSONObject().apply {
                put("id", e.id); put("code", e.code); put("title", e.title); put("date", e.date.toString())
                e.time?.let { put("time", it.toString()) }
                if (e.place.isNotBlank()) put("place", e.place)
            })
        }
    }

    private fun saveExams(ctx: Context, list: List<Exam>) =
        settings(ctx).edit().putString("exams", examsToJson(list).toString()).apply()

    /** Sınavı ekler ya da aynı kimlikteki sınavın yerine koyar. */
    fun saveExam(ctx: Context, exam: Exam) {
        val list = exams(ctx)
        saveExams(ctx, if (list.any { it.id == exam.id }) list.map { if (it.id == exam.id) exam else it } else list + exam)
    }

    fun removeExam(ctx: Context, id: String) = saveExams(ctx, exams(ctx).filterNot { it.id == id })

    fun newExamId(): String = UUID.randomUUID().toString()

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
            examReminder = p.getBoolean("n_exam", true),
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
            .putBoolean("n_exam", n.examReminder)
            .apply()
    }

    // ---------------- Zamanlayıcı imleci ----------------

    /** En son hangi ana kadar bildirimlerin işlendiği. */
    fun cursor(ctx: Context): LocalDateTime? =
        settings(ctx).getString("cursor", null)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

    fun setCursor(ctx: Context, t: LocalDateTime) = settings(ctx).edit().putString("cursor", t.toString()).apply()

    // ---------------- Hepsi bir arada ----------------

    fun snapshot(ctx: Context, now: LocalDateTime = AppClock.now()): Snapshot = buildSnapshot(
        now = now,
        semesterStart = semesterStart(ctx),
        weeks = weeks(ctx),
        requiredPercent = requiredPercent(ctx),
        slots = slots(ctx),
        configs = courseConfigs(ctx),
        explicit = statusMap(ctx),
        notify = notifySettings(ctx),
        exams = exams(ctx),
        holidays = holidays(ctx),
        termName = termName(ctx),
    )
}
