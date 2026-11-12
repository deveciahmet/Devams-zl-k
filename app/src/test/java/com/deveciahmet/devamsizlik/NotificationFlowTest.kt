package com.deveciahmet.devamsizlik

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Bildirim akışının uçtan uca testi (Robolectric: Android'i JVM'de taklit eder).
 * Varsayılan program ve dönem: 1. hafta 28 Eylül 2026, 6. hafta 2–8 Kasım 2026.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationFlowTest {
    private lateinit var ctx: Context
    private val zone: ZoneId = ZoneId.systemDefault()

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        shadowOf(ctx as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun tearDown() {
        AppClock.clock = Clock.systemDefaultZone()
    }

    private fun at(s: String) {
        AppClock.clock = Clock.fixed(LocalDateTime.parse(s).atZone(zone).toInstant(), zone)
    }

    private fun fireAlarm(s: String) {
        at(s)
        Scheduler.onAlarm(ctx)
    }

    private val nm get() = ctx.getSystemService(NotificationManager::class.java)
    private fun posted(): List<Notification> = shadowOf(nm).allNotifications
    private fun title(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private fun big(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()

    private fun tap(n: Notification, label: String) {
        n.actions.first { it.title.toString() == label }.actionIntent.send()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun millis(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun classEnd_asksQuestion_andYesRecordsWithoutOpeningApp() {
        at("2026-11-02T13:00:00")
        Scheduler.reschedule(ctx)
        fireAlarm("2026-11-02T13:29:05")

        val n = posted().single()
        assertEquals("MAK 335 dersine girdin mi?", title(n))
        assertEquals(listOf("Evet", "Hayır", "Ders yapılmadı"), n.actions.map { it.title.toString() })

        tap(n, "Evet")
        assertEquals(Status.ATTENDED, Repo.status(ctx, "mak335-pzt|2026-11-02"))
        assertTrue("cevaplanınca bildirim kapanmalı", posted().isEmpty())
    }

    @Test
    fun noAnswer_isAskedAgainAfterReminderDelay() {
        at("2026-11-02T13:00:00")
        Scheduler.reschedule(ctx)
        fireAlarm("2026-11-02T13:29:05")
        nm.cancelAll() // kullanıcı bildirimi kaydırıp kapattı

        fireAlarm("2026-11-02T13:59:05")
        assertEquals("Hatırlatma: MAK 335 dersine girdin mi?", title(posted().single()))

        // Sadece bir kez hatırlatır
        nm.cancelAll()
        fireAlarm("2026-11-02T14:29:05")
        assertTrue(posted().isEmpty())
    }

    @Test
    fun answering_cancelsTheReminder_andAlarmMovesToNextClass() {
        at("2026-11-02T13:00:00")
        Scheduler.reschedule(ctx)
        fireAlarm("2026-11-02T13:29:05")
        val am = shadowOf(ctx.getSystemService(AlarmManager::class.java))
        // Cevap yokken sıradaki olay 30 dk sonraki hatırlatma
        assertEquals(millis("2026-11-02T13:59:00"), am.scheduledAlarms.single().getTriggerAtMs())

        tap(posted().single(), "Hayır")
        assertEquals(Status.ABSENT, Repo.status(ctx, "mak335-pzt|2026-11-02"))
        // Cevap gelince hatırlatma iptal, sıradaki olay MAK 303'ün bitişi
        assertEquals(millis("2026-11-02T17:29:00"), am.scheduledAlarms.single().getTriggerAtMs())
    }

    @Test
    fun twoPolls_askEachHourSeparately() {
        Repo.saveCourse(ctx, "MAK 303", "MAK 303", CourseConfig(polls = 2))
        at("2026-11-02T16:00:00")
        Scheduler.reschedule(ctx, resetCursor = true)

        fireAlarm("2026-11-02T16:30:05") // başlangıçtan 1 saat sonra
        val first = posted().single()
        assertEquals("MAK 303 dersinin 1. saatine girdin mi?", title(first))
        tap(first, "Hayır")
        assertEquals(Status.ABSENT, Repo.status(ctx, "mak303-pzt|2026-11-02"))

        fireAlarm("2026-11-02T17:29:05") // ders bitiminde
        val second = posted().single()
        assertEquals("MAK 303 dersinin 2. saatine girdin mi?", title(second))
        tap(second, "Evet")
        assertEquals(Status.ATTENDED, Repo.status(ctx, "mak303-pzt|2026-11-02|2"))

        val st = Repo.snapshot(ctx, LocalDateTime.parse("2026-11-02T18:00:00")).stat("MAK 303")!!
        assertEquals(52, st.total)
        assertEquals(1, st.absent)
        assertEquals(1, st.attended)
    }

    @Test
    fun lastRight_triggersEarlyWarning_andPreClassReminder() {
        // MAK 335: 13 ders, 3 hak; 1 devamsızlık zaten var
        Repo.setStatus(ctx, "mak335-pzt|2026-09-28", Status.ABSENT)
        at("2026-11-02T13:00:00")
        Scheduler.reschedule(ctx)
        fireAlarm("2026-11-02T13:29:05")
        tap(posted().single(), "Hayır")

        val warn = posted().single()
        assertEquals("⚠ MAK 335: son 1 devamsızlık hakkın kaldı", title(warn))
        nm.cancelAll()

        // Haftaya Pazartesi, dersten 1 saat önce "kaçırma" hatırlatması (+ haftalık özet)
        fireAlarm("2026-11-09T09:30:05")
        val pre = posted().single { title(it)?.contains("yaklaşıyor") == true }
        assertEquals("MAK 335 dersin yaklaşıyor (10:30–13:29)", title(pre))
        assertTrue(big(pre).contains("Son 1 devamsızlık hakkın kaldı"))
    }

    @Test
    fun exhaustedRight_warnsThatNextAbsenceFails() {
        Repo.setStatus(ctx, "mak335-pzt|2026-09-28", Status.ABSENT)
        Repo.setStatus(ctx, "mak335-pzt|2026-10-05", Status.ABSENT)
        at("2026-11-02T13:00:00")
        Scheduler.reschedule(ctx)
        fireAlarm("2026-11-02T13:29:05")
        tap(posted().single(), "Hayır")
        assertEquals("⛔ MAK 335: devamsızlık hakkın bitti!", title(posted().single()))
    }

    @Test
    fun weeklySummary_onMondayMorning() {
        Repo.setStatus(ctx, "mak335-pzt|2026-11-02", Status.ATTENDED)
        Repo.setStatus(ctx, "mak303-pzt|2026-11-02", Status.ABSENT)
        at("2026-11-09T07:00:00")
        Scheduler.reschedule(ctx)
        fireAlarm("2026-11-09T08:00:05")

        val n = posted().single()
        assertEquals(Notifier.CH_SUMMARY, n.channelId)
        assertEquals("Haftalık özet • 6. hafta", title(n))
        assertTrue(big(n), big(n).contains("Girdin: 1 • Girmedin: 1"))
        assertTrue(big(n), big(n).contains("Devamsızlık: MAK 303 (1)"))
    }

    @Test
    fun disabledReminderAndWeekly_sendNothingExtra() {
        Repo.setNotifySettings(ctx, NotifySettings(reminderMinutes = 0, weeklyEnabled = false))
        at("2026-11-02T13:00:00")
        Scheduler.reschedule(ctx)
        fireAlarm("2026-11-02T13:29:05")
        nm.cancelAll()
        fireAlarm("2026-11-02T13:59:05")
        assertTrue(posted().isEmpty())
    }

    @Test
    fun bootReceiver_reschedules() {
        at("2026-11-04T09:00:00")
        BootReceiver().onReceive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED))
        val am = shadowOf(ctx.getSystemService(AlarmManager::class.java))
        // Çarşamba MAK 305 08:30–10:29
        assertEquals(millis("2026-11-04T10:29:00"), am.scheduledAlarms.single().getTriggerAtMs())
    }

    @Test
    fun importReplace_keepsHistoryOfMatchingClasses() {
        Repo.setStatus(ctx, "mak335-pzt|2026-09-28", Status.ABSENT)
        val r = ScheduleImport.parse(
            """[{"code":"MAK 335","name":"Makine Dinamiği","day":1,"start":"10:30","end":"13:29"},
                {"code":"FIZ 101","day":3,"start":"13:30","end":"15:29"}]"""
        )
        assertEquals(2, Repo.importSlots(ctx, r.entries, replace = true))
        val snap = Repo.snapshot(ctx, LocalDateTime.parse("2026-11-04T12:00:00"))
        assertEquals(listOf("FIZ 101", "MAK 335"), snap.courseCodes)
        val mak = snap.stat("MAK 335")!!
        assertEquals(1, mak.absent)
        assertEquals("Makine Dinamiği", mak.name)
    }

    @Test
    fun importAppend_doesNotDuplicate() {
        val r = ScheduleImport.parse("""[{"code":"MAK 335","day":1,"start":"10:30","end":"13:29"}]""")
        Repo.importSlots(ctx, r.entries, replace = false)
        assertEquals(11, Repo.slots(ctx).size)
    }

    @Test
    fun movingClassToAnotherDay_movesItsHistory() {
        Repo.setStatus(ctx, "mak335-pzt|2026-09-28", Status.ABSENT)
        Repo.setStatus(ctx, "mak335-pzt|2026-09-28|2", Status.ATTENDED)
        val old = Repo.slots(ctx).first { it.id == "mak335-pzt" }
        Repo.updateSlot(ctx, old, old.copy(day = 2))
        assertNull(Repo.status(ctx, "mak335-pzt|2026-09-28"))
        assertEquals(Status.ABSENT, Repo.status(ctx, "mak335-pzt|2026-09-29"))
        assertEquals(Status.ATTENDED, Repo.status(ctx, "mak335-pzt|2026-09-29|2"))
    }

    @Test
    fun renamingCourse_keepsHistoryAndSettings() {
        Repo.setStatus(ctx, "mak335-pzt|2026-09-28", Status.ABSENT)
        val saved = Repo.saveCourse(ctx, "MAK 335", "mak 336",
            CourseConfig(name = "Dinamik", mode = LimitMode.COUNT, allowedCount = 2))
        assertEquals("MAK 336", saved)
        val snap = Repo.snapshot(ctx, LocalDateTime.parse("2026-11-04T12:00:00"))
        assertNull(snap.stat("MAK 335"))
        val st = snap.stat("MAK 336")!!
        assertEquals(1, st.absent)
        assertEquals(2, st.allowed)
        assertEquals("Dinamik", st.title)
    }

    @Test
    fun increasingPolls_copiesFirstAnswerToPastClasses() {
        Repo.setStatus(ctx, "mak303-pzt|2026-09-28", Status.ABSENT)
        Repo.setStatus(ctx, "mak303-per|2026-10-01", Status.ATTENDED)
        Repo.saveCourse(ctx, "MAK 303", "MAK 303", CourseConfig(polls = 2))
        Repo.fillNewPolls(ctx, "MAK 303", 1, LocalDateTime.parse("2026-11-04T12:00:00"))
        assertEquals(Status.ABSENT, Repo.status(ctx, "mak303-pzt|2026-09-28|2"))
        assertEquals(Status.ATTENDED, Repo.status(ctx, "mak303-per|2026-10-01|2"))
        assertNull(Repo.status(ctx, "mak303-pzt|2026-10-05|2"))
    }

    @Test
    fun oldVersionLimitSetting_isMigrated() {
        Repo.settings(ctx).edit().putInt("limit", 25).commit()
        assertEquals(75, Repo.requiredPercent(ctx))
        Repo.setRequiredPercent(ctx, 80)
        assertEquals(80, Repo.requiredPercent(ctx))
    }

    @Test
    fun testNotification_answerIsNotRecorded() {
        at("2026-11-02T14:00:00")
        val snap = Repo.snapshot(ctx)
        val poll = snap.polls.last { !it.time.isAfter(snap.now) }
        Notifier.askPoll(ctx, poll, snap.config(poll.code), reminder = false, test = true)
        val n = posted().single()
        assertEquals("Test: MAK 335 dersine girdin mi?", title(n))
        assertTrue(big(n).contains("kaydedilmez"))
        tap(n, "Hayır")
        assertNull(Repo.status(ctx, poll.key))
        assertTrue(Repo.statusMap(ctx).isEmpty())
        assertTrue("test bildirimi kapanmalı", posted().isEmpty())
    }

    @Test
    fun tappingQuestion_opensThatCoursesMarkingScreen() {
        at("2026-11-02T13:00:00")
        Scheduler.reschedule(ctx)
        fireAlarm("2026-11-02T13:29:05")
        val intent = shadowOf(posted().single().contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(
            OpenRequest(OpenRequest.COURSE, "MAK 335", "mak335-pzt|2026-11-02"),
            OpenRequest.from(intent),
        )
    }

    @Test
    fun examReminder_comesTheEveningBefore() {
        Repo.saveExam(ctx, Exam("e1", "MAK 335", "Vize", java.time.LocalDate.of(2026, 11, 17), java.time.LocalTime.of(13, 30), "MKB A501"))
        at("2026-11-16T19:00:00")
        Scheduler.reschedule(ctx)
        val am = shadowOf(ctx.getSystemService(AlarmManager::class.java))
        assertEquals(millis("2026-11-16T20:00:00"), am.scheduledAlarms.single().getTriggerAtMs())
        fireAlarm("2026-11-16T20:00:05")
        val n = posted().single()
        assertEquals(Notifier.CH_EXAM, n.channelId)
        assertEquals("Yarın sınavın var: MAK 335 · Vize", title(n))
        assertTrue(big(n), big(n).contains("13:30 • MKB A501"))
        assertEquals(OpenRequest(OpenRequest.EXAMS), OpenRequest.from(shadowOf(n.contentIntent).savedIntent))
    }

    @Test
    fun holiday_noQuestionsThatDay() {
        Repo.addHolidays(ctx, listOf(java.time.LocalDate.of(2026, 11, 5)))
        at("2026-11-05T07:00:00")
        Scheduler.reschedule(ctx)
        val am = shadowOf(ctx.getSystemService(AlarmManager::class.java))
        // Perşembe dersleri sorulmaz; sıradaki soru Cuma BEB 104'ün bitişi
        assertEquals(millis("2026-11-06T12:29:00"), am.scheduledAlarms.single().getTriggerAtMs())
    }

    @Test
    fun weeklySummary_listsUpcomingExams() {
        Repo.setStatus(ctx, "mak335-pzt|2026-11-02", Status.ATTENDED)
        Repo.saveExam(ctx, Exam("e1", "MAK 303", "Vize", java.time.LocalDate.of(2026, 11, 11), java.time.LocalTime.of(9, 30)))
        at("2026-11-09T07:00:00")
        Scheduler.reschedule(ctx)
        fireAlarm("2026-11-09T08:00:05")
        val n = posted().single()
        assertTrue(big(n), big(n).contains("📅 MAK 303 · Vize: Çarşamba 09:30"))
        assertEquals(OpenRequest(OpenRequest.FILL), OpenRequest.from(shadowOf(n.contentIntent).savedIntent))
    }
}
