package com.deveciahmet.devamsizlik

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class ImportTest {
    private fun t(s: String) = LocalTime.parse(s)

    /** Kullanıcının OBS ekran görüntüsünden bir yapay zekânın üreteceği cevap. */
    private val obsAnswer = """
        [{"code":"MAK 335","name":"","day":1,"start":"10:30","end":"13:29"},
         {"code":"MAK 303","name":"","day":1,"start":"15:30","end":"17:29"},
         {"code":"MAK 309","name":"","day":2,"start":"10:30","end":"13:29"},
         {"code":"ITB 220","name":"","day":2,"start":"14:30","end":"17:29"},
         {"code":"MAK 305","name":"","day":3,"start":"08:30","end":"10:29"},
         {"code":"MAK 303","name":"","day":4,"start":"08:30","end":"10:29"},
         {"code":"MAK 305","name":"","day":4,"start":"10:30","end":"12:29"},
         {"code":"MAK 317","name":"","day":4,"start":"12:30","end":"14:29"},
         {"code":"MAK 304E","name":"","day":4,"start":"15:30","end":"17:29"},
         {"code":"BEB 104","name":"","day":5,"start":"10:30","end":"12:29"},
         {"code":"MAK 118","name":"","day":5,"start":"14:00","end":"16:59"}]
    """.trimIndent()

    @Test
    fun parsesObsSchedule() {
        val r = ScheduleImport.parse(obsAnswer)
        assertNull(r.error)
        assertTrue(r.warnings.isEmpty())
        assertEquals(11, r.entries.size)
        assertEquals(ImportedSlot("MAK 335", "", 1, t("10:30"), t("13:29")), r.entries.first())
        assertEquals(ImportedSlot("MAK 118", "", 5, t("14:00"), t("16:59")), r.entries.last())
        // Programdaki varsayılan saatlerle birebir aynı olmalı
        val expected = Repo.defaultSlots.map { listOf(it.code, it.day, it.start, it.end) }.toSet()
        assertEquals(expected, r.entries.map { listOf(it.code, it.day, it.start, it.end) }.toSet())
    }

    @Test
    fun toleratesChatterAndCodeFences() {
        val text = "Tabii! İşte programın:\n```json\n" +
            "[{\"code\":\"mak335\",\"name\":\"Makine Dinamiği\",\"day\":\"Pazartesi\",\"start\":\"10.30\",\"end\":\"13:29\"}]\n" +
            "```\nBaşka bir şey ister misin? [Not: saatler OBS'den]"
        val r = ScheduleImport.parse(text)
        assertNull(r.error)
        assertEquals(listOf(ImportedSlot("MAK 335", "Makine Dinamiği", 1, t("10:30"), t("13:29"))), r.entries)
    }

    @Test
    fun acceptsTurkishKeysDayNamesAndTimeRanges() {
        val text = """
            {"dersler": [
              {"Kod": "BEB 104", "Gün": "Cuma", "Saat": "10:30 - 12:29"},
              {"ders_kodu": "MAK 309", "gun": "Salı", "başlangıç": "10:30", "bitiş": "13:29"},
              {"code": "X 1", "day": "Cumartesi", "start": "9:00", "end": "10:00"},
              {"code": "Y 2", "day": "Pazar", "start": "0900", "end": "1000"}
            ]}
        """.trimIndent()
        val r = ScheduleImport.parse(text)
        assertNull(r.error)
        assertEquals(4, r.entries.size)
        assertEquals(setOf(5, 2, 6, 7), r.entries.map { it.day }.toSet())
        assertEquals(t("12:29"), r.entries.first { it.code == "BEB 104" }.end)
        assertEquals(t("09:00"), r.entries.first { it.code == "Y 2" }.start)
    }

    @Test
    fun reportsBadEntriesAndDuplicates() {
        val text = """
            [{"code":"MAK 335","day":1,"start":"10:30","end":"13:29"},
             {"code":"MAK 335","day":1,"start":"10:30","end":"13:29"},
             {"code":"MAK 999","day":9,"start":"10:30","end":"13:29"},
             {"code":"MAK 998","day":1,"start":"25:00","end":"26:00"},
             {"code":"MAK 997","day":1,"start":"14:00","end":"13:00"},
             {"day":1,"start":"10:00","end":"11:00"}]
        """.trimIndent()
        val r = ScheduleImport.parse(text)
        assertNull(r.error)
        assertEquals(1, r.entries.size)
        assertEquals(5, r.warnings.size)
    }

    @Test
    fun errorWhenNoJson() {
        val r = ScheduleImport.parse("Üzgünüm, fotoğrafı okuyamadım.")
        assertNotNull(r.error)
        assertTrue(r.entries.isEmpty())
        assertNotNull(ScheduleImport.parse("   ").error)
    }

    @Test
    fun smartQuotesFromMessagingApps() {
        val r = ScheduleImport.parse("[{“code”:“MAK 317”,“day”:4,“start”:“12:30”,“end”:“14:29”}]")
        assertNull(r.error)
        assertEquals("MAK 317", r.entries.single().code)
    }

    @Test
    fun dayAndTimeParsers() {
        assertEquals(1, ScheduleImport.parseDay("Pazartesi"))
        assertEquals(7, ScheduleImport.parseDay("PAZAR"))
        assertEquals(6, ScheduleImport.parseDay("cumartesi"))
        assertEquals(5, ScheduleImport.parseDay("Cuma"))
        assertEquals(3, ScheduleImport.parseDay("Çarşamba"))
        assertEquals(4, ScheduleImport.parseDay("Per"))
        assertEquals(2, ScheduleImport.parseDay("Tuesday"))
        assertEquals(4, ScheduleImport.parseDay("4"))
        assertNull(ScheduleImport.parseDay("0"))
        assertNull(ScheduleImport.parseDay("yarın"))
        assertEquals(t("08:30"), ScheduleImport.parseTime("8:30"))
        assertEquals(t("13:29"), ScheduleImport.parseTime("13:29:00"))
        assertNull(ScheduleImport.parseTime("24:00"))
        assertNull(ScheduleImport.parseTime("öğlen"))
    }
}
