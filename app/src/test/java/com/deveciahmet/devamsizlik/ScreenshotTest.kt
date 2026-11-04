package com.deveciahmet.devamsizlik

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Bütün ekranları gerçekçi veriyle dolaşır ve ekran görüntülerini app/build/screenshots/ altına kaydeder
 * (emülatör olmadan arayüzü gözle kontrol etmek için). Ekranlardan biri çökerse test de düşer.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
class ScreenshotTest {
    @get:Rule(order = 0)
    val clock = FixedClockRule("2026-11-04T12:00:00")

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val out = File("build/screenshots").apply { mkdirs() }

    private fun shot(name: String) {
        rule.waitForIdle()
        rule.activityRule.scenario.onActivity { act ->
            val v = act.window.decorView
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(android.graphics.Canvas(bmp))
            FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun back() {
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    @Test
    fun screens() {
        // Gerçekçi veri: ilk 5 hafta dolu, birkaç devamsızlık; bu haftanın Pzt/Sal'ı işaretsiz
        Repo.saveCourse(ctx, "MAK 303", "MAK 303", CourseConfig(name = "Makine Teorisi", polls = 2))
        val snap = Repo.snapshot(ctx)
        val absent = mutableSetOf<String>()
        snap.polls.filter { it.code == "MAK 317" && it.session.week in listOf(1, 3, 4) }.forEach { absent += it.key }
        snap.polls.filter { it.code == "MAK 309" && it.session.week in listOf(2, 5) }.forEach { absent += it.key }
        snap.polls.filter { it.code == "MAK 303" && it.session.week == 2 && it.index == 2 }.forEach { absent += it.key }
        val past = snap.polls.filter { !it.time.isAfter(snap.now) && it.session.week <= 5 }
        Repo.setStatuses(ctx, past.map { it.key }.filterNot { it in absent }, Status.ATTENDED)
        Repo.setStatuses(ctx, absent, Status.ABSENT)
        rule.waitForIdle()

        shot("01_home")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("MAK 335"))
        shot("02_home_courses")

        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Doldur"))
        rule.onNodeWithText("Doldur").performClick()
        shot("03_fill")
        back()

        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Makine Teorisi"))
        rule.onNodeWithText("Makine Teorisi").performClick()
        shot("04_course_two_polls")
        rule.onNodeWithContentDescription("Ders ayarları").performClick()
        shot("05_course_edit")
        rule.onNodeWithText("Kaydet").performScrollTo()
        shot("06_course_edit_bottom")
        back()
        back()

        rule.onNodeWithContentDescription("Ayarlar").performClick()
        shot("07_settings")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Cevapsız soruyu tekrar sor"))
        shot("08_settings_notifications")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Sürüm", substring = true))
        shot("09_settings_bottom")

        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Programı fotoğraftan ekle"))
        rule.onNodeWithText("Programı fotoğraftan ekle").performClick()
        shot("10_import")
        val list = rule.onNode(hasScrollAction())
        list.performScrollToNode(hasSetTextAction())
        rule.onNode(hasSetTextAction()).performTextInput(
            "[{\"code\":\"MAK 335\",\"name\":\"Makine Dinamiği\",\"day\":1,\"start\":\"10:30\",\"end\":\"13:29\"}," +
                "{\"code\":\"MAK 303\",\"name\":\"\",\"day\":1,\"start\":\"15:30\",\"end\":\"17:29\"}," +
                "{\"code\":\"MAK 309\",\"name\":\"\",\"day\":9,\"start\":\"10:30\",\"end\":\"13:29\"}," +
                "{\"code\":\"ITB 220\",\"name\":\"\",\"day\":\"Salı\",\"start\":\"14:30\",\"end\":\"17:29\"}]"
        )
        list.performScrollToNode(hasText("Çözümle"))
        rule.onNodeWithText("Çözümle").performClick()
        list.performScrollToNode(hasText("Programa kaydet"))
        shot("11_import_preview")
    }
}
