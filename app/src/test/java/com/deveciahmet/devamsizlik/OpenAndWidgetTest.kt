package com.deveciahmet.devamsizlik

import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Bildirime / widget'a dokununca açılan ekran ve ana ekran widget'ının içeriği. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenAndWidgetTest {
    @get:Rule(order = 0)
    val clock = FixedClockRule("2026-11-04T12:00:00")

    @get:Rule(order = 1)
    val rule = createEmptyComposeRule()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun millis(s: String) = LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun View.text(id: Int): String = findViewById<TextView>(id).text.toString()

    @Test
    fun questionNotificationTap_opensThatCourseAtThatClass() {
        val intent = OpenRequest(OpenRequest.COURSE, "MAK 305", "mak305-car|2026-11-04").intent(ctx)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            rule.onNodeWithContentDescription("Ders ayarları").assertExists()
            rule.onNodeWithText("Hafta 6", substring = true).assertExists()
            // Geri: ana ekran
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            rule.onNodeWithText("Hafta 6/13").assertExists()
        }
    }

    @Test
    fun weeklySummaryTap_opensFillScreen_examTap_opensExams() {
        ActivityScenario.launch<MainActivity>(OpenRequest(OpenRequest.FILL).intent(ctx)).use {
            rule.onNodeWithText("Geçmiş dersleri doldur").assertExists()
        }
        ActivityScenario.launch<MainActivity>(OpenRequest(OpenRequest.EXAMS).intent(ctx)).use {
            rule.onNodeWithText("Henüz sınav eklemedin").assertExists()
        }
    }

    @Test
    fun widget_showsNextClassExamCountdownAndWarnings() {
        Repo.saveCourse(ctx, "MAK 303", "MAK 303", CourseConfig(name = "Isı Geçişi"))
        Repo.saveExam(ctx, Exam("e1", "MAK 303", "Vize", LocalDate.of(2026, 11, 17), LocalTime.of(13, 30)))
        Repo.setStatuses(ctx, listOf("mak309-sal|2026-09-29", "mak309-sal|2026-10-06", "mak309-sal|2026-10-13"), Status.ABSENT)

        val v = Widgets.build(ctx, widgetData(Repo.snapshot(ctx)), small = false).apply(ctx, FrameLayout(ctx))
        assertEquals("Sıradaki ders", v.text(R.id.w_label))
        assertEquals("Isı Geçişi", v.text(R.id.w_title))
        assertEquals("Yarın 08:30–10:29", v.text(R.id.w_detail))
        assertEquals("13", v.text(R.id.w_exam_big))
        assertEquals("gün kaldı", v.text(R.id.w_exam_small))
        assertEquals("Isı Geçişi · Vize", v.text(R.id.w_exam_title))
        assertEquals("⛔ Hakkın bitti · MAK 309", v.text(R.id.w_msg1))
        assertEquals("57 yoklama işaretlenmemiş", v.text(R.id.w_msg2))

        val small = Widgets.build(ctx, widgetData(Repo.snapshot(ctx)), small = true).apply(ctx, FrameLayout(ctx))
        assertEquals("13", small.text(R.id.w_exam_big))
        assertEquals("Isı Geçişi", small.text(R.id.w_title))
    }

    @Test
    fun placedWidget_updatesWhenDataChanges_andSchedulesNextRefresh() {
        val mgr = AppWidgetManager.getInstance(ctx)
        val id = shadowOf(mgr).createWidget(DevamsizlikWidget::class.java, R.layout.widget)
        assertEquals("MAK 303", shadowOf(mgr).getViewFor(id).text(R.id.w_title))

        Repo.saveCourse(ctx, "MAK 303", "MAK 303", CourseConfig(name = "Isı Geçişi"))
        Scheduler.reschedule(ctx)
        assertEquals("Isı Geçişi", shadowOf(mgr).getViewFor(id).text(R.id.w_title))

        val am = shadowOf(ctx.getSystemService(AlarmManager::class.java))
        assertTrue(am.scheduledAlarms.any { it.getType() == AlarmManager.RTC && it.getTriggerAtMs() == millis("2026-11-05T00:01:00") })
    }
}
