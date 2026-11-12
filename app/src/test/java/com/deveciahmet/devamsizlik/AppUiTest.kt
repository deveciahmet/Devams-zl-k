package com.deveciahmet.devamsizlik

import android.app.DatePickerDialog
import android.content.Context
import android.content.DialogInterface
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.time.LocalDate

/**
 * Arayüz testleri. Gerçekçi metin ölçümü için yerel (NATIVE) grafik kullanılıyor. Not: Robolectric'te
 * "w400dp-h860dp" ekranında içinde metin kutusu olan pencereler (Dialog) hiç boşta kalmıyor; bu yüzden varsayılan
 * (küçük) ekran kullanılıyor ve listedeki öğelere dokunmadan önce kaydırılıyor.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AppUiTest {
    @get:Rule(order = 0)
    val clock = FixedClockRule("2026-11-04T12:00:00")

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun back() {
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    /** Alt çubuktaki sekme (aynı yazı ekranda başlık olarak da geçebilir). */
    private fun tab(name: String) {
        rule.onNode(hasText(name) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
        rule.waitForIdle()
    }

    private fun scrollTo(text: String, substring: Boolean = false) {
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = substring))
    }

    /** Arka planda çalışan bir işin sonucunu bekler; sonuç listede aşağıda kalabileceği için kaydırarak arar. */
    private fun waitForText(text: String, substring: Boolean = false) {
        rule.waitUntil(10_000) {
            ShadowLooper.idleMainLooper()
            rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() ||
                runCatching { scrollTo(text, substring) }.isSuccess
        }
    }

    /** Açık platform tarih seçicisinde tarihi seçip "Tamam"a basar. */
    private fun pickDate(date: LocalDate) {
        rule.waitForIdle()
        val d = ShadowDialog.getLatestDialog() as DatePickerDialog
        d.updateDate(date.year, date.monthValue - 1, date.dayOfMonth)
        d.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        rule.waitForIdle()
    }

    @Test
    fun home_showsToday_week_courses_andUnansweredCard() {
        rule.onNodeWithText("Hafta 6/13").assertExists()
        rule.onNodeWithText("2026-2027 Güz Dönemi").assertExists()
        // Bugün Çarşamba: tek ders MAK 305, cevaplanmamış
        rule.onNodeWithText("İşaretsiz").assertExists()
        // 5 tam hafta × 11 + bu hafta Pzt (2) + Sal (2) + Çar MAK 305 (1) = 60
        rule.onNodeWithText("60 yoklama işaretlenmemiş").assertExists()
        scrollTo("MAK 118")
        rule.onNodeWithText("MAK 118").assertExists()
    }

    @Test
    fun home_warnsWhenNotificationsAreOff() {
        rule.onNodeWithText("Bildirimler kapalı").assertDoesNotExist()
        shadowOf(ctx.getSystemService(android.app.NotificationManager::class.java)).setNotificationsEnabled(false)
        Repo.setStatus(ctx, "yenile", Status.ATTENDED) // ekranı yenilet
        rule.waitForIdle()
        rule.onNodeWithText("Bildirimler kapalı").assertExists()
    }

    @Test
    fun home_todaysClassOpensItsWeekInCourseScreen() {
        rule.onNodeWithText("İşaretsiz").performClick()
        rule.waitForIdle()
        // Ders ekranı bu dersin haftasına kaydırılmış olarak açılır
        rule.onNodeWithContentDescription("Ders ayarları").assertExists()
        rule.onNodeWithText("Hafta 6  (2 Kas – 8 Kas)  • bu hafta").assertExists()
        rule.onNode(hasText("Girdim") and hasAnyAncestor(hasTestTag("session_mak305-car|2026-11-04")))
            .performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(Status.ATTENDED, Repo.status(ctx, "mak305-car|2026-11-04"))
    }

    @Test
    fun fillScreen_bulkAttend_thenMarkOneAbsent() {
        rule.onNodeWithText("Doldur").performClick()
        rule.onNodeWithText("Geçmiş dersleri doldur").assertExists()
        rule.onNode(hasText("hepsini", substring = true) and hasClickAction()).performClick()
        rule.onNodeWithText("Evet").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("İşaretlenmemiş geçmiş ders yok 🎉").assertExists()
        assertEquals(0, Repo.snapshot(ctx).unansweredPast.size)

        // Tüm listeyi göster ve ilk dersi "Girmedim" yap
        rule.onNodeWithText("Sadece işaretlenmemişler").performClick()
        scrollTo("Girmedim")
        rule.onAllNodesWithText("Girmedim").onFirst().performClick()
        rule.waitForIdle()
        val st = Repo.snapshot(ctx).stat("MAK 335")!!
        assertEquals(1, st.absent)
        assertEquals(5, st.attended)

        back()
        rule.onNodeWithText("Hafta 6/13").assertExists()
    }

    @Test
    fun courseEdit_setsNamePollsAndLimit() {
        scrollTo("MAK 303")
        rule.onNodeWithText("MAK 303").performClick()
        rule.onNodeWithContentDescription("Ders ayarları").performClick()
        rule.onNodeWithText("Ders ayarları").assertExists()

        rule.onNode(hasSetTextAction() and hasText("Ders adı (isteğe bağlı)")).performTextInput("Makine Teorisi")
        rule.onNodeWithText("Gelmeme hakkı").performScrollTo().performClick()
        rule.onNode(hasSetTextAction() and hasText("Kaç derse gelmeme hakkın var?")).performScrollTo()
            .performTextReplacement("4")
        rule.onNodeWithText("2").performScrollTo().performClick()
        rule.onNodeWithText("Kaydet").performScrollTo().performClick()
        rule.waitForIdle()

        val cfg = Repo.courseConfigs(ctx)["MAK 303"]!!
        assertEquals("Makine Teorisi", cfg.name)
        assertEquals(2, cfg.polls)
        assertEquals(LimitMode.COUNT, cfg.mode)
        assertEquals(4, cfg.allowedCount)
        // Ders ekranına geri döndü, başlık artık ders adı
        rule.onAllNodesWithText("Makine Teorisi").onFirst().assertExists()
    }

    @Test
    fun courseEdit_removeCourseFromSchedule() {
        tab("Ayarlar")
        scrollTo("MAK 118", substring = true)
        rule.onNodeWithText("MAK 118", substring = true).performClick()
        rule.onNodeWithText("Dersi programdan kaldır").performScrollTo().performClick()
        rule.onNodeWithText("Kaldır").performClick()
        rule.waitForIdle()
        assertFalse("MAK 118" in Repo.snapshot(ctx).courseCodes)
        assertEquals(10, Repo.slots(ctx).size)
    }

    @Test
    fun settings_numberFieldsDoNotResetWhileTyping() {
        tab("Ayarlar")
        val weeks = rule.onNode(hasSetTextAction() and hasText("Toplam hafta"))
        weeks.performTextClearance()
        weeks.performTextInput("1")
        rule.waitForIdle()
        weeks.performTextInput("4")
        rule.waitForIdle()
        assertEquals(14, Repo.weeks(ctx))
        weeks.assert(hasText("14"))

        val req = rule.onNode(hasSetTextAction() and hasText("Devam şartı %"))
        req.performTextClearance()
        rule.waitForIdle()
        req.assert(hasText("")) // boşken kayıtlı değere geri dönmez
        req.performTextInput("8")
        rule.waitForIdle()
        req.performTextInput("5")
        rule.waitForIdle()
        assertEquals(85, Repo.requiredPercent(ctx))
        req.assert(hasText("85"))

        val week = rule.onNode(hasSetTextAction() and hasText("Şu anki hafta"))
        week.performTextClearance()
        week.performTextInput("1")
        rule.waitForIdle()
        week.performTextInput("0")
        rule.waitForIdle()
        assertEquals(10, Repo.snapshot(ctx).currentWeek)
        week.assert(hasText("10"))
    }

    @Test
    fun settings_changesNotifications() {
        tab("Ayarlar")
        scrollTo("1 saat")
        rule.onNodeWithText("1 saat").performClick()
        scrollTo("2 hak")
        rule.onNodeWithText("2 hak").performClick()
        scrollTo("Sınav hatırlatması")
        rule.onNodeWithText("Sınav hatırlatması").performClick()
        scrollTo("Sürüm", substring = true)
        rule.waitForIdle()
        val ns = Repo.notifySettings(ctx)
        assertEquals(60, ns.reminderMinutes)
        assertEquals(2, ns.warnThreshold)
        assertFalse(ns.examReminder)
    }

    @Test
    fun exams_addShowCountdownAndDelete() {
        tab("Sınavlar")
        rule.onNodeWithText("Henüz sınav eklemedin").assertExists()
        rule.onNodeWithContentDescription("Sınav ekle").performClick()
        // Ders seç: MAK 335
        rule.onNodeWithText("BEB 104").performClick()
        rule.onNodeWithText("MAK 335").performScrollTo().performClick()
        rule.onNodeWithText("Final").performClick()
        rule.onNodeWithText("Tarih", substring = true).performClick()
        pickDate(LocalDate.of(2026, 11, 17))
        rule.onNodeWithText("Sınava 13 gün kaldı.").assertExists()
        rule.onNodeWithText("Kaydet").performClick()
        rule.waitForIdle()

        val exam = Repo.exams(ctx).single()
        assertEquals("MAK 335", exam.code)
        assertEquals("Final", exam.title)
        assertEquals(LocalDate.of(2026, 11, 17), exam.date)
        rule.onNodeWithText("MAK 335 · Final").assertExists()
        rule.onNodeWithText("13").assertExists()

        // Ana ekranda sıradaki sınav
        tab("Dersler")
        rule.onNodeWithText("Sıradaki sınav").assertExists()

        tab("Sınavlar")
        rule.onNodeWithText("MAK 335 · Final").performClick()
        rule.onNodeWithText("Sil").performClick()
        rule.waitForIdle()
        assertTrue(Repo.exams(ctx).isEmpty())
    }

    @Test
    fun holidays_pickDayMarksAllItsClasses_andRemove() {
        tab("Ayarlar")
        rule.onNodeWithText("Tatil günleri").performClick()
        rule.onNodeWithText("Tatil günü ekle").performClick()
        pickDate(LocalDate.of(2026, 10, 29))
        rule.onNodeWithText("4 ders \"yapılmadı\" olarak işaretlenecek:").assertExists()
        rule.onNodeWithText("Tatil yap").performClick()
        rule.waitForIdle()

        assertEquals(setOf(LocalDate.of(2026, 10, 29)), Repo.holidays(ctx))
        val snap = Repo.snapshot(ctx)
        assertTrue(snap.polls.filter { it.session.date == LocalDate.of(2026, 10, 29) }.all { snap.status(it.key) == Status.CANCELLED })
        rule.onNodeWithText("29 Ekim Perşembe").assertExists()
        rule.onNodeWithText("4 ders yapılmadı sayılıyor").assertExists()

        rule.onNodeWithContentDescription("Tatili kaldır").performClick()
        rule.waitForIdle()
        assertTrue(Repo.holidays(ctx).isEmpty())
    }

    @Test
    fun keplerImport_typedCodes_namesComeFromItu_andAmbiguousSectionIsChosen() {
        tab("Ayarlar")
        scrollTo("Programı Kepler'den ekle")
        rule.onNodeWithText("Programı Kepler'den ekle").performClick()
        rule.onNodeWithText("Fotoğraf yerine ders kodlarını yaz").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("MAK 335\nITB 220\nBEB 104 Cuma 10:30-12:29")
        rule.onNodeWithText("Bul").performScrollTo().performClick()
        waitForText("Bulunan dersler (3)")

        scrollTo("Akışkanlar Mekaniği II")
        scrollTo("Tenis Teknik ve Taktik Eğitim")
        // ITB 220'nin iki farklı saati var: seçmeden kaydedilemez
        scrollTo("Hangi şubedesin?", substring = true)
        scrollTo("Programa kaydet (3 ders)")
        rule.onNodeWithText("Programa kaydet (3 ders)").assertIsNotEnabled()
        scrollTo("Sal 14:30–17:29")
        rule.onNodeWithText("Sal 14:30–17:29").performClick()
        scrollTo("Programa kaydet (3 ders)")
        rule.onNodeWithText("Programa kaydet (3 ders)").performClick()
        rule.waitForIdle()

        val snap = Repo.snapshot(ctx)
        assertEquals(listOf("BEB 104", "ITB 220", "MAK 335"), snap.courseCodes)
        assertEquals("Psikoloji", snap.title("ITB 220"))
        assertEquals("Akışkanlar Mekaniği II", snap.title("MAK 335"))
        assertEquals(java.time.LocalTime.of(14, 30), snap.slots.single { it.code == "ITB 220" }.start)
        assertEquals("MED B34", snap.slots.single { it.code == "ITB 220" }.place)
        assertEquals(java.time.LocalTime.of(10, 30), snap.slots.single { it.code == "BEB 104" }.start)
        assertEquals("2026-2027 Güz Dönemi", snap.termName)
        // Eski programda aynı gün/saatteki MAK 335'in geçmişi korunur
        assertEquals("mak335-pzt", snap.slots.single { it.code == "MAK 335" }.id)
    }

    @Test
    fun keplerImport_offlineShowsError() {
        clock.itu.offline = true
        tab("Ayarlar")
        scrollTo("Programı Kepler'den ekle")
        rule.onNodeWithText("Programı Kepler'den ekle").performClick()
        rule.onNodeWithText("Fotoğraf yerine ders kodlarını yaz").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("MAK 335")
        rule.onNodeWithText("Bul").performScrollTo().performClick()
        waitForText("İnternet bağlantını", substring = true)
        assertEquals(11, Repo.slots(ctx).size)
    }

    @Test
    fun newTerm_archivesAndUndo_thenSummaryOfOldTerm() {
        Repo.setStatus(ctx, "mak335-pzt|2026-09-28", Status.ABSENT)
        tab("Ayarlar")
        scrollTo("Yeni dönem başlat")
        rule.onNodeWithText("Yeni dönem başlat").performClick()
        rule.onNodeWithText("Başlat").performClick()
        rule.waitForIdle()
        assertTrue(Repo.slots(ctx).isEmpty())
        assertEquals(1, Archive.list(ctx).size)

        // Geri al
        waitForText("Geri al")
        rule.onNodeWithText("Geri al").performClick()
        rule.waitForIdle()
        assertEquals(11, Repo.slots(ctx).size)
        assertTrue(Archive.list(ctx).isEmpty())

        // Tekrar başlat ve eski dönemin özetine bak
        scrollTo("Yeni dönem başlat")
        rule.onNodeWithText("Yeni dönem başlat").performClick()
        rule.onNodeWithText("Başlat").performClick()
        rule.waitForIdle()
        assertTrue(Repo.slots(ctx).isEmpty())
        tab("Dersler")
        scrollTo("Ders programın boş")
        tab("Ayarlar")
        scrollTo("Geçmiş dönemler")
        rule.onNodeWithText("Geçmiş dönemler").performClick()
        rule.onNodeWithText("2026-2027 Güz Dönemi").performClick()
        rule.onNodeWithText("Girmedin").assertExists()
        rule.onNodeWithText("⛔", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Bütün derslerde devamsızlık sınırının altında kaldın ✓").assertExists()
    }
}
