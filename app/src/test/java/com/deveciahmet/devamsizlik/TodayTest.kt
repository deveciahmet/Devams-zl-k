package com.deveciahmet.devamsizlik

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Ana ekrandaki "Bugün" kartı ve widget içeriği (varsayılan program, 6. hafta). */
class TodayTest {
    private val start = LocalDate.of(2026, 9, 28)

    private fun snap(
        now: String = "2026-11-04T12:00",
        explicit: Map<String, Status> = emptyMap(),
        exams: List<Exam> = emptyList(),
        holidays: Set<LocalDate> = emptySet(),
        configs: Map<String, CourseConfig> = emptyMap(),
        slots: List<Slot> = Repo.defaultSlots,
    ) = buildSnapshot(LocalDateTime.parse(now), start, 13, 70, slots, configs, explicit, NotifySettings(),
        exams, holidays)

    private fun keyOf(code: String, date: String) =
        Repo.defaultSlots.first { it.code == code && it.day == LocalDate.parse(date).dayOfWeek.value }.id + "|" + date

    @Test
    fun nextClass_skipsFinishedAndHolidayClasses() {
        val s = snap()
        val next = currentOrNextClass(s)!!
        assertFalse(next.ongoing)
        assertEquals("MAK 303", next.session.slot.code)
        assertEquals(LocalDate.of(2026, 11, 5), next.session.date)
        assertEquals("Yarın 08:30–10:29", classDetail(s, next))

        val during = currentOrNextClass(snap(now = "2026-11-04T09:00"))!!
        assertTrue(during.ongoing)
        assertEquals("MAK 305", during.session.slot.code)
        assertEquals("10:29'a kadar", classDetail(snap(now = "2026-11-04T09:00"), during))

        // Perşembe tatil: sıradaki ders Cuma
        val afterHoliday = currentOrNextClass(snap(holidays = setOf(LocalDate.of(2026, 11, 5))))!!
        assertEquals("BEB 104", afterHoliday.session.slot.code)
        assertNull(currentOrNextClass(snap(now = "2027-01-04T09:00")))
    }

    @Test
    fun sessionStates() {
        val s0 = snap()
        val wed = todaysClasses(s0).single()
        assertEquals("MAK 305", wed.slot.code)
        assertEquals(SessionState.UNANSWERED, sessionState(s0, wed))
        assertEquals(SessionState.ATTENDED, sessionState(snap(explicit = mapOf(wed.key to Status.ATTENDED)), wed))
        assertEquals(SessionState.ONGOING, sessionState(snap(now = "2026-11-04T09:00"), wed))
        assertEquals(SessionState.UPCOMING, sessionState(snap(now = "2026-11-04T07:00"), wed))
        assertEquals(SessionState.CANCELLED, sessionState(snap(holidays = setOf(wed.date)), wed))
        // İki yoklamanın biri girildi biri girilmedi
        val cfg = mapOf("MAK 305" to CourseConfig(polls = 2))
        val mixed = snap(configs = cfg, explicit = mapOf(wed.key to Status.ATTENDED, "${wed.key}|2" to Status.ABSENT))
        assertEquals(SessionState.PARTIAL, sessionState(mixed, wed))
    }

    @Test
    fun alerts_mostImportantFirst() {
        val explicit = mapOf(
            keyOf("MAK 335", "2026-09-28") to Status.ABSENT,
            keyOf("MAK 335", "2026-10-05") to Status.ABSENT,
            keyOf("MAK 335", "2026-10-12") to Status.ABSENT,
            keyOf("MAK 309", "2026-09-29") to Status.ABSENT,
            keyOf("MAK 309", "2026-10-06") to Status.ABSENT,
        )
        val a = alerts(snap(explicit = explicit))
        assertEquals(Alert(Level.DANGER, "MAK 335: hakkın bitti", "MAK 335", short = "Hakkın bitti · MAK 335"), a[0])
        assertEquals(Alert(Level.WARN, "MAK 309: son 1 hak", "MAK 309", short = "Son 1 hak · MAK 309"), a[1])
        assertTrue(a.last().unanswered)
        assertEquals(3, a.size)
    }

    @Test
    fun widget_nextClassExamCountdownAndMessages() {
        val exam = Exam("e1", "MAK 303", "Vize", LocalDate.of(2026, 11, 17), LocalTime.of(13, 30))
        val explicit = mapOf(
            keyOf("MAK 303", "2026-09-28") to Status.ABSENT,
            keyOf("MAK 303", "2026-10-01") to Status.ABSENT,
            keyOf("MAK 303", "2026-10-05") to Status.ABSENT,
            keyOf("MAK 303", "2026-10-08") to Status.ABSENT,
            keyOf("MAK 303", "2026-10-12") to Status.ABSENT,
            keyOf("MAK 303", "2026-10-15") to Status.ABSENT,
            keyOf("MAK 309", "2026-09-29") to Status.ABSENT,
            keyOf("MAK 309", "2026-10-06") to Status.ABSENT,
            keyOf("MAK 309", "2026-10-13") to Status.ABSENT,
        )
        val d = widgetData(snap(explicit = explicit, exams = listOf(exam), configs = mapOf("MAK 303" to CourseConfig(name = "Isı Geçişi"))))
        assertEquals("Sıradaki ders", d.label)
        assertEquals("Isı Geçişi", d.title)
        assertEquals("Yarın 08:30–10:29", d.detail)
        assertEquals("13", d.examBig)
        assertEquals("gün kaldı", d.examSmall)
        assertEquals("Isı Geçişi · Vize", d.examTitle)
        // MAK 309'un hakkı bitti ama sıradaki ders Isı Geçişi: onun uyarısı (son 1 hak) önce gelir
        assertEquals(listOf("Isı Geçişi: son 1 hak", "MAK 309: hakkın bitti"), d.messages.map { it.text })
    }

    @Test
    fun widget_emptyAndFinishedStates() {
        val empty = widgetData(snap(slots = emptyList()))
        assertEquals("Program boş", empty.title)
        assertNull(empty.examBig)
        assertEquals("Sınav ekle", empty.examSmall)
        assertTrue(empty.messages.isEmpty())

        val over = widgetData(snap(now = "2027-01-04T09:00"))
        assertEquals("Dönem bitti", over.label)

        val tomorrow = widgetData(snap(exams = listOf(Exam("x", "", "İngilizce", LocalDate.of(2026, 11, 5), LocalTime.of(9, 0)))))
        assertEquals("Yarın", tomorrow.examBig)
        assertEquals("09:00", tomorrow.examSmall)
        assertEquals("İngilizce", tomorrow.examTitle)
    }

    @Test
    fun widget_refreshTimes() {
        // Öğlen: bir sonraki değişiklik gece yarısı (yarının sıradaki dersi ve kalan gün sayısı)
        assertEquals(LocalDateTime.parse("2026-11-05T00:01"), nextWidgetRefresh(snap()))
        // Ders sürerken: ders bitince
        assertEquals(LocalDateTime.parse("2026-11-04T10:29"), nextWidgetRefresh(snap(now = "2026-11-04T09:00")))
        // Sabah: ders başlayınca
        assertEquals(LocalDateTime.parse("2026-11-04T08:30"), nextWidgetRefresh(snap(now = "2026-11-04T07:00")))
    }
}
