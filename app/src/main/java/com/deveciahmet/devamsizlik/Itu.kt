package com.deveciahmet.devamsizlik

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalTime

// İTÜ'nün herkese açık "Ders Programı" (açılan dersler) sayfası: https://obs.itu.edu.tr/public/DersProgram
// Bir ders kodunun adı, şubeleri (CRN), günleri, saatleri ve derslikleri buradan alınır.

/** Bir şubenin haftalık tek bir ders saati. place: "MKB A501" */
data class ItuMeeting(val day: Int, val start: LocalTime, val end: LocalTime, val place: String)

/** Açılan derslerdeki tek bir şube (CRN). */
data class ItuSection(
    val crn: String,
    val code: String,
    val name: String,
    val instructor: String,
    val meetings: List<ItuMeeting>,
)

/** Sayfanın cevaplarını çözümler (ağdan bağımsız, birim testleriyle sınanıyor). */
object ItuParser {
    private val ROW = Regex("<tr[^>]*>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL)
    private val CELL = Regex("<td[^>]*>(.*?)</td>", RegexOption.DOT_MATCHES_ALL)
    private val BR = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]+>")
    private val SPACES = Regex("\\s+")
    private val ENTITY = Regex("&(#[xX][0-9a-fA-F]+|#\\d+|[a-zA-Z]+);")
    private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")

    /** HTML karakter kodlarını çözer: "Is&#x131;" → "Isı" */
    fun decode(s: String): String = ENTITY.replace(s) { m ->
        val e = m.groupValues[1]
        val cp = when {
            e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)
            e.startsWith("#") -> e.substring(1).toIntOrNull()
            else -> null
        }
        when {
            cp != null && Character.isValidCodePoint(cp) -> String(Character.toChars(cp))
            cp == null -> NAMED[e] ?: m.value
            else -> m.value
        }
    }

    private fun text(html: String): String = decode(TAG.replace(html, " ")).replace(SPACES, " ").trim()

    /** Hücredeki satırlar (<br> ile ayrılmış). */
    private fun lines(html: String): List<String> = BR.split(html).map { text(it) }

    private fun meaningful(s: String?): String? = s?.trim()?.takeUnless { it.isEmpty() || it.all { c -> c == '-' } }

    /** "15:30/17:29" → (15:30, 17:29) */
    fun parseRange(s: String?): Pair<LocalTime, LocalTime>? {
        val parts = s?.split('/', '-', '–')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return null
        if (parts.size != 2) return null
        val a = ScheduleImport.parseTime(parts[0]) ?: return null
        val b = ScheduleImport.parseTime(parts[1]) ?: return null
        return if (b.isAfter(a)) a to b else null
    }

    /** Ders programı tablosu → şubeler. Sütunlar: CRN, Kod, Ad, Yöntem, Hoca, Bina, Gün, Saat, Derslik, … */
    fun sections(html: String): List<ItuSection> {
        val body = html.substringAfter("<tbody", "")
        val out = ArrayList<ItuSection>()
        for (row in ROW.findAll(body)) {
            val cells = CELL.findAll(row.groupValues[1]).map { it.groupValues[1] }.toList()
            if (cells.size < 9) continue
            val crn = text(cells[0])
            if (!crn.matches(Regex("\\d{4,6}"))) continue
            val code = normalizeCode(text(cells[1]))
            if (code.isBlank()) continue
            val buildings = lines(cells[5])
            val days = lines(cells[6])
            val times = lines(cells[7])
            val rooms = lines(cells[8])
            val meetings = days.indices.mapNotNull { i ->
                val day = meaningful(days[i])?.let { ScheduleImport.parseDay(it) } ?: return@mapNotNull null
                val (start, end) = parseRange(times.getOrNull(i)) ?: return@mapNotNull null
                val place = listOfNotNull(meaningful(buildings.getOrNull(i)), meaningful(rooms.getOrNull(i))).joinToString(" ")
                ItuMeeting(day, start, end, place)
            }
            out += ItuSection(crn, code, text(cells[2]), text(cells[4]), meetings)
        }
        return out
    }

    /** [{"bransKoduId":12,"dersBransKodu":"MAK"}, …] → {"MAK": 12} */
    fun branches(json: String): Map<String, Int> {
        val arr = JSONArray(json)
        val out = LinkedHashMap<String, Int>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val code = o.optString("dersBransKodu").trim().uppercase()
            val id = o.optInt("bransKoduId", -1)
            if (code.isNotEmpty() && id >= 0) out[code] = id
        }
        return out
    }

    fun sectionsToJson(list: List<ItuSection>): JSONArray = JSONArray().apply {
        list.forEach { s ->
            put(JSONObject().apply {
                put("crn", s.crn); put("code", s.code); put("name", s.name); put("ins", s.instructor)
                put("m", JSONArray().apply {
                    s.meetings.forEach { m ->
                        put(JSONObject().apply {
                            put("d", m.day); put("s", m.start.toString()); put("e", m.end.toString()); put("p", m.place)
                        })
                    }
                })
            })
        }
    }

    fun sectionsFromJson(arr: JSONArray): List<ItuSection> = (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val m = o.getJSONArray("m")
        ItuSection(
            o.getString("crn"), o.getString("code"), o.optString("name"), o.optString("ins"),
            (0 until m.length()).map { j ->
                val x = m.getJSONObject(j)
                ItuMeeting(x.getInt("d"), LocalTime.parse(x.getString("s")), LocalTime.parse(x.getString("e")), x.optString("p"))
            },
        )
    }
}

/** Şubesi belli olmayan bir ders saati için derslik: o gün ve saatteki bütün şubeler aynı yerdeyse o yer. */
fun placeFor(sections: List<ItuSection>, day: Int, start: LocalTime): String? =
    sections.flatMap { s -> s.meetings.filter { it.day == day && it.start == start }.map { it.place } }
        .distinct().singleOrNull()?.takeIf { it.isNotBlank() }

/** Verinin nereden geldiği; testlerde sahte bir kaynakla değiştirilir. */
interface ItuSource {
    /** level: "LS" lisans, "LU" lisansüstü … */
    fun branches(level: String): Map<String, Int>
    fun sections(level: String, branchId: Int): List<ItuSection>
    fun activeTerm(level: String): String?
}

class ItuWebSource(private val base: String = "https://obs.itu.edu.tr/public/DersProgram") : ItuSource {
    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "Devamsizlik-Android")
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    override fun branches(level: String) =
        ItuParser.branches(get("$base/SearchBransKoduByProgramSeviye?programSeviyeTipiAnahtari=$level"))

    override fun sections(level: String, branchId: Int) =
        ItuParser.sections(get("$base/DersProgramSearch?programSeviyeTipiAnahtari=$level&dersBransKoduId=$branchId"))

    override fun activeTerm(level: String): String? = runCatching {
        JSONObject(get("$base/GetAktifDonemByProgramSeviye?programSeviyeTipiAnahtari=$level")).optString("aktifDonem")
    }.getOrNull()?.trim()?.ifBlank { null }
}

/** Arama sonucu: koda göre şubeler ve İTÜ'de var olan branş kodları (yanlış okunmuş kodları ayıklamak için). */
class ItuLookup(val catalog: Map<String, List<ItuSection>>, val branches: Set<String>) {
    fun knownBranch(code: String): Boolean = code.substringBefore(' ') in branches
}

object Itu {
    @Volatile
    var source: ItuSource = ItuWebSource()

    /** Uygulama açılınca adı boş dersleri sessizce doldursun mu (testlerde kapatılır). */
    @Volatile
    var autoFill: Boolean = true

    private const val CACHE = "itu_cache"
    private val LEVELS = listOf("LS", "LU")
    private const val BRANCH_TTL = 7L * 24 * 3600 * 1000
    private const val SECTION_TTL = 6L * 3600 * 1000

    private fun cache(ctx: Context) = ctx.applicationContext.getSharedPreferences(CACHE, Context.MODE_PRIVATE)

    fun clearCache(ctx: Context) = cache(ctx).edit().clear().commit()

    /** Önbellekte tazeyse oradan, değilse ağdan; ağ hatasında eski önbellek de olur. */
    private fun <T> cached(ctx: Context, key: String, ttl: Long, now: Long, load: () -> T, enc: (T) -> String, dec: (String) -> T): T {
        val p = cache(ctx)
        val at = p.getLong("$key@", 0)
        val old = p.getString(key, null)?.let { runCatching { dec(it) }.getOrNull() }
        if (old != null && now - at < ttl) return old
        return try {
            load().also { p.edit().putString(key, enc(it)).putLong("$key@", now).apply() }
        } catch (e: IOException) {
            old ?: throw e
        }
    }

    private fun branchIds(ctx: Context, level: String, now: Long): Map<String, Int> =
        cached(ctx, "br_$level", BRANCH_TTL, now, { source.branches(level) },
            { JSONObject(it as Map<*, *>).toString() },
            { s -> JSONObject(s).let { o -> o.keys().asSequence().associateWith { k -> o.getInt(k) } } })

    private fun sections(ctx: Context, level: String, id: Int, now: Long): List<ItuSection> =
        cached(ctx, "sec_${level}_$id", SECTION_TTL, now, { source.sections(level, id) },
            { ItuParser.sectionsToJson(it).toString() }, { ItuParser.sectionsFromJson(JSONArray(it)) })

    /**
     * Ders kodlarının branşlarındaki bütün şubeleri getirir (önce lisans, bulunamayan kodlar için lisansüstü).
     * Ağa ulaşılamazsa ve önbellekte de yoksa IOException fırlatır. Ana iş parçacığında çağırma.
     */
    fun lookup(ctx: Context, codes: Collection<String>, now: Long = System.currentTimeMillis()): ItuLookup {
        val wanted = codes.map { normalizeCode(it) }.filter { it.contains(' ') }.toSet()
        val out = HashMap<String, List<ItuSection>>()
        val known = HashSet<String>()
        val fetched = HashSet<String>()
        for (level in LEVELS) {
            val missing = wanted.filter { it !in out }
            if (missing.isEmpty()) break
            val ids = branchIds(ctx, level, now)
            known += ids.keys
            for (branch in missing.map { it.substringBefore(' ') }.distinct()) {
                val id = ids[branch] ?: continue
                if (!fetched.add("$level|$branch")) continue
                for ((code, list) in sections(ctx, level, id, now).groupBy { it.code }) {
                    if (code !in out) out[code] = list
                }
            }
        }
        return ItuLookup(out, known)
    }

    /** İTÜ'nün şu anki dönemi ("2026-2027 Güz Dönemi"); bulunamazsa null. */
    fun activeTerm(): String? = runCatching { source.activeTerm("LS") }.getOrNull()

    /**
     * Programdaki derslerin adlarını (boşsa) ve dersliklerini İTÜ'den doldurur. Dönen değer: güncellenen ders sayısı.
     * Ağ hatasında IOException fırlatır. Ana iş parçacığında çağırma.
     */
    fun fillCourseInfo(ctx: Context): Int {
        val codes = Repo.slots(ctx).map { it.code }.distinct()
        if (codes.isEmpty()) return 0
        return Repo.applyCatalog(ctx, lookup(ctx, codes).catalog)
    }

    /**
     * Uygulama açılınca, adı boş ders varsa günde en fazla bir kez sessizce İTÜ'den doldurmayı dener.
     */
    fun autoFillIfNeeded(ctx: Context, now: Long = System.currentTimeMillis()) {
        val cfgs = Repo.courseConfigs(ctx)
        if (Repo.slots(ctx).none { cfgs[it.code]?.name.isNullOrBlank() }) return
        val p = cache(ctx)
        if (now - p.getLong("autofill@", 0) < 24L * 3600 * 1000) return
        p.edit().putLong("autofill@", now).apply()
        runCatching { if (fillCourseInfo(ctx) > 0) Widgets.refresh(ctx) }
    }
}
