package com.deveciahmet.devamsizlik

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import java.time.ZoneId

/**
 * Ana ekran widget'ı: sıradaki (ya da süren) ders, sıradaki sınava kalan gün ve en önemli devamsızlık uyarıları.
 * İçerik [widgetData] ile hesaplanır; burada sadece görünüm kurulur.
 */
class DevamsizlikWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val snap = Repo.snapshot(ctx)
        Widgets.update(ctx, mgr, ids, snap)
        Widgets.scheduleNext(ctx, snap)
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) {
        Widgets.update(ctx, mgr, intArrayOf(id), Repo.snapshot(ctx))
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == Widgets.ACTION_REFRESH) Widgets.refresh(ctx)
    }

    override fun onDisabled(ctx: Context) = Widgets.cancelAlarm(ctx)
}

object Widgets {
    const val ACTION_REFRESH = "com.deveciahmet.devamsizlik.WIDGET_REFRESH"

    fun ids(ctx: Context): IntArray = AppWidgetManager.getInstance(ctx)
        ?.getAppWidgetIds(ComponentName(ctx, DevamsizlikWidget::class.java)) ?: IntArray(0)

    /** Ekrandaki bütün widget'ları günceller ve bir sonraki yenilemeyi kurar. Widget yoksa hiçbir şey yapmaz. */
    fun refresh(ctx: Context, snap: Snapshot? = null) {
        val ids = ids(ctx)
        if (ids.isEmpty()) return
        val s = snap ?: Repo.snapshot(ctx)
        update(ctx, AppWidgetManager.getInstance(ctx), ids, s)
        scheduleNext(ctx, s)
    }

    fun update(ctx: Context, mgr: AppWidgetManager, ids: IntArray, snap: Snapshot) {
        val data = widgetData(snap)
        for (id in ids) mgr.updateAppWidget(id, views(ctx, data, mgr.getAppWidgetOptions(id)))
    }

    private fun views(ctx: Context, data: WidgetData, options: Bundle?): RemoteViews {
        if (Build.VERSION.SDK_INT >= 31) {
            return RemoteViews(mapOf(SizeF(110f, 80f) to build(ctx, data, small = true), SizeF(220f, 80f) to build(ctx, data, small = false)))
        }
        val minWidth = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250) ?: 250
        return build(ctx, data, small = minWidth in 1 until 220)
    }

    private fun open(ctx: Context, req: OpenRequest): PendingIntent = req.pending(ctx)

    fun build(ctx: Context, d: WidgetData, small: Boolean): RemoteViews {
        val v = RemoteViews(ctx.packageName, if (small) R.layout.widget_small else R.layout.widget)
        v.setTextViewText(R.id.w_label, d.label)
        v.setTextViewText(R.id.w_title, d.title)
        v.setTextViewText(R.id.w_detail, d.detail)
        v.setOnClickPendingIntent(R.id.w_root, open(ctx, OpenRequest(OpenRequest.HOME)))
        v.setOnClickPendingIntent(
            R.id.w_class,
            open(ctx, d.classCode?.let { OpenRequest(OpenRequest.COURSE, it) } ?: OpenRequest(OpenRequest.HOME)),
        )

        v.setTextViewText(R.id.w_exam_big, d.examBig ?: "+")
        v.setTextViewText(R.id.w_exam_small, d.examSmall)
        v.setTextViewText(R.id.w_exam_title, d.examTitle)
        v.setViewVisibility(R.id.w_exam_title, if (d.examTitle.isBlank()) View.GONE else View.VISIBLE)
        v.setOnClickPendingIntent(R.id.w_exam, open(ctx, OpenRequest(OpenRequest.EXAMS)))

        if (!small) {
            val msgViews = listOf(R.id.w_msg1, R.id.w_msg2)
            msgViews.forEachIndexed { i, id ->
                val a = d.messages.getOrNull(i)
                if (a == null) {
                    v.setViewVisibility(id, View.GONE)
                    return@forEachIndexed
                }
                v.setViewVisibility(id, View.VISIBLE)
                val icon = when (a.level) {
                    Level.DANGER -> "⛔ "
                    Level.WARN -> "⚠ "
                    Level.INFO -> ""
                }
                v.setTextViewText(id, icon + a.short)
                val color = when (a.level) {
                    Level.DANGER -> R.color.widget_danger
                    Level.WARN -> R.color.widget_warn
                    Level.INFO -> R.color.widget_text_secondary
                }
                // Android 12+: renk, widget her çizildiğinde (açık/koyu temaya göre) kaynaktan çözülsün
                if (Build.VERSION.SDK_INT >= 31) v.setColorStateList(id, "setTextColor", color)
                else v.setTextColor(id, ctx.getColor(color))
                val req = when {
                    a.code != null -> OpenRequest(OpenRequest.COURSE, a.code)
                    a.unanswered -> OpenRequest(OpenRequest.FILL)
                    else -> OpenRequest(OpenRequest.HOME)
                }
                v.setOnClickPendingIntent(id, open(ctx, req))
            }
        }
        return v
    }

    private fun alarmIntent(ctx: Context, flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        ctx, 7, Intent(ctx, DevamsizlikWidget::class.java).setAction(ACTION_REFRESH), flags or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Bir dersin başlaması/bitmesi ya da gece yarısı widget'ı yeniler (telefon uyanıkken; uyandırmaz). */
    fun scheduleNext(ctx: Context, snap: Snapshot) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = alarmIntent(ctx, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val at = nextWidgetRefresh(snap).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
                am.setExact(AlarmManager.RTC, at, pi)
            } else {
                am.setWindow(AlarmManager.RTC, at, 10 * 60 * 1000L, pi)
            }
        } catch (e: SecurityException) {
            am.setWindow(AlarmManager.RTC, at, 10 * 60 * 1000L, pi)
        }
    }

    fun cancelAlarm(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        alarmIntent(ctx, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it) }
    }
}
