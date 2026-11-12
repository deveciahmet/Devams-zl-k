package com.deveciahmet.devamsizlik

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime

/** İçe aktarılan tek bir ders saati (yapay zekâdan ya da İTÜ'nün açılan dersler listesinden). */
data class ImportedSlot(
    val code: String,
    val name: String,
    val day: Int,
    val start: LocalTime,
    val end: LocalTime,
    val place: String = "",
)

/**
 * Programı yapay zekâ ile (hazır metin + fotoğraf + JSON cevabı yapıştırma) ekleme.
 * Şimdilik kapalı: yerine Kepler fotoğrafından ekleme kullanılıyor. Açmak için true yap.
 */
const val PROMPT_IMPORT_ENABLED = false

/** Türkçe karakterleri sadeleştirip küçük harfe çevirir: "Çarşamba" → "carsamba". */
fun foldTr(s: String): String {
    val sb = StringBuilder(s.length)
    for (c in s) {
        sb.append(
            when (c) {
                'ç', 'Ç' -> 'c'
                'ğ', 'Ğ' -> 'g'
                'ı', 'I', 'İ', 'i' -> 'i'
                'ö', 'Ö' -> 'o'
                'ş', 'Ş' -> 's'
                'ü', 'Ü' -> 'u'
                'â', 'Â' -> 'a'
                'î', 'Î' -> 'i'
                'û', 'Û' -> 'u'
                else -> c.lowercaseChar()
            }
        )
    }
    return sb.toString()
}

data class ParseResult(val entries: List<ImportedSlot>, val warnings: List<String>, val error: String?)

/** Kullanıcının fotoğrafla birlikte yapay zekâya göndereceği metin. */
val IMPORT_PROMPT = """
Ekteki fotoğraf/ekran görüntüsü bir haftalık ders programı. Programdaki her ders saatini aşağıdaki JSON biçimine çevir.

Kurallar:
1. Cevap olarak SADECE JSON dizisini yaz. Açıklama, başlık veya başka metin ekleme.
2. Her eleman bir ders saatidir: {"code": "...", "name": "...", "day": 1, "start": "HH:mm", "end": "HH:mm"}
3. "code": dersin kodu (örn. "MAK 335"). Kod yoksa dersin adını yaz.
4. "name": dersin tam adı fotoğrafta yazıyorsa onu yaz, yoksa "" bırak.
5. "day": 1=Pazartesi, 2=Salı, 3=Çarşamba, 4=Perşembe, 5=Cuma, 6=Cumartesi, 7=Pazar. Fotoğrafta gün yerine tarih varsa o tarihin haftanın hangi günü olduğunu yaz.
6. "start" ve "end": 24 saat biçiminde "HH:mm" (örn. "08:30", "13:29"). Saatleri fotoğrafta yazdığı gibi yaz, yuvarlama.
7. Aynı ders haftada birden fazla gün veya saatte varsa her biri ayrı eleman olsun.
8. Okuyamadığın bir değer varsa en olası değeri yaz.

Örnek cevap:
[{"code":"MAK 335","name":"","day":1,"start":"10:30","end":"13:29"},{"code":"MAK 303","name":"","day":4,"start":"08:30","end":"10:29"}]
""".trim()

object ScheduleImport {

    fun parse(raw: String): ParseResult {
        val text = clean(raw)
        if (text.isBlank()) return ParseResult(emptyList(), emptyList(), "Önce yapay zekânın cevabını yapıştır.")
        val arr = findArray(text) ?: return ParseResult(
            emptyList(), emptyList(),
            "Cevapta ders listesi (JSON) bulunamadı. Cevabı eksiksiz kopyaladığından emin ol; " +
                "gerekirse yapay zekâdan \"sadece JSON yaz\" diye tekrar iste.",
        )
        val warnings = ArrayList<String>()
        val out = LinkedHashMap<String, ImportedSlot>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o == null) {
                warnings += "${i + 1}. kayıt ders bilgisi değil"
                continue
            }
            val (slot, err) = parseEntry(o)
            if (slot == null) {
                warnings += "${i + 1}. kayıt atlandı: $err"
                continue
            }
            val k = "${slot.code.lowercase()}|${slot.day}|${slot.start}"
            if (out.containsKey(k)) {
                warnings += "${i + 1}. kayıt tekrar ettiği için atlandı (${slot.code})"
                continue
            }
            out[k] = slot
        }
        val entries = out.values.sortedWith(compareBy<ImportedSlot>({ it.day }, { it.start }))
        if (entries.isEmpty()) return ParseResult(emptyList(), warnings, "Geçerli ders saati bulunamadı.")
        return ParseResult(entries, warnings, null)
    }

    private fun clean(raw: String): String = raw
        .replace('“', '"').replace('”', '"').replace('„', '"')
        .replace('«', '"').replace('»', '"')
        .replace('‘', '\'').replace('’', '\'')
        .replace(' ', ' ')
        .replace(Regex("```[a-zA-Z]*"), " ")
        .trim()

    /** Metnin içinden ders listesini (JSON dizisi) bulur; etrafında açıklama olsa bile. */
    private fun findArray(t: String): JSONArray? {
        val starts = t.indices.filter { t[it] == '[' }.take(20)
        val ends = t.indices.filter { t[it] == ']' }.reversed().take(20)
        for (s in starts) for (e in ends) {
            if (e <= s) continue
            val arr = runCatching { JSONArray(t.substring(s, e + 1)) }.getOrNull() ?: continue
            if (arr.length() > 0 && arr.optJSONObject(0) != null) return arr
        }
        val os = t.indexOf('{')
        val oe = t.lastIndexOf('}')
        if (os >= 0 && oe > os) {
            val o = runCatching { JSONObject(t.substring(os, oe + 1)) }.getOrNull() ?: return null
            val keys = o.keys()
            while (keys.hasNext()) {
                val a = o.optJSONArray(keys.next())
                if (a != null && a.length() > 0) return a
            }
            return JSONArray().put(o)
        }
        return null
    }

    private fun fold(s: String): String = foldTr(s)

    private fun fields(o: JSONObject): Map<String, String> {
        val m = HashMap<String, String>()
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next()
            if (o.isNull(k)) continue
            val v = o.opt(k)?.toString()?.trim().orEmpty()
            if (v.isNotEmpty()) m[fold(k).filter { c -> c.isLetter() }] = v
        }
        return m
    }

    private fun Map<String, String>.pick(vararg keys: String): String? = keys.firstNotNullOfOrNull { this[it] }

    private fun parseEntry(o: JSONObject): Pair<ImportedSlot?, String?> {
        val f = fields(o)
        val name = f.pick("name", "ad", "adi", "isim", "dersadi", "title", "coursename") ?: ""
        val codeRaw = f.pick("code", "kod", "derskodu", "coursecode", "course", "ders") ?: name.ifBlank { null }
            ?: return null to "ders kodu yok"
        val dayRaw = f.pick("day", "gun", "weekday", "dayofweek") ?: return null to "gün yok ($codeRaw)"
        val day = parseDay(dayRaw) ?: return null to "gün anlaşılamadı: \"$dayRaw\" ($codeRaw)"

        var startRaw = f.pick("start", "baslangic", "begin", "from", "starttime", "baslangicsaati")
        var endRaw = f.pick("end", "bitis", "finish", "to", "endtime", "bitissaati")
        if (startRaw == null || endRaw == null) {
            val range = f.pick("time", "saat", "hours", "zaman")?.split('-', '–', '—')?.map { it.trim() }
            if (range != null && range.size == 2) {
                if (startRaw == null) startRaw = range[0]
                if (endRaw == null) endRaw = range[1]
            }
        }
        val start = startRaw?.let(::parseTime) ?: return null to "başlangıç saati anlaşılamadı: \"${startRaw.orEmpty()}\" ($codeRaw)"
        val end = endRaw?.let(::parseTime) ?: return null to "bitiş saati anlaşılamadı: \"${endRaw.orEmpty()}\" ($codeRaw)"
        if (!end.isAfter(start)) return null to "bitiş saati başlangıçtan önce ($codeRaw ${start.format(timeFmt)}–${end.format(timeFmt)})"

        val code = normalizeCode(codeRaw)
        val cleanName = name.trim().takeUnless { it.equals(codeRaw, ignoreCase = true) || normalizeCode(it) == code }.orEmpty()
        return ImportedSlot(code, cleanName, day, start, end) to null
    }

    private val DAY_ALIASES = mapOf(
        "pzt" to 1, "pts" to 1, "pt" to 1, "mon" to 1,
        "sal" to 2, "sl" to 2, "tue" to 2, "tues" to 2,
        "car" to 3, "crs" to 3, "cars" to 3, "wed" to 3,
        "per" to 4, "prs" to 4, "pers" to 4, "thu" to 4, "thur" to 4, "thurs" to 4,
        "cum" to 5, "cm" to 5, "fri" to 5,
        "cmt" to 6, "cts" to 6, "sat" to 6,
        "paz" to 7, "pz" to 7, "sun" to 7,
    )

    // Uzun isimler önce: "pazartesi" "pazar"dan, "cumartesi" "cuma"dan önce denenmeli.
    private val DAY_FULL = listOf(
        "pazartesi" to 1, "sali" to 2, "carsamba" to 3, "persembe" to 4, "cumartesi" to 6, "cuma" to 5, "pazar" to 7,
        "monday" to 1, "tuesday" to 2, "wednesday" to 3, "thursday" to 4, "friday" to 5, "saturday" to 6, "sunday" to 7,
    )

    fun parseDay(raw: String): Int? {
        val trimmed = raw.trim()
        trimmed.toIntOrNull()?.let { return it.takeIf { d -> d in 1..7 } }
        trimmed.toDoubleOrNull()?.let { d -> return d.toInt().takeIf { d % 1.0 == 0.0 && it in 1..7 } }
        val n = fold(trimmed).filter { it.isLetter() }
        if (n.isEmpty()) return null
        DAY_ALIASES[n]?.let { return it }
        for ((name, d) in DAY_FULL) if (n.startsWith(name)) return d
        return null
    }

    private val TIME_RE = Regex("^(\\d{1,2})[:.](\\d{2})(?::\\d{2})?$")
    private val TIME_COMPACT_RE = Regex("^(\\d{2})(\\d{2})$")

    fun parseTime(raw: String): LocalTime? {
        val s = raw.trim()
        val m = TIME_RE.matchEntire(s) ?: TIME_COMPACT_RE.matchEntire(s) ?: return null
        val h = m.groupValues[1].toInt()
        val mi = m.groupValues[2].toInt()
        if (h !in 0..23 || mi !in 0..59) return null
        return LocalTime.of(h, mi)
    }
}
