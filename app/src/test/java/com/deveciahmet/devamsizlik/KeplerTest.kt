package com.deveciahmet.devamsizlik

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/** Kepler ekran görüntüsünden okunan metnin yorumlanması ve İTÜ şubeleriyle eşleştirilmesi. */
class KeplerTest {
    private fun t(s: String) = LocalTime.parse(s)

    /** OCR satırı: kelimeler soldan sağa, her harf ~14 piksel. */
    private fun line(text: String, x: Int, y: Int, h: Int = 30): OcrLine {
        val words = ArrayList<OcrWord>()
        var cx = x
        for (w in text.split(" ")) {
            words += OcrWord(w, cx, y, cx + w.length * 14, y + h)
            cx += w.length * 14 + 10
        }
        return OcrLine(text, x, y, cx - 10, y + h, words)
    }

    private val lookup: ItuLookup by lazy {
        val all = listOf("MAK", "ITB", "BEB", "FIZ").flatMap { ItuParser.sections(fixture("itu/$it.html")) }
        ItuLookup(all.groupBy { it.code }, ItuParser.branches(fixture("itu/branches_LS.json")).keys)
    }

    // Haftalık tablo: başlıkta gün adları, solda saatler; her ders bloğunda sadece ders kodu yazıyor.
    private val dayX = mapOf(1 to 260, 2 to 450, 3 to 640, 4 to 830, 5 to 1020)
    private val rowY = mapOf("08:30" to 300, "09:30" to 380, "10:30" to 460, "11:30" to 540, "12:30" to 620,
        "13:30" to 700, "14:30" to 780, "15:30" to 860, "16:30" to 940)

    private fun gridLines(blocks: List<Triple<String, Int, String>>, withTimes: Boolean = false): List<OcrLine> {
        val out = ArrayList<OcrLine>()
        out += line("Saat", 30, 200)
        listOf("Pazartesi", "Salı", "Çarşamba", "Perşembe", "Cuma").forEachIndexed { i, d ->
            out += line(d, dayX.getValue(i + 1) - d.length * 7, 200)
        }
        rowY.forEach { (time, y) -> out += line(time, 20, y + 25) }
        for ((code, day, time) in blocks) {
            val y = rowY.getValue(time) + 10
            out += line(code, dayX.getValue(day) - 60, y)
            if (withTimes) {
                val end = LocalTime.parse(time).plusMinutes(119).format(timeFmt)
                out += line("$time - $end", dayX.getValue(day) - 80, y + 36)
            }
        }
        return out
    }

    @Test
    fun findsCodesCrnsDaysAndTimesInText() {
        assertEquals(listOf("MAK 335", "MAK 304E", "FIZ 101EL"), KeplerReader.codesIn("mak335 / MAK 304E, FIZ 101EL").map { it.first })
        // O harfi sıfır diye okunmuş, CRN ve saat kod sanılmamalı
        assertEquals(listOf("MAK 305"), KeplerReader.codesIn("MAK 3O5 CRN 14955 10:30").map { it.first })
        assertEquals(listOf("14955"), KeplerReader.crnsIn("MAK 305 14955 02.11.2026 10:30"))
        assertEquals(listOf(1, 4), KeplerReader.daysIn("PAZARTESİ / Perşembe"))
        assertEquals(listOf(t("15:30") to t("17:29"), t("08:30") to t("10:29")), KeplerReader.rangesIn("15:30/17:29 08.30-10.29"))
        assertEquals(t("10:30"), KeplerReader.labelTime(" 10:30 "))
        assertNull(KeplerReader.labelTime("MAK 335 10:30"))
    }

    @Test
    fun gridScreenshot_dayColumnsAndRowLabelsPickTheRightSection() {
        val f = KeplerReader.fromLines(
            gridLines(
                listOf(
                    Triple("MAK 335", 1, "10:30"),
                    Triple("MAK 303", 1, "15:30"),
                    Triple("ITB 220", 2, "14:30"),
                    Triple("MAK 303", 4, "08:30"),
                    Triple("BEB 104", 5, "10:30"),
                )
            )
        )
        assertEquals(listOf(Hint(day = 2, during = t("14:30"))), f.codes["ITB 220"])
        assertEquals(listOf(Hint(day = 5, during = t("10:30"))), f.codes["BEB 104"])
        assertEquals(2, f.codes.getValue("MAK 303").size)

        val r = ScheduleMatcher.match(f, lookup)
        assertEquals(listOf("BEB 104", "ITB 220", "MAK 303", "MAK 335"), r.courses.map { it.code })
        val itb = r.courses.single { it.code == "ITB 220" }
        assertEquals(MatchVia.HINTS, itb.via)
        assertEquals(listOf(ItuMeeting(2, t("14:30"), t("17:29"), "MED B34")), itb.option!!.meetings)
        val beb = r.courses.single { it.code == "BEB 104" }
        assertEquals(MatchVia.HINTS, beb.via)
        assertEquals(5, beb.option!!.meetings.single().day)
        assertEquals(t("10:30"), beb.option!!.meetings.single().start)
        assertEquals(MatchVia.SINGLE, r.courses.single { it.code == "MAK 335" }.via)
        assertEquals("Isı Geçişi", r.courses.single { it.code == "MAK 303" }.name)
    }

    @Test
    fun gridScreenshot_timesInsideBlocks() {
        val f = KeplerReader.fromLines(gridLines(listOf(Triple("BEB 104", 5, "14:30")), withTimes = true))
        assertEquals(listOf(Hint(day = 5, start = t("14:30"))), f.codes["BEB 104"])
    }

    @Test
    fun listScreenshot_crnDecidesTheSection() {
        val lines = listOf(
            line("CRN", 20, 100), line("Ders Kodu", 140, 100), line("Ders Adı", 300, 100), line("Gün", 700, 100),
            line("10071", 20, 160), line("BEB 104", 140, 160), line("Tenis Teknik ve Taktik Eğitim", 300, 160),
            line("Cuma", 700, 160), line("10:30/12:29", 820, 160),
            line("14326", 20, 220), line("ITB 220", 140, 220), line("Psikoloji", 300, 220),
            line("Salı", 700, 220), line("14:30/17:29", 820, 220),
            line("14882", 20, 280), line("MAK 303", 140, 280), line("Isı Geçişi", 300, 280),
            line("Pazartesi Perşembe", 620, 280), line("15:30/17:29 08:30/10:29", 820, 280),
        )
        val f = KeplerReader.fromLines(lines)
        assertEquals(setOf("10071", "14326", "14882"), f.crns)
        // Liste görünümünde başlık yok: ipuçları aynı satırdan
        assertEquals(listOf(Hint(day = 1, start = t("15:30")), Hint(day = 4, start = t("08:30"))), f.codes["MAK 303"])

        val r = ScheduleMatcher.match(f, lookup)
        val beb = r.courses.single { it.code == "BEB 104" }
        assertEquals(MatchVia.CRN, beb.via)
        assertEquals("10071", beb.section!!.crn)
        assertEquals("14326", r.courses.single { it.code == "ITB 220" }.section!!.crn)
        val slots = ScheduleMatcher.toSlots(r.courses, f.words)
        assertEquals(
            ImportedSlot("BEB 104", "Tenis Teknik ve Taktik Eğitim", 5, t("10:30"), t("12:29"), "KORT"),
            slots.single { it.code == "BEB 104" },
        )
        assertEquals(2, slots.count { it.code == "MAK 303" })
    }

    @Test
    fun pastedText_withoutHintsAsksForTheSection() {
        val f = KeplerReader.fromText("MAK 335, MAK 309\nITB 220\nBEB 999\nXYZ 101\nMAK 4901")
        val r = ScheduleMatcher.match(f, lookup)
        assertEquals(listOf("ITB 220", "MAK 309", "MAK 335"), r.courses.map { it.code })
        // MAK 309'un iki şubesi aynı saatte: tek seçenek, ama derslik belli değil
        val vib = r.courses.single { it.code == "MAK 309" }
        assertEquals(MatchVia.SINGLE, vib.via)
        assertEquals("", ScheduleMatcher.placeOf(vib, vib.option!!.meetings.single(), f.words))
        // ITB 220'nin iki farklı saati var: kullanıcı seçmeli
        val itb = r.courses.single { it.code == "ITB 220" }
        assertEquals(MatchVia.CHOICE, itb.via)
        assertNull(itb.chosen)
        assertEquals(2, itb.options.size)
        assertTrue(ScheduleMatcher.toSlots(listOf(itb), f.words).isEmpty())
        // BEB 999 açılmamış (yanlış okunmuş olabilir); XYZ diye bir branş yok, sessizce atlanır
        assertEquals(listOf("BEB 999"), r.notFound)
        assertEquals(listOf("MAK 4901"), r.noSchedule)
    }

    @Test
    fun pastedText_dayAndTimeOnFollowingLines() {
        val f = KeplerReader.fromText("BEB 104\nTenis Teknik ve Taktik Eğitim\nPerşembe\n15:30/17:29\nITB 220\nSalı 10:30/13:29")
        val r = ScheduleMatcher.match(f, lookup)
        assertEquals(t("15:30"), r.courses.single { it.code == "BEB 104" }.option!!.meetings.single().start)
        assertEquals(t("10:30"), r.courses.single { it.code == "ITB 220" }.option!!.meetings.single().start)
    }

    @Test
    fun crnAloneAddsTheCourse() {
        // Kod yanlış okunmuş ("MAK 33S") ama aynı branştan bir kod ve CRN okunmuş
        val f = KeplerReader.fromText("MAK 303\nMAK 33S 14893")
        val r = ScheduleMatcher.match(f, lookup)
        assertEquals(listOf("MAK 303", "MAK 335"), r.courses.map { it.code })
        assertEquals(MatchVia.CRN, r.courses.single { it.code == "MAK 335" }.via)
    }

    @Test
    fun roomInPhotoBreaksTie() {
        val f = KeplerReader.fromText("MAK 305 MKB D353")
        val m = ScheduleMatcher.match(f, lookup).courses.single()
        assertEquals(MatchVia.SINGLE, m.via)
        assertEquals("MKB D353", ScheduleMatcher.placeOf(m, m.option!!.meetings.first(), f.words))
    }
}
