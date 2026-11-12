package com.deveciahmet.devamsizlik

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/** Arşivlenmiş bir dönemin listede görünen bilgileri. */
data class ArchiveInfo(
    val id: String,
    val name: String,
    val start: LocalDate,
    val weeks: Int,
    val archivedAt: LocalDateTime,
    val courseCount: Int,
)

/** Dönem toplamları (özet ekranı için). */
data class TermTotals(val attended: Int, val absent: Int, val cancelled: Int, val unanswered: Int, val courses: Int, val failed: Int)

fun termTotals(snap: Snapshot): TermTotals = TermTotals(
    attended = snap.stats.sumOf { it.attended },
    absent = snap.stats.sumOf { it.absent },
    cancelled = snap.stats.sumOf { it.cancelled },
    unanswered = snap.stats.sumOf { it.unanswered },
    courses = snap.stats.size,
    failed = snap.stats.count { it.remaining < 0 },
)

/**
 * Dönem bitince "Yeni dönem başlat": şu anki dönemin bütün verisi (program, ders ayarları, yoklamalar, tatiller,
 * sınavlar) arşive kopyalanır ve yeni dönem boş başlar. Bildirim ayarları ve genel devam şartı korunur.
 */
object Archive {
    const val PREFS = "archive"

    fun prefs(ctx: Context): SharedPreferences = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Şu anki dönemin bütün verisi. */
    fun currentTermJson(ctx: Context, name: String, now: LocalDateTime): JSONObject = JSONObject().apply {
        put("name", name.trim().ifBlank { Repo.termName(ctx) })
        put("start", Repo.semesterStart(ctx).toString())
        put("weeks", Repo.weeks(ctx))
        put("required", Repo.requiredPercent(ctx))
        put("slots", Repo.slotsToJson(Repo.slots(ctx)))
        put("courses", Repo.configsToJson(Repo.courseConfigs(ctx)))
        put("attendance", JSONObject().apply { Repo.statusMap(ctx).forEach { (k, v) -> put(k, v.name) } })
        put("holidays", Repo.datesToJson(Repo.holidays(ctx)))
        put("exams", Repo.examsToJson(Repo.exams(ctx)))
        put("archivedAt", now.toString())
    }

    private fun load(ctx: Context, id: String): JSONObject? =
        prefs(ctx).getString(id, null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun info(id: String, o: JSONObject): ArchiveInfo? = runCatching {
        val slots = o.optJSONArray("slots") ?: JSONArray()
        ArchiveInfo(
            id = id,
            name = o.optString("name"),
            start = LocalDate.parse(o.getString("start")),
            weeks = o.optInt("weeks", 13),
            archivedAt = LocalDateTime.parse(o.getString("archivedAt")),
            courseCount = (0 until slots.length()).map { slots.getJSONObject(it).optString("code") }.distinct().size,
        )
    }.getOrNull()

    /** Arşivlenmiş dönemler, en yenisi başta. */
    fun list(ctx: Context): List<ArchiveInfo> = prefs(ctx).all.mapNotNull { (id, v) ->
        (v as? String)?.let { s -> runCatching { JSONObject(s) }.getOrNull()?.let { info(id, it) } }
    }.sortedByDescending { it.start }

    fun info(ctx: Context, id: String): ArchiveInfo? = load(ctx, id)?.let { info(id, it) }

    /** Arşivlenmiş dönemin, arşivlendiği andaki hali. */
    fun snapshot(ctx: Context, id: String): Snapshot? {
        val o = load(ctx, id) ?: return null
        return runCatching {
            val att = o.optJSONObject("attendance") ?: JSONObject()
            val explicit = att.keys().asSequence().mapNotNull { k ->
                runCatching { Status.valueOf(att.getString(k)) }.getOrNull()?.let { k to it }
            }.toMap()
            buildSnapshot(
                now = LocalDateTime.parse(o.getString("archivedAt")),
                semesterStart = LocalDate.parse(o.getString("start")),
                weeks = o.optInt("weeks", 13),
                requiredPercent = o.optInt("required", 70),
                slots = Repo.slotsFromJson(o.optJSONArray("slots") ?: JSONArray()),
                configs = Repo.configsFromJson(o.optJSONObject("courses") ?: JSONObject()),
                explicit = explicit,
                exams = Repo.examsFromJson(o.optJSONArray("exams") ?: JSONArray()),
                holidays = Repo.datesFromJson(o.optJSONArray("holidays") ?: JSONArray()),
                termName = o.optString("name"),
            )
        }.getOrNull()
    }

    /** Şu anki dönemde kaydedilmiş bir şey var mı (boş dönemi arşivlemeye gerek yok). */
    fun currentHasData(ctx: Context): Boolean =
        Repo.statusMap(ctx).isNotEmpty() || Repo.slots(ctx).isNotEmpty() || Repo.exams(ctx).isNotEmpty()

    /**
     * Şu anki dönemi [name] adıyla arşivler ve [newStart] haftasında yeni, boş bir dönem başlatır.
     * Arşivin kimliğini döner (geri almak için).
     */
    fun startNewTerm(ctx: Context, name: String, newStart: LocalDate, now: LocalDateTime = AppClock.now()): String {
        val id = UUID.randomUUID().toString()
        prefs(ctx).edit().putString(id, currentTermJson(ctx, name, now).toString()).commit()
        Repo.attendance(ctx).edit().clear().commit()
        Repo.settings(ctx).edit()
            .putString("slots", "[]")
            .remove("courses").remove("holidays").remove("exams").remove("term_name")
            .putString("start", mondayOf(newStart).toString())
            .putString("cursor", now.toString())
            .commit()
        return id
    }

    /**
     * Arşivdeki dönemi şu anki dönem yapar. Şu anki dönemde veri varsa önce o arşivlenir (hiçbir şey kaybolmaz).
     */
    fun restore(ctx: Context, id: String, now: LocalDateTime = AppClock.now()) {
        val o = load(ctx, id) ?: return
        if (currentHasData(ctx)) {
            prefs(ctx).edit().putString(UUID.randomUUID().toString(), currentTermJson(ctx, Repo.termName(ctx), now).toString()).commit()
        }
        val att = o.optJSONObject("attendance") ?: JSONObject()
        val e = Repo.attendance(ctx).edit().clear()
        att.keys().forEach { k -> e.putString(k, att.getString(k)) }
        e.commit()
        Repo.settings(ctx).edit()
            .putString("slots", (o.optJSONArray("slots") ?: JSONArray()).toString())
            .putString("courses", (o.optJSONObject("courses") ?: JSONObject()).toString())
            .putString("holidays", (o.optJSONArray("holidays") ?: JSONArray()).toString())
            .putString("exams", (o.optJSONArray("exams") ?: JSONArray()).toString())
            .putString("term_name", o.optString("name"))
            .putString("start", o.getString("start"))
            .putInt("weeks", o.optInt("weeks", 13))
            .putInt("required", o.optInt("required", 70))
            .putString("cursor", now.toString())
            .commit()
        prefs(ctx).edit().remove(id).commit()
    }

    fun delete(ctx: Context, id: String) = prefs(ctx).edit().remove(id).commit()
}
