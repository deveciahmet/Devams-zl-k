package com.deveciahmet.devamsizlik

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime

/** Yedek dosyasının özeti (geri yüklemeden önce gösterilir). */
data class BackupInfo(
    val createdAt: LocalDateTime?,
    val termName: String,
    val courses: Int,
    val records: Int,
    val exams: Int,
    val archives: Int,
)

/**
 * Bütün kayıtları (ayarlar, program, yoklamalar, sınavlar, tatiller, arşivlenmiş dönemler) tek bir JSON dosyasına
 * yazar ve geri yükler. Telefon değişince veri kaybolmasın diye.
 */
object Backup {
    private const val APP = "devamsizlik"
    private const val FORMAT = 1

    /** Yedeklenen kayıt dosyaları. İTÜ ders listesi önbelleği gibi yeniden indirilebilen şeyler yedeklenmez. */
    private val PREFS = listOf(Repo.SETTINGS, Repo.ATTENDANCE, Archive.PREFS)

    /** Bu anahtarlar yedeklenmez: bildirim zamanlayıcısının iç durumu. */
    private val SKIP = setOf("cursor")

    private fun prefs(ctx: Context, name: String) = ctx.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    fun fileName(now: LocalDateTime = AppClock.now()): String = "devamsizlik-yedek-${now.toLocalDate()}.json"

    fun export(ctx: Context, now: LocalDateTime = AppClock.now()): String {
        val data = JSONObject()
        for (name in PREFS) {
            val o = JSONObject()
            for ((k, v) in prefs(ctx, name).all) {
                if (k in SKIP || v == null) continue
                val typed = when (v) {
                    is String -> JSONObject().put("s", v)
                    is Int -> JSONObject().put("i", v)
                    is Long -> JSONObject().put("l", v)
                    is Boolean -> JSONObject().put("b", v)
                    is Float -> JSONObject().put("f", v.toDouble())
                    is Set<*> -> JSONObject().put("ss", JSONArray(v.map { it.toString() }))
                    else -> continue
                }
                o.put(k, typed)
            }
            data.put(name, o)
        }
        return JSONObject()
            .put("app", APP)
            .put("format", FORMAT)
            .put("createdAt", now.toString())
            .put("data", data)
            .toString(1)
    }

    /** Dosyayı doğrular; bu uygulamanın yedeği değilse IllegalArgumentException fırlatır. */
    private fun parse(json: String): JSONObject {
        val root = runCatching { JSONObject(json.trim().removePrefix("﻿")) }.getOrNull()
            ?: throw IllegalArgumentException("Dosya okunamadı. Devamsızlık uygulamasının yedek dosyasını seç.")
        if (root.optString("app") != APP || root.optJSONObject("data") == null) {
            throw IllegalArgumentException("Bu dosya bir devamsızlık yedeği değil.")
        }
        if (root.optInt("format", 0) > FORMAT) {
            throw IllegalArgumentException("Bu yedek uygulamanın daha yeni bir sürümüyle alınmış. Önce uygulamayı güncelle.")
        }
        return root
    }

    fun inspect(json: String): BackupInfo {
        val root = parse(json)
        val data = root.getJSONObject("data")
        val settings = data.optJSONObject(Repo.SETTINGS) ?: JSONObject()
        fun str(key: String) = settings.optJSONObject(key)?.optString("s")
        val slots = str("slots")?.let { runCatching { Repo.slotsFromJson(JSONArray(it)) }.getOrNull() } ?: Repo.defaultSlots
        val start = str("start")?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
        return BackupInfo(
            createdAt = runCatching { LocalDateTime.parse(root.getString("createdAt")) }.getOrNull(),
            termName = str("term_name").orEmpty().ifBlank { start?.let { defaultTermName(it) }.orEmpty() },
            courses = slots.map { it.code }.distinct().size,
            records = data.optJSONObject(Repo.ATTENDANCE)?.length() ?: 0,
            exams = str("exams")?.let { runCatching { JSONArray(it).length() }.getOrNull() } ?: 0,
            archives = data.optJSONObject(Archive.PREFS)?.length() ?: 0,
        )
    }

    /** Şu anki bütün kayıtları yedektekilerle değiştirir. */
    fun restore(ctx: Context, json: String, now: LocalDateTime = AppClock.now()) {
        val data = parse(json).getJSONObject("data")
        for (name in PREFS) {
            val o = data.optJSONObject(name) ?: JSONObject()
            val e = prefs(ctx, name).edit().clear()
            for (k in o.keys()) {
                if (k in SKIP) continue
                val t = o.optJSONObject(k) ?: continue
                when {
                    t.has("s") -> e.putString(k, t.getString("s"))
                    t.has("i") -> e.putInt(k, t.getInt("i"))
                    t.has("l") -> e.putLong(k, t.getLong("l"))
                    t.has("b") -> e.putBoolean(k, t.getBoolean("b"))
                    t.has("f") -> e.putFloat(k, t.getDouble("f").toFloat())
                    t.has("ss") -> t.getJSONArray("ss").let { a -> e.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet()) }
                }
            }
            if (name == Repo.SETTINGS) e.putString("cursor", now.toString())
            e.commit()
        }
    }
}
