package com.deveciahmet.devamsizlik

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.LocalDateTime

const val EXTRA_KEY = "session_key"
const val EXTRA_STATUS = "status"

object Notifier {
    const val CH_ASK = "yoklama"
    const val CH_WARN = "uyari"
    const val CH_SUMMARY = "ozet"
    const val WEEKLY_ID = 900_001

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        fun ch(id: String, name: String, desc: String, importance: Int) {
            if (nm.getNotificationChannel(id) == null) {
                nm.createNotificationChannel(NotificationChannel(id, name, importance).apply { description = desc })
            }
        }
        ch(CH_ASK, "Yoklama soruları", "Her dersin (ya da yoklamanın) sonunda derse girip girmediğini sorar",
            NotificationManager.IMPORTANCE_HIGH)
        ch(CH_WARN, "Devamsızlık uyarıları", "Devamsızlık hakkın azaldığında ve riskli derslerden önce uyarır",
            NotificationManager.IMPORTANCE_HIGH)
        ch(CH_SUMMARY, "Haftalık özet", "Her hafta devam durumunun özeti", NotificationManager.IMPORTANCE_DEFAULT)
    }

    fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 1,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun courseTitle(code: String, cfg: CourseConfig): String = if (cfg.name.isBlank()) code else "${cfg.name} ($code)"

    fun pollNotificationId(pollKey: String): Int = pollKey.hashCode()

    fun question(poll: Poll, cfg: CourseConfig): String {
        val t = courseTitle(poll.code, cfg)
        return if (poll.count > 1) "$t dersinin ${poll.index}. saatine girdin mi?" else "$t dersine girdin mi?"
    }

    private fun answerIntent(ctx: Context, poll: Poll, status: Status): PendingIntent {
        val i = Intent(ctx, ActionReceiver::class.java).apply {
            // data her yoklama + cevap için farklı olsun ki PendingIntent'ler birbirini ezmesin
            data = Uri.parse("devamsizlik://answer/${Uri.encode(poll.key)}/${status.name}")
            putExtra(EXTRA_KEY, poll.key)
            putExtra(EXTRA_STATUS, status.name)
        }
        return PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    @SuppressLint("MissingPermission")
    private fun post(ctx: Context, id: Int, n: Notification) {
        if (!canPost(ctx)) return
        ensureChannels(ctx)
        NotificationManagerCompat.from(ctx).notify(id, n)
    }

    fun cancelPoll(ctx: Context, pollKey: String) = NotificationManagerCompat.from(ctx).cancel(pollNotificationId(pollKey))

    /** "MAK 335 dersine girdin mi?" — Evet / Hayır / Ders yapılmadı */
    fun askPoll(ctx: Context, poll: Poll, cfg: CourseConfig, reminder: Boolean) {
        val s = poll.session
        val q = question(poll, cfg)
        val title = if (reminder) "Hatırlatma: $q" else q
        val detail = buildString {
            append("${dayLongName(s.slot.day)} ${s.slot.start.format(timeFmt)}–${s.slot.end.format(timeFmt)} • Hafta ${s.week}")
            if (poll.count > 1) append(" • ${poll.index}. yoklama")
        }
        val n = NotificationCompat.Builder(ctx, CH_ASK)
            .setSmallIcon(R.drawable.ic_stat_check)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().setBigContentTitle(title).bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .addAction(0, "Evet", answerIntent(ctx, poll, Status.ATTENDED))
            .addAction(0, "Hayır", answerIntent(ctx, poll, Status.ABSENT))
            .addAction(0, "Ders yapılmadı", answerIntent(ctx, poll, Status.CANCELLED))
            .build()
        post(ctx, pollNotificationId(poll.key), n)
    }

    private fun warning(ctx: Context, id: Int, title: String, text: String) {
        val n = NotificationCompat.Builder(ctx, CH_WARN)
            .setSmallIcon(R.drawable.ic_stat_check)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().setBigContentTitle(title).bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        post(ctx, id, n)
    }

    /** "Hayır" dendikten sonra kalan hak eşiğe indiyse hemen uyarır. */
    fun warnAbsence(ctx: Context, st: CourseStats) {
        val title = when {
            st.remaining < 0 -> "⛔ ${st.title}: devamsızlık sınırını aştın!"
            st.remaining == 0 -> "⛔ ${st.title}: devamsızlık hakkın bitti!"
            else -> "⚠ ${st.title}: son ${st.remaining} devamsızlık hakkın kaldı"
        }
        val text = when {
            st.remaining < 0 -> "${st.absent} devamsızlık yaptın, sınır ${st.allowed}."
            st.remaining == 0 -> "Bir kez daha girmezsen devamsızlıktan kalırsın. (${st.absent}/${st.allowed})"
            else -> "${st.absent}/${st.allowed} devamsızlık yaptın. Dikkat et!"
        }
        warning(ctx, "warn|${st.code}".hashCode(), title, text)
    }

    /** Hakkı azalan dersin başlamasından önce "kaçırma" hatırlatması. */
    fun preclass(ctx: Context, session: Session, st: CourseStats) {
        val hours = "${session.slot.start.format(timeFmt)}–${session.slot.end.format(timeFmt)}"
        val title = "${st.title} dersin yaklaşıyor ($hours)"
        val text = when {
            st.remaining < 0 -> "Devamsızlık sınırını zaten aştın (${st.absent}/${st.allowed})."
            st.remaining == 0 -> "Devamsızlık hakkın bitti! Bu derse girmezsen devamsızlıktan kalırsın."
            else -> "Son ${st.remaining} devamsızlık hakkın kaldı, kaçırma!"
        }
        warning(ctx, "pre|${session.key}".hashCode(), title, text)
    }

    fun weekly(ctx: Context, sum: WeekSummary, snap: Snapshot, at: LocalDateTime) {
        val week = weekNumber(snap.semesterStart, at.toLocalDate().minusDays(1))
        val title = if (week in 1..snap.weeks) "Haftalık özet • $week. hafta" else "Haftalık özet"
        val first = buildString {
            append("Son 7 gün • Girdin: ${sum.attended} • Girmedin: ${sum.absent}")
            if (sum.cancelled > 0) append(" • Yapılmadı: ${sum.cancelled}")
            if (sum.unanswered > 0) append(" • İşaretsiz: ${sum.unanswered}")
        }
        val lines = mutableListOf(first)
        if (sum.absentByCourse.isNotEmpty()) {
            lines += "Devamsızlık: " + sum.absentByCourse.entries.joinToString(", ") { "${snap.title(it.key)} (${it.value})" }
        }
        val threshold = snap.notify.warnThreshold
        snap.stats.filter { it.remaining <= threshold }.sortedBy { it.remaining }.forEach {
            lines += when {
                it.remaining < 0 -> "⛔ ${it.title}: sınırı aştın (${it.absent}/${it.allowed})"
                it.remaining == 0 -> "⛔ ${it.title}: hakkın bitti"
                else -> "⚠ ${it.title}: son ${it.remaining} hak"
            }
        }
        val unanswered = snap.unansweredPast.size
        if (unanswered > 0) lines += "İşaretlenmemiş $unanswered yoklama var, doldurmak için dokun."
        val n = NotificationCompat.Builder(ctx, CH_SUMMARY)
            .setSmallIcon(R.drawable.ic_stat_check)
            .setContentTitle(title)
            .setContentText(first)
            .setStyle(NotificationCompat.BigTextStyle().setBigContentTitle(title).bigText(lines.joinToString("\n")))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        post(ctx, WEEKLY_ID, n)
    }
}
