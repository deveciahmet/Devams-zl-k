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
const val EXTRA_TEST = "test"

/** Uygulamanın hangi ekranla açılacağı (bildirimden ya da widget'tan). */
data class OpenRequest(val target: String, val code: String? = null, val focus: String? = null) {
    companion object {
        const val HOME = "home"
        const val COURSE = "course"
        const val FILL = "fill"
        const val EXAMS = "exams"
        const val IMPORT = "import"

        private const val EXTRA_OPEN = "open"
        private const val EXTRA_CODE = "open_code"
        private const val EXTRA_FOCUS = "open_focus"

        fun from(intent: Intent?): OpenRequest? {
            val target = intent?.getStringExtra(EXTRA_OPEN) ?: return null
            return OpenRequest(target, intent.getStringExtra(EXTRA_CODE), intent.getStringExtra(EXTRA_FOCUS))
        }
    }

    fun intent(ctx: Context): Intent = Intent(ctx, MainActivity::class.java)
        // data her istek için farklı olsun ki PendingIntent'ler birbirinin yerine geçmesin
        .setData(Uri.parse("devamsizlik://open/$target/${Uri.encode(code.orEmpty())}/${Uri.encode(focus.orEmpty())}"))
        .putExtra(EXTRA_OPEN, target)
        .putExtra(EXTRA_CODE, code)
        .putExtra(EXTRA_FOCUS, focus)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun pending(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 1, intent(ctx), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

object Notifier {
    const val CH_ASK = "yoklama"
    const val CH_WARN = "uyari"
    const val CH_SUMMARY = "ozet"
    const val CH_EXAM = "sinav"
    const val WEEKLY_ID = 900_001
    const val TEST_ID = 900_002

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
        ch(CH_EXAM, "Sınav hatırlatmaları", "Sınavdan önceki akşam hatırlatır", NotificationManager.IMPORTANCE_HIGH)
    }

    fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun courseTitle(code: String, cfg: CourseConfig): String = if (cfg.name.isBlank()) code else "${cfg.name} ($code)"

    fun pollNotificationId(pollKey: String): Int = pollKey.hashCode()

    fun question(poll: Poll, cfg: CourseConfig): String {
        val t = courseTitle(poll.code, cfg)
        return if (poll.count > 1) "$t dersinin ${poll.index}. saatine girdin mi?" else "$t dersine girdin mi?"
    }

    private fun answerIntent(ctx: Context, poll: Poll, status: Status, test: Boolean): PendingIntent {
        val i = Intent(ctx, ActionReceiver::class.java).apply {
            // data her yoklama + cevap için farklı olsun ki PendingIntent'ler birbirini ezmesin
            data = Uri.parse("devamsizlik://answer/${Uri.encode(poll.key)}/${status.name}${if (test) "/test" else ""}")
            putExtra(EXTRA_KEY, poll.key)
            putExtra(EXTRA_STATUS, status.name)
            putExtra(EXTRA_TEST, test)
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

    fun cancel(ctx: Context, id: Int) = NotificationManagerCompat.from(ctx).cancel(id)

    /**
     * "MAK 335 dersine girdin mi?" — Evet / Hayır / Ders yapılmadı. Bildirime dokununca o dersin işaretleme ekranı açılır.
     * [test]: ayarlardaki test bildirimi; cevabı kaydedilmez.
     */
    fun askPoll(ctx: Context, poll: Poll, cfg: CourseConfig, reminder: Boolean, test: Boolean = false) {
        val s = poll.session
        val q = question(poll, cfg)
        val title = when {
            test -> "Test: $q"
            reminder -> "Hatırlatma: $q"
            else -> q
        }
        val detail = buildString {
            append("${dayLongName(s.slot.day)} ${timeRange(s.slot.start, s.slot.end)} • Hafta ${s.week}")
            if (poll.count > 1) append(" • ${poll.index}. yoklama")
            if (test) append("\nBu bir test bildirimi: cevabın kaydedilmez.")
        }
        val n = NotificationCompat.Builder(ctx, CH_ASK)
            .setSmallIcon(R.drawable.ic_stat_check)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().setBigContentTitle(title).bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(OpenRequest(OpenRequest.COURSE, poll.code, s.key).pending(ctx))
            .setAutoCancel(true)
            .addAction(0, "Evet", answerIntent(ctx, poll, Status.ATTENDED, test))
            .addAction(0, "Hayır", answerIntent(ctx, poll, Status.ABSENT, test))
            .addAction(0, "Ders yapılmadı", answerIntent(ctx, poll, Status.CANCELLED, test))
            .build()
        post(ctx, if (test) TEST_ID else pollNotificationId(poll.key), n)
    }

    private fun warning(ctx: Context, id: Int, title: String, text: String, open: OpenRequest, channel: String = CH_WARN) {
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_check)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().setBigContentTitle(title).bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open.pending(ctx))
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
        warning(ctx, "warn|${st.code}".hashCode(), title, text, OpenRequest(OpenRequest.COURSE, st.code))
    }

    /** Hakkı azalan dersin başlamasından önce "kaçırma" hatırlatması. */
    fun preclass(ctx: Context, session: Session, st: CourseStats) {
        val place = if (session.slot.place.isBlank()) "" else ", ${session.slot.place}"
        val title = "${st.title} dersin yaklaşıyor (${timeRange(session.slot.start, session.slot.end)}$place)"
        val text = when {
            st.remaining < 0 -> "Devamsızlık sınırını zaten aştın (${st.absent}/${st.allowed})."
            st.remaining == 0 -> "Devamsızlık hakkın bitti! Bu derse girmezsen devamsızlıktan kalırsın."
            else -> "Son ${st.remaining} devamsızlık hakkın kaldı, kaçırma!"
        }
        warning(ctx, "pre|${session.key}".hashCode(), title, text, OpenRequest(OpenRequest.COURSE, st.code, session.key))
    }

    /** Sınavdan önceki akşam: "Yarın sınavın var: Isı Geçişi · Vize" */
    fun examReminder(ctx: Context, exam: Exam, snap: Snapshot) {
        val days = daysUntil(snap.today, exam.date)
        val title = when (days) {
            0L -> "Bugün sınavın var: ${snap.examTitle(exam)}"
            1L -> "Yarın sınavın var: ${snap.examTitle(exam)}"
            else -> "${daysLeftText(days)} sonra sınavın var: ${snap.examTitle(exam)}"
        }
        val text = listOfNotNull(
            exam.date.format(dateDayFmt),
            exam.time?.format(timeFmt),
            exam.place.ifBlank { null },
        ).joinToString(" • ") + ". Başarılar!"
        warning(ctx, "exam|${exam.id}".hashCode(), title, text, OpenRequest(OpenRequest.EXAMS), CH_EXAM)
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
        val today = at.toLocalDate()
        snap.upcomingExams.filter { daysUntil(today, it.date) in 0..7 }.forEach {
            lines += "📅 ${snap.examTitle(it)}: ${relativeDay(today, it.date)}${it.time?.let { t -> " ${t.format(timeFmt)}" }.orEmpty()}"
        }
        val unanswered = snap.unansweredPast.size
        if (unanswered > 0) lines += "İşaretlenmemiş $unanswered yoklama var, doldurmak için dokun."
        val n = NotificationCompat.Builder(ctx, CH_SUMMARY)
            .setSmallIcon(R.drawable.ic_stat_check)
            .setContentTitle(title)
            .setContentText(first)
            .setStyle(NotificationCompat.BigTextStyle().setBigContentTitle(title).bigText(lines.joinToString("\n")))
            .setContentIntent(OpenRequest(if (unanswered > 0) OpenRequest.FILL else OpenRequest.HOME).pending(ctx))
            .setAutoCancel(true)
            .build()
        post(ctx, WEEKLY_ID, n)
    }
}
