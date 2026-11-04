package com.deveciahmet.devamsizlik

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class CoreTest {
    private fun t(s: String) = LocalTime.parse(s)
    private val start = LocalDate.of(2026, 9, 28)
    private val slots = listOf(
        Slot("mak335", "MAK 335", 1, t("10:30"), t("13:29")),
        Slot("mak303-pzt", "MAK 303", 1, t("15:30"), t("17:29")),
        Slot("mak303-per", "MAK 303", 4, t("08:30"), t("10:29")),
    )

    @Test
    fun weekNumber_isSixOnNovember4() {
        assertEquals(6, weekNumber(start, LocalDate.of(2026, 11, 4)))
        assertEquals(1, weekNumber(start, start))
        assertEquals(0, weekNumber(start, start.minusDays(1)))
        assertEquals(13, weekNumber(start, start.plusWeeks(12).plusDays(6)))
    }

    @Test
    fun pollTimes_matchesSpec() {
        // 2 saatlik derste 2 yoklama: başlangıçtan 1 saat sonra ve ders bitiminde
        assertEquals(listOf(t("16:30"), t("17:29")), pollTimes(t("15:30"), t("17:29"), 2))
        assertEquals(listOf(t("11:30"), t("12:30"), t("13:29")), pollTimes(t("10:30"), t("13:29"), 3))
        assertEquals(listOf(t("13:29")), pollTimes(t("10:30"), t("13:29"), 1))
        // Ders kısa: eşit aralıklarla
        assertEquals(listOf(t("08:59"), t("09:29")), pollTimes(t("08:30"), t("09:29"), 2))
    }

    @Test
    fun sessions_coverWholeSemester() {
        val s = generateSessions(start, 13, slots)
        assertEquals(39, s.size)
        assertEquals(LocalDateTime.of(2026, 9, 28, 10, 30), s.first().start)
        assertEquals(13, s.last().week)
        // Sıralı mı
        assertEquals(s.sortedBy { it.start }, s)
    }

    @Test
    fun pollKeys_firstPollKeepsOldKey() {
        val s = generateSessions(start, 1, slots.take(1)).first()
        assertEquals("mak335|2026-09-28", pollKey(s.key, 1))
        assertEquals("mak335|2026-09-28|2", pollKey(s.key, 2))
    }

    @Test
    fun stats_defaultPercent() {
        val sessions = generateSessions(start, 13, slots)
        val polls = buildPolls(sessions, emptyMap())
        val now = LocalDateTime.of(2026, 11, 4, 12, 0)
        val absent = polls.filter { it.code == "MAK 335" }.take(2).map { it.key }.toSet()
        val st = computeStats(polls, { if (it in absent) Status.ABSENT else null }, emptyMap(), 70, now)
        val mak335 = st.first { it.code == "MAK 335" }
        assertEquals(13, mak335.total)
        assertEquals(3, mak335.allowed) // ⌊13 × 0.3⌋
        assertEquals(2, mak335.absent)
        assertEquals(1, mak335.remaining)
        val mak303 = st.first { it.code == "MAK 303" }
        assertEquals(26, mak303.total)
        assertEquals(7, mak303.allowed) // ⌊26 × 0.3⌋
        // 6. hafta Çarşamba öğlen: MAK 335'in 6 dersi geçti, 2'si devamsız → 4 işaretsiz
        assertEquals(4, mak335.unanswered)
        assertEquals(7, mak335.upcoming)
    }

    @Test
    fun stats_customModesAndManualTotal() {
        val sessions = generateSessions(start, 13, slots)
        val now = LocalDateTime.of(2026, 11, 4, 12, 0)
        val cfgs = mapOf(
            "MAK 335" to CourseConfig(totalSessions = 12, mode = LimitMode.COUNT, allowedCount = 4),
            "MAK 303" to CourseConfig(mode = LimitMode.PERCENT, requiredPercent = 80, polls = 2),
        )
        val polls = buildPolls(sessions, cfgs)
        val st = computeStats(polls, { null }, cfgs, 70, now)
        val mak335 = st.first { it.code == "MAK 335" }
        assertEquals(12, mak335.total)
        assertTrue(mak335.manualTotal)
        assertEquals(4, mak335.allowed)
        val mak303 = st.first { it.code == "MAK 303" }
        assertEquals(2, mak303.pollsPerSession)
        assertEquals(26, mak303.sessionCount)
        assertEquals(52, mak303.total) // 26 ders × 2 yoklama
        assertEquals(10, mak303.allowed) // ⌊52 × 0.2⌋
        assertEquals("yoklama", mak303.unit)
    }

    @Test
    fun stats_cancelledReducesTotalOnlyWhenAutomatic() {
        val sessions = generateSessions(start, 13, slots.take(1))
        val polls = buildPolls(sessions, emptyMap())
        val cancelled = setOf(polls[0].key)
        val now = LocalDateTime.of(2026, 11, 4, 12, 0)
        val auto = computeStats(polls, { if (it in cancelled) Status.CANCELLED else null }, emptyMap(), 70, now).first()
        assertEquals(12, auto.total)
        assertEquals(3, auto.allowed)
        val manualCfg = mapOf("MAK 335" to CourseConfig(totalSessions = 13))
        val manual = computeStats(polls, { if (it in cancelled) Status.CANCELLED else null }, manualCfg, 70, now).first()
        assertEquals(13, manual.total)
    }

    @Test
    fun events_askRemindPreclassWeekly() {
        val sessions = generateSessions(start, 13, slots.take(1))
        val polls = buildPolls(sessions, emptyMap())
        val answered = polls[0].key
        val ns = NotifySettings(reminderMinutes = 30, weeklyEnabled = true, weeklyDay = 1, weeklyTime = t("08:00"),
            warnEnabled = true, warnThreshold = 1, preclassMinutes = 60)
        val ev = computeEvents(polls, { if (it == answered) Status.ATTENDED else null }, setOf("MAK 335"), ns, start, 13)
        assertEquals(12, ev.count { it.type == EventType.ASK })
        assertEquals(12, ev.count { it.type == EventType.REMIND })
        assertEquals(13, ev.count { it.type == EventType.PRECLASS })
        assertEquals(14, ev.count { it.type == EventType.WEEKLY })
        assertFalse(ev.any { it.key == answered && it.type != EventType.PRECLASS })
        val ask = ev.first { it.type == EventType.ASK }
        assertEquals(LocalDateTime.of(2026, 10, 5, 13, 29), ask.time)
        assertEquals(LocalDateTime.of(2026, 10, 5, 13, 59),
            ev.first { it.type == EventType.REMIND && it.key == ask.key }.time)
        assertEquals(LocalDateTime.of(2026, 9, 28, 9, 30), ev.first { it.type == EventType.PRECLASS }.time)
    }

    @Test
    fun events_disabledFeaturesProduceNothing() {
        val polls = buildPolls(generateSessions(start, 13, slots), emptyMap())
        val ns = NotifySettings(reminderMinutes = 0, weeklyEnabled = false, warnEnabled = false)
        val ev = computeEvents(polls, { null }, setOf("MAK 335"), ns, start, 13)
        assertTrue(ev.all { it.type == EventType.ASK })
        assertEquals(polls.size, ev.size)
    }

    @Test
    fun events_multiPollAsksEachHour() {
        val cfgs = mapOf("MAK 303" to CourseConfig(polls = 2))
        val polls = buildPolls(generateSessions(start, 1, slots), cfgs)
        val ev = computeEvents(polls, { null }, emptySet(), NotifySettings(reminderMinutes = 0, weeklyEnabled = false), start, 1)
        val times = ev.filter { it.type == EventType.ASK }.map { it.time.toLocalTime() }
        assertEquals(listOf(t("13:29"), t("16:30"), t("17:29"), t("09:30"), t("10:29")), times)
    }

    @Test
    fun summary_countsLastSevenDays() {
        val polls = buildPolls(generateSessions(start, 13, slots), emptyMap())
        val to = LocalDateTime.of(2026, 10, 5, 8, 0) // 2. hafta Pazartesi sabahı → 1. haftayı özetler
        val absentKey = polls.first { it.code == "MAK 303" }.key
        val sum = summarize(polls, { if (it == absentKey) Status.ABSENT else Status.ATTENDED }, to.minusDays(7), to)
        assertEquals(3, sum.total)
        assertEquals(2, sum.attended)
        assertEquals(1, sum.absent)
        assertEquals(mapOf("MAK 303" to 1), sum.absentByCourse)
    }

    @Test
    fun normalizeCode_onlyTouchesCodes() {
        assertEquals("MAK 335", normalizeCode("mak335"))
        assertEquals("MAK 304E", normalizeCode("  mak   304e "))
        assertEquals("ITB 220", normalizeCode("ITB 220"))
        assertEquals("Fizik Laboratuvarı", normalizeCode(" Fizik   Laboratuvarı "))
    }
}
