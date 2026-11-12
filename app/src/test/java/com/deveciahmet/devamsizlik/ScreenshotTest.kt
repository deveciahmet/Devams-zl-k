package com.deveciahmet.devamsizlik

import android.app.DatePickerDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
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
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.LocalTime

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

    private fun save(name: String, bmp: Bitmap) =
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

    private fun shot(name: String) {
        rule.waitForIdle()
        rule.activityRule.scenario.onActivity { act ->
            val v = act.window.decorView
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bmp))
            save(name, bmp)
        }
    }

    /** Açık pencerenin (dialog) görüntüsü. */
    private fun dialogShot(name: String) {
        rule.waitForIdle()
        val d = ShadowDialog.getLatestDialog() ?: return
        val v = d.window!!.decorView
        val bmp = Bitmap.createBitmap(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        save(name, bmp)
    }

    private fun back() {
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    /** Alt çubuktaki sekme (aynı yazı ekranda başlık olarak da geçebilir). */
    private fun tab(name: String) {
        rule.onNode(hasText(name) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
        rule.waitForIdle()
    }

    private fun widgetShot(name: String, small: Boolean, widthDp: Int, heightDp: Int) {
        val d = ctx.resources.displayMetrics.density
        val w = (widthDp * d).toInt()
        val h = (heightDp * d).toInt()
        val host = FrameLayout(ctx)
        val v: View = Widgets.build(ctx, widgetData(Repo.snapshot(ctx)), small).apply(ctx, host)
        host.addView(v, FrameLayout.LayoutParams(w, h))
        host.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF5B7FA6.toInt()) // duvar kâğıdı
        host.draw(Canvas(bmp))
        save(name, bmp)
    }

    @Test
    fun screens() {
        // Gerçekçi veri: ilk 5 hafta dolu, birkaç devamsızlık; bu haftanın Pzt/Sal'ı işaretsiz; adlar İTÜ'den
        Itu.fillCourseInfo(ctx)
        Repo.saveCourse(ctx, "MAK 303", "MAK 303", Repo.courseConfigs(ctx).getValue("MAK 303").copy(polls = 2))
        val snap = Repo.snapshot(ctx)
        val absent = mutableSetOf<String>()
        snap.polls.filter { it.code == "MAK 317" && it.session.week in listOf(1, 3, 4) }.forEach { absent += it.key }
        snap.polls.filter { it.code == "MAK 309" && it.session.week in listOf(2, 5) }.forEach { absent += it.key }
        snap.polls.filter { it.code == "MAK 303" && it.session.week == 2 && it.index == 2 }.forEach { absent += it.key }
        val past = snap.polls.filter { !it.time.isAfter(snap.now) && it.session.week <= 5 }
        Repo.setStatuses(ctx, past.map { it.key }.filterNot { it in absent }, Status.ATTENDED)
        Repo.setStatuses(ctx, absent, Status.ABSENT)
        Repo.setStatus(ctx, "mak305-car|2026-11-04", Status.ATTENDED)
        Repo.addHolidays(ctx, listOf(LocalDate.of(2026, 10, 29)))
        Repo.saveExam(ctx, Exam("e1", "MAK 303", "Vize", LocalDate.of(2026, 11, 11), LocalTime.of(13, 30), "MKB A363"))
        Repo.saveExam(ctx, Exam("e2", "MAK 335", "Vize", LocalDate.of(2026, 11, 20), LocalTime.of(9, 30)))
        Repo.saveExam(ctx, Exam("e3", "MAK 309", "Quiz", LocalDate.of(2026, 10, 21)))
        Scheduler.reschedule(ctx)
        rule.waitForIdle()

        widgetShot("00_widget", small = false, widthDp = 330, heightDp = 160)
        widgetShot("00_widget_small", small = true, widthDp = 160, heightDp = 160)

        shot("01_home")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Akışkanlar Mekaniği II"))
        shot("02_home_courses")

        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Doldur"))
        rule.onNodeWithText("Doldur").performClick()
        shot("03_fill")
        back()

        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Isı Geçişi"))
        rule.onAllNodesWithText("Isı Geçişi").onFirst().performClick()
        shot("04_course_two_polls")
        rule.onNodeWithContentDescription("Ders ayarları").performClick()
        shot("05_course_edit")
        rule.onNodeWithText("Kaydet").performScrollTo()
        shot("06_course_edit_bottom")
        back()
        back()

        tab("Sınavlar")
        shot("07_exams")
        // Not: metin kutulu pencereler (sınav ekleme) Robolectric'in NATIVE modunda boşta kalmadığı için çekilmiyor

        tab("Ayarlar")
        shot("09_settings")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Cevapsız soruyu tekrar sor"))
        shot("10_settings_notifications")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Sürüm", substring = true))
        shot("11_settings_backup")

        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Tatil günleri"))
        rule.onNodeWithText("Tatil günleri").performClick()
        shot("12_holidays")
        rule.onNodeWithText("Tatil günü ekle").performClick()
        rule.waitForIdle()
        (ShadowDialog.getLatestDialog() as DatePickerDialog).apply {
            updateDate(2026, 10, 19)
            getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        }
        ShadowLooper.idleMainLooper()
        shot("13_holiday_add")
        dialogShot("13b_holiday_add_dialog")
        rule.onNodeWithText("Vazgeç").performClick()
        back()

        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Programı Kepler'den ekle"))
        rule.onNodeWithText("Programı Kepler'den ekle").performClick()
        shot("14_kepler")
        rule.onNodeWithText("Fotoğraf yerine ders kodlarını yaz").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("MAK 335\nMAK 303\nITB 220\n14955\nMAK 305\nBEB 104 Cuma 10:30/12:29")
        rule.onNodeWithText("Bul").performClick()
        rule.waitUntil(10_000) {
            ShadowLooper.idleMainLooper()
            rule.onAllNodesWithText("Bulunan dersler", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        shot("15_kepler_result")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Programa kaydet", substring = true))
        shot("16_kepler_result_bottom")
        back()

        // Yeni dönem: arşiv özeti
        Archive.startNewTerm(ctx, "2026-2027 Güz Dönemi", LocalDate.of(2027, 2, 15))
        Scheduler.reschedule(ctx)
        rule.waitForIdle()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Geçmiş dönemler"))
        rule.onNodeWithText("Geçmiş dönemler").performClick()
        rule.onNodeWithText("2026-2027 Güz Dönemi").performClick()
        shot("17_archive_summary")
        back()
        back()
        tab("Dersler")
        shot("18_new_term_home")
    }
}
