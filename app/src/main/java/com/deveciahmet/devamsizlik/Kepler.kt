package com.deveciahmet.devamsizlik

import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

// Kepler'deki ders programının fotoğrafından (ya da kopyalanan metninden) dersleri bulma.
// Fotoğraftaki metin telefonda okunur (OCR); buradaki kodlar sadece okunan metni yorumlar, Android'e bağımlı değil.
// Gün/saat/ad bilgisinin asıl kaynağı İTÜ'nün açılan dersler listesidir; fotoğraftan sadece hangi dersler
// (ve mümkünse hangi şube: CRN, gün sütunu, saat) olduğu çıkarılır.

/** OCR'ın bulduğu bir kelime ve fotoğraftaki yeri (piksel). */
data class OcrWord(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

/** OCR'ın bulduğu bir satır. */
data class OcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val words: List<OcrWord> = emptyList(),
) {
    val cx: Int get() = (left + right) / 2
    val cy: Int get() = (top + bottom) / 2
    val height: Int get() = (bottom - top).coerceAtLeast(1)
}

/** Bir dersin fotoğrafta göründüğü yerden çıkan ipucu: hangi gün, hangi saatte başlıyor / hangi saatte sürüyor. */
data class Hint(val day: Int? = null, val start: LocalTime? = null, val during: LocalTime? = null)

data class Findings(
    /** Ders kodu → göründüğü her yerdeki ipuçları. */
    val codes: Map<String, List<Hint>>,
    val crns: Set<String>,
    /** Sadeleştirilmiş kelimeler (derslik ya da hoca adını eşleştirmek için). */
    val words: Set<String>,
) {
    operator fun plus(o: Findings): Findings {
        val m = LinkedHashMap<String, List<Hint>>(codes)
        for ((k, v) in o.codes) m[k] = m[k].orEmpty() + v
        return Findings(m, crns + o.crns, words + o.words)
    }

    val isEmpty: Boolean get() = codes.isEmpty() && crns.isEmpty()

    companion object {
        val EMPTY = Findings(emptyMap(), emptySet(), emptySet())
    }
}

object KeplerReader {
    // "MAK 335", "MAK304E", "FIZ 101EL". Numarada O harfi 0 diye okunmuş olabilir.
    private val CODE = Regex("(?<![A-Z0-9])([A-Z]{2,4}) ?([0-9O]{3,4})([A-Z]{0,2})(?![A-Z0-9])")
    private val CRN = Regex("(?<![0-9:./])[1-9][0-9]{4}(?![0-9:./])")
    private val RANGE = Regex("(?<![0-9])([0-9]{1,2})[:.]([0-9]{2})\\s*[-–—/]\\s*([0-9]{1,2})[:.]([0-9]{2})(?![0-9])")
    private val TIME = Regex("([0-9]{1,2})[:.]([0-9]{2})")
    private val NON_LETTER = Regex("[^\\p{L}]+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    private val DAY_WORDS = mapOf(
        "pazartesi" to 1, "pzt" to 1, "pts" to 1, "monday" to 1, "mon" to 1,
        "sali" to 2, "sal" to 2, "tuesday" to 2, "tue" to 2,
        "carsamba" to 3, "car" to 3, "crs" to 3, "wednesday" to 3, "wed" to 3,
        "persembe" to 4, "per" to 4, "prs" to 4, "thursday" to 4, "thu" to 4,
        "cuma" to 5, "cum" to 5, "friday" to 5, "fri" to 5,
        "cumartesi" to 6, "cmt" to 6, "cts" to 6, "saturday" to 6, "sat" to 6,
        "pazar" to 7, "paz" to 7, "sunday" to 7, "sun" to 7,
    )

    fun dayOf(word: String): Int? = DAY_WORDS[foldTr(word).filter { it.isLetter() }]

    fun daysIn(text: String): List<Int> = text.split(NON_LETTER).filter { it.isNotEmpty() }.mapNotNull { dayOf(it) }

    /** Metindeki ders kodları ve yerleri. */
    fun codesIn(text: String): List<Pair<String, IntRange>> {
        val up = text.uppercase(Locale.ROOT)
        return CODE.findAll(up).mapNotNull { m ->
            val num = m.groupValues[2]
            if (num.count { it.isDigit() } < 2) return@mapNotNull null
            "${m.groupValues[1]} ${num.replace('O', '0')}${m.groupValues[3]}" to m.range
        }.toList()
    }

    fun crnsIn(text: String): List<String> = CRN.findAll(text).map { it.value }.toList()

    private fun time(h: String, m: String): LocalTime? {
        val hh = h.toIntOrNull() ?: return null
        val mm = m.toIntOrNull() ?: return null
        return if (hh in 0..23 && mm in 0..59) LocalTime.of(hh, mm) else null
    }

    fun rangesIn(text: String): List<Pair<LocalTime, LocalTime>> = RANGE.findAll(text).mapNotNull { m ->
        val a = time(m.groupValues[1], m.groupValues[2])
        val b = time(m.groupValues[3], m.groupValues[4])
        if (a != null && b != null && b.isAfter(a)) a to b else null
    }.toList()

    /** Satır sadece bir saat ya da saat aralığıysa (tablonun kenarındaki saatler) o saat. */
    fun labelTime(text: String): LocalTime? {
        val t = text.trim()
        RANGE.matchEntire(t)?.let { return time(it.groupValues[1], it.groupValues[2]) }
        TIME.matchEntire(t)?.let { return time(it.groupValues[1], it.groupValues[2]) }
        return null
    }

    fun wordsIn(text: String): Set<String> =
        text.split(NON_WORD).map { foldTr(it) }.filter { it.length >= 2 }.toSet()

    /** Aynı satırdaki gün ve saatlerden ipuçları: "Pazartesi Perşembe 15:30/17:29 08:30/10:29". */
    fun hintsFromText(text: String): List<Hint> {
        val days = daysIn(text)
        val ranges = rangesIn(text)
        return when {
            days.isEmpty() && ranges.isEmpty() -> listOf(Hint())
            days.size == ranges.size -> days.zip(ranges) { d, r -> Hint(day = d, start = r.first) }
            else -> days.map { Hint(day = it) } + ranges.map { Hint(start = it.first) }
        }
    }

    /**
     * Yapıştırılan ya da elle yazılan metin: her satır bir ders olabilir ("14893 MAK 335 … Pazartesi 10:30/13:29").
     * Satırda gün/saat yoksa bir sonraki dersin satırına kadar olan satırlara da bakılır (hücre hücre kopyalanmışsa).
     */
    fun fromText(text: String): Findings {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val codes = LinkedHashMap<String, MutableList<Hint>>()
        for ((i, line) in lines.withIndex()) {
            val found = codesIn(line)
            if (found.isEmpty()) continue
            var end = i + 1
            while (end < lines.size && end < i + 8 && codesIn(lines[end]).isEmpty()) end++
            val hints = hintsFromText(lines.subList(i, end).joinToString("  "))
            for ((code, _) in found) codes.getOrPut(code) { mutableListOf() } += hints
        }
        return Findings(codes, crnsIn(text).toSet(), wordsIn(text))
    }

    // ---------------- Fotoğraftaki yerleşimden okuma ----------------

    private data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val cx: Int get() = (left + right) / 2
        val cy: Int get() = (top + bottom) / 2
    }

    private data class DayPos(val day: Int, val x: Int, val y: Int, val bottom: Int)

    /** Haftalık tablonun gün sütunları (başlıktaki gün adlarının yatay konumları). */
    private class Columns(val days: List<Int>, val xs: List<Int>, val headerBottom: Int) {
        private val half = (xs.last() - xs.first()) / (xs.size - 1) / 2
        val left: Int get() = xs.first() - half

        fun dayAt(x: Int): Int? {
            if (x < xs.first() - half || x > xs.last() + half) return null
            return days[xs.indices.minBy { abs(xs[it] - x) }]
        }
    }

    /** Satırdaki ders kodları ve fotoğraftaki kutuları (kelimelerden; yoksa satırın kutusu). */
    private fun codeBoxes(l: OcrLine): List<Pair<String, Box>> {
        val found = codesIn(l.text)
        if (found.isEmpty()) return emptyList()
        val up = l.text.uppercase(Locale.ROOT)
        val spans = ArrayList<Pair<IntRange, OcrWord>>()
        var from = 0
        for (w in l.words) {
            val wt = w.text.uppercase(Locale.ROOT)
            val i = up.indexOf(wt, from)
            if (wt.isEmpty() || i < 0) {
                spans.clear()
                break
            }
            spans += (i until i + wt.length) to w
            from = i + wt.length
        }
        return found.map { (code, r) ->
            val ws = spans.filter { it.first.first <= r.last && it.first.last >= r.first }.map { it.second }
            code to if (ws.isEmpty()) Box(l.left, l.top, l.right, l.bottom)
            else Box(ws.minOf { it.left }, ws.minOf { it.top }, ws.maxOf { it.right }, ws.maxOf { it.bottom })
        }
    }

    private fun dayPositions(lines: List<OcrLine>): List<DayPos> {
        val out = ArrayList<DayPos>()
        for (l in lines) {
            val letters = l.text.split(NON_LETTER).filter { it.isNotEmpty() }
            if (letters.isEmpty() || letters.size > 8) continue
            val words = l.words.ifEmpty { listOf(OcrWord(l.text, l.left, l.top, l.right, l.bottom)) }
            val days = words.mapNotNull { w ->
                dayOf(w.text)?.let { DayPos(it, (w.left + w.right) / 2, (w.top + w.bottom) / 2, w.bottom) }
            }
            // Satırın çoğu gün adı olmalı: "Pazartesi", "Pzt 02.11", "Pazartesi Salı Çarşamba …"
            if (days.isNotEmpty() && days.size * 2 >= letters.size) out += days
        }
        return out
    }

    /**
     * Haftalık tablo başlığı: aynı hizada, soldan sağa sıralı en az iki gün adı. Aynı hizada ders kodu
     * olmamalı (liste görünümünde her satırda gün adı ve ders kodu yan yana durur, o başlık değildir).
     */
    private fun header(lines: List<OcrLine>, codes: List<Box>, h: Int): Columns? {
        val groups = ArrayList<MutableList<DayPos>>()
        for (p in dayPositions(lines).sortedBy { it.y }) {
            val g = groups.lastOrNull()
            if (g != null && abs(g.map { it.y }.average() - p.y) <= h * 0.8) g += p else groups += mutableListOf(p)
        }
        return groups.asSequence()
            .map { g -> g.distinctBy { it.day }.sortedBy { it.x } }
            .filter { g -> g.size >= 2 && g.zipWithNext().all { (a, b) -> a.day < b.day } }
            .filter { g ->
                val y = g.map { it.y }.average()
                codes.none { abs(it.cy - y) <= h }
            }
            .maxWithOrNull(compareBy<List<DayPos>> { it.size }.thenByDescending { it.first().y })
            ?.let { g -> Columns(g.map { it.day }, g.map { it.x }, g.maxOf { it.bottom }) }
    }

    private fun gridHint(box: Box, cols: Columns, lines: List<OcrLine>, labels: List<Pair<OcrLine, LocalTime>>, h: Int): Hint {
        if (box.cy <= cols.headerBottom) return Hint()
        val day = cols.dayAt(box.cx) ?: return Hint()
        // Aynı sütunda kodun hemen yanındaki saat aralığı ("10:30 - 13:29")
        val near = lines.asSequence()
            .filter { it.cy > cols.headerBottom && it.cy in (box.top - 3 * h)..(box.bottom + 5 * h) && cols.dayAt(it.cx) == day }
            .flatMap { l -> rangesIn(l.text).asSequence().map { r -> l to r } }
            .minByOrNull { abs(it.first.cy - box.cy) }
        if (near != null) return Hint(day = day, start = near.second.first)
        // Yoksa tablonun solundaki saat etiketlerinden kodun hizasındaki
        val label = labels.asSequence()
            .filter { it.first.cx < cols.left && it.first.cy > cols.headerBottom }
            .minByOrNull { abs(it.first.cy - box.cy) }
        return Hint(day = day, during = label?.second)
    }

    private fun rowHints(line: OcrLine, lines: List<OcrLine>, h: Int): List<Hint> {
        val band = lines.filter { abs(it.cy - line.cy) <= h * 0.7 }.sortedBy { it.left }
        return hintsFromText(band.joinToString("  ") { it.text })
    }

    /** Fotoğraftan okunan satırlar: haftalık tablo (gün sütunları) ya da liste (her satır bir ders) olabilir. */
    fun fromLines(lines: List<OcrLine>): Findings {
        val ls = lines.filter { it.text.isNotBlank() }
        if (ls.isEmpty()) return Findings.EMPTY
        val h = ls.map { it.height }.sorted()[ls.size / 2]
        val occurrences = ls.flatMap { l -> codeBoxes(l).map { (code, box) -> Triple(code, box, l) } }
        val cols = header(ls, occurrences.map { it.second }, h)
        val labels = ls.mapNotNull { l -> labelTime(l.text)?.let { l to it } }
        val codes = LinkedHashMap<String, MutableList<Hint>>()
        for ((code, box, line) in occurrences) {
            val hints = if (cols != null) listOf(gridHint(box, cols, ls, labels, h)) else rowHints(line, ls, h)
            codes.getOrPut(code) { mutableListOf() } += hints
        }
        val all = ls.joinToString("\n") { it.text }
        return Findings(codes, crnsIn(all).toSet(), wordsIn(all))
    }
}

// ---------------- İTÜ şubeleriyle eşleştirme ----------------

/** Aynı gün ve saatlerde yapılan şubeler tek seçenek sayılır (sadece hoca ya da derslik farklı). */
data class SectionOption(val meetings: List<ItuMeeting>, val sections: List<ItuSection>)

enum class MatchVia {
    /** Fotoğraftaki CRN ile şube kesin bulundu. */
    CRN,

    /** Fotoğraftaki gün/saat yerleşiminden bulundu. */
    HINTS,

    /** Dersin tek bir ders saati seçeneği var. */
    SINGLE,

    /** Birden çok şube var ve hangisi olduğu anlaşılamadı: kullanıcı seçmeli. */
    CHOICE,
}

data class CourseMatch(
    val code: String,
    val name: String,
    /** Olası ders saatleri; en olası başta. */
    val options: List<SectionOption>,
    /** Seçili seçenek (null: kullanıcı seçmeli). */
    val chosen: Int?,
    val via: MatchVia,
    /** CRN ile kesinleşen şube. */
    val section: ItuSection? = null,
) {
    val option: SectionOption? get() = chosen?.let { options.getOrNull(it) }
}

data class MatchResult(
    val courses: List<CourseMatch>,
    /** Branşı İTÜ'de olan ama bu dönem açılan derslerde bulunamayan (büyük ihtimalle yanlış okunmuş) kodlar. */
    val notFound: List<String>,
    /** Açılan derslerde var ama haftalık ders saati olmayan dersler (bitirme projesi gibi). */
    val noSchedule: List<String>,
)

fun meetingsText(meetings: List<ItuMeeting>): String =
    meetings.joinToString(", ") { "${dayShortName(it.day)} ${timeRange(it.start, it.end)}" }

object ScheduleMatcher {
    private fun signature(ms: List<ItuMeeting>) = ms.sortedWith(compareBy({ it.day }, { it.start }))
        .joinToString(";") { "${it.day}|${it.start}|${it.end}" }

    private fun near(a: LocalTime, b: LocalTime, minutes: Long) = abs(ChronoUnit.MINUTES.between(a, b)) <= minutes

    private fun covers(m: ItuMeeting, t: LocalTime) = !t.isBefore(m.start.minusMinutes(45)) && !t.isAfter(m.end)

    private fun tokens(s: String): List<String> = KeplerReader.wordsIn(s).toList()

    /** Seçeneğin fotoğraftaki ipuçlarıyla ne kadar uyuştuğu. */
    fun score(o: SectionOption, hints: List<Hint>, words: Set<String>): Double {
        var s = 0.0
        val ms = o.meetings
        for (h in hints) {
            val onDay = h.day?.let { d -> ms.filter { it.day == d } } ?: ms
            if (h.day != null && onDay.isEmpty()) continue
            val exact = when {
                h.start != null -> onDay.any { near(it.start, h.start, 30) }
                h.during != null -> onDay.any { covers(it, h.during) }
                else -> false
            }
            s += when {
                exact && h.day != null -> 3.0
                exact -> 1.0
                h.day != null -> 1.0
                else -> 0.0
            }
        }
        // Küçük artılar: şubelerden birinin dersliği ya da hocası fotoğrafta geçiyorsa
        if (o.sections.any { sec -> sec.meetings.any { m -> tokens(m.place).let { t -> t.isNotEmpty() && t.all { it in words } } } }) {
            s += 0.5
        }
        if (o.sections.any { sec -> tokens(sec.instructor).let { t -> t.size >= 2 && t.all { it in words } } }) s += 0.5
        return s
    }

    fun match(f: Findings, lookup: ItuLookup): MatchResult {
        val codes = LinkedHashSet<String>(f.codes.keys)
        // Kodu okunamamış ama CRN'i okunmuş şubeler
        for (list in lookup.catalog.values) for (s in list) if (s.crn in f.crns) codes += s.code

        val courses = ArrayList<CourseMatch>()
        val notFound = ArrayList<String>()
        val noSchedule = ArrayList<String>()
        for (code in codes) {
            val secs = lookup.catalog[code]
            if (secs.isNullOrEmpty()) {
                if (lookup.knownBranch(code)) notFound += code
                continue
            }
            val scheduled = secs.filter { it.meetings.isNotEmpty() }
            if (scheduled.isEmpty()) {
                noSchedule += code
                continue
            }
            val name = secs.first().name
            val options = scheduled.groupBy { signature(it.meetings) }.values.map { group ->
                SectionOption(group.first().meetings.sortedWith(compareBy({ it.day }, { it.start })), group)
            }
            val byCrn = scheduled.firstOrNull { it.crn in f.crns }
            courses += when {
                byCrn != null -> CourseMatch(code, name, options, options.indexOfFirst { byCrn in it.sections }, MatchVia.CRN, byCrn)
                options.size == 1 -> CourseMatch(code, name, options, 0, MatchVia.SINGLE)
                else -> {
                    val hints = f.codes[code].orEmpty()
                    val scored = options.map { it to score(it, hints, f.words) }.sortedByDescending { it.second }
                    val sorted = scored.map { it.first }
                    if (scored[0].second > 0 && scored[0].second > scored[1].second) {
                        CourseMatch(code, name, sorted, 0, MatchVia.HINTS)
                    } else {
                        CourseMatch(code, name, sorted, null, MatchVia.CHOICE)
                    }
                }
            }
        }
        return MatchResult(courses.sortedBy { it.code }, notFound.sorted(), noSchedule.sorted())
    }

    /** Bir ders saatinin derslik bilgisi: şube belliyse onunki, değilse bütün şubelerde aynıysa o. */
    fun placeOf(m: CourseMatch, meeting: ItuMeeting, words: Set<String>): String {
        fun at(s: ItuSection) = s.meetings.firstOrNull { it.day == meeting.day && it.start == meeting.start }?.place
        m.section?.let { return at(it).orEmpty() }
        val places = m.option?.sections?.mapNotNull { at(it) }?.distinct().orEmpty().filter { it.isNotBlank() }
        if (places.size <= 1) return places.firstOrNull().orEmpty()
        return places.filter { p -> tokens(p).let { t -> t.isNotEmpty() && t.all { it in words } } }.singleOrNull().orEmpty()
    }

    /** Seçilmiş derslerin programa eklenecek saatleri. */
    fun toSlots(matches: List<CourseMatch>, words: Set<String>): List<ImportedSlot> = matches.flatMap { m ->
        m.option?.meetings.orEmpty().map { mt ->
            ImportedSlot(m.code, m.name, mt.day, mt.start, mt.end, placeOf(m, mt, words))
        }
    }
}
