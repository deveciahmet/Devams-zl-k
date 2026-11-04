package com.deveciahmet.devamsizlik

import android.content.Context
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId

/** Saati, uygulama açılmadan önce 4 Kasım 2026 12:00'ye (6. hafta Çarşamba) sabitler. */
class FixedClockRule(private val time: String) : TestRule {
    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            val zone = ZoneId.systemDefault()
            AppClock.clock = Clock.fixed(LocalDateTime.parse(time).atZone(zone).toInstant(), zone)
            try {
                base.evaluate()
            } finally {
                AppClock.clock = Clock.systemDefaultZone()
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
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

    @Test
    fun home_showsWeek_courses_andUnansweredCard() {
        rule.onNodeWithText("Hafta 6 / 13").assertExists()
        // 5 tam hafta × 11 + bu hafta Pzt (2) + Sal (2) + Çar MAK 305 (1) = 60
        rule.onNodeWithText("60 geçmiş yoklama işaretlenmemiş").assertExists()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("MAK 118"))
        rule.onNodeWithText("MAK 118").assertExists()
    }

    @Test
    fun home_warnsWhenNotificationsAreOff() {
        rule.onNodeWithText("Bildirimler kapalı").assertDoesNotExist()
        org.robolectric.Shadows.shadowOf(ctx.getSystemService(android.app.NotificationManager::class.java))
            .setNotificationsEnabled(false)
        Repo.setStatus(ctx, "yenile", Status.ATTENDED) // ekranı yenilet
        rule.waitForIdle()
        rule.onNodeWithText("Bildirimler kapalı").assertExists()
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
        rule.onNode(isToggleable()).performClick()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Girmedim"))
        rule.onAllNodesWithText("Girmedim").onFirst().performClick()
        rule.waitForIdle()
        val st = Repo.snapshot(ctx).stat("MAK 335")!!
        assertEquals(1, st.absent)
        assertEquals(5, st.attended)

        back()
        rule.onNodeWithText("Hafta 6 / 13").assertExists()
    }

    @Test
    fun courseEdit_setsNamePollsAndLimit() {
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("MAK 303"))
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
    fun importFlow_parsesPreviewAndSaves() {
        rule.onNodeWithContentDescription("Ayarlar").performClick()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Programı fotoğraftan ekle"))
        rule.onNodeWithText("Programı fotoğraftan ekle").performClick()
        rule.onNodeWithText("1. Bu metni kopyala").assertExists()
        rule.onNodeWithText("Kopyala").performClick()
        val list = rule.onNode(hasScrollAction())

        val answer = "İşte program:\n```json\n" +
            "[{\"code\":\"MAK 335\",\"name\":\"Makine Dinamiği\",\"day\":1,\"start\":\"10:30\",\"end\":\"13:29\"}," +
            "{\"code\":\"FIZ 101\",\"name\":\"\",\"day\":3,\"start\":\"13:30\",\"end\":\"15:29\"}]\n```"
        list.performScrollToNode(hasSetTextAction())
        rule.onNode(hasSetTextAction()).performTextInput(answer)
        list.performScrollToNode(hasText("Çözümle"))
        rule.onNodeWithText("Çözümle").performClick()
        list.performScrollToNode(hasText("3. Kontrol et (2 ders saati)"))
        rule.onNodeWithText("3. Kontrol et (2 ders saati)").assertExists()
        list.performScrollToNode(hasText("Programa kaydet"))
        rule.onNodeWithText("Programa kaydet").performClick()
        rule.waitForIdle()

        val snap = Repo.snapshot(ctx)
        assertEquals(listOf("FIZ 101", "MAK 335"), snap.courseCodes)
        assertEquals("Makine Dinamiği", snap.config("MAK 335").name)
        rule.onNodeWithText("Ayarlar").assertExists()
    }

    @Test
    fun settings_rendersAllSectionsAndChangesNotifications() {
        rule.onNodeWithContentDescription("Ayarlar").performClick()
        val list = rule.onNode(hasScrollAction())
        list.performScrollToNode(hasText("1 saat"))
        rule.onNodeWithText("1 saat").performClick()
        list.performScrollToNode(hasText("2 hak"))
        rule.onNodeWithText("2 hak").performClick()
        list.performScrollToNode(hasText("Sürüm", substring = true))
        rule.waitForIdle()
        val ns = Repo.notifySettings(ctx)
        assertEquals(60, ns.reminderMinutes)
        assertEquals(2, ns.warnThreshold)
    }
}
