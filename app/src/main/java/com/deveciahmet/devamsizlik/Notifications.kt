package com.deveciahmet.devamsizlik

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.LocalDateTime
import java.time.ZoneId

const val CHANNEL_ID = "yoklama"
const val EXTRA_KEY = "session_key"
const val EXTRA_STATUS = "status"

object Alarms {

    /** Şu andan (veya [after]'dan) sonra biten ilk dersin bitişine alarm kurar. */
    fun scheduleNext(ctx: Context, after: LocalDateTime = LocalDateTime.now()): Session? {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val next = Repo.sessions(ctx)
            .filter { it.end.isAfter(after) && Repo.status(ctx, it.key) != Status.CANCELLED }
            .minByOrNull { it.end }

        val intent = Intent(ctx, AlarmReceiver::class.java)
        if (next == null) {
            PendingIntent.getBroadcast(ctx, 0, intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let { am.cancel(it) }
            return null
        }
        intent.putExtra(EXTRA_KEY, next.key)
        val pi = PendingIntent.getBroadcast(ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val millis = next.end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        }
        return next
    }

    fun nextSession(ctx: Context, now: LocalDateTime = LocalDateTime.now()): Session? =
        Repo.sessions(ctx)
            .filter { it.end.isAfter(now) && Repo.status(ctx, it.key) != Status.CANCELLED }
            .minByOrNull { it.end }
}

object Notifier {

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(CHANNEL_ID, "Yoklama soruları", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "Her dersin bitiminde derse girip girmediğini sorar"
            nm.createNotificationChannel(ch)
        }
    }

    private fun actionIntent(ctx: Context, s: Session, status: Status?, req: Int): PendingIntent {
        val i = Intent(ctx, ActionReceiver::class.java).apply {
            action = "com.deveciahmet.devamsizlik.ANSWER.$req"
            putExtra(EXTRA_KEY, s.key)
            putExtra(EXTRA_STATUS, status?.name)
        }
        return PendingIntent.getBroadcast(ctx, s.key.hashCode() * 4 + req, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun notificationId(s: Session) = s.key.hashCode()

    fun askAttendance(ctx: Context, s: Session) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(ctx)

        val open = PendingIntent.getActivity(ctx, 1,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_check)
            .setContentTitle("${s.slot.code} dersine girdin mi?")
            .setContentText("${dayLongName(s.slot.day)} ${s.slot.start.format(timeFmt)}–${s.slot.end.format(timeFmt)} • Hafta ${s.week}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Evet", actionIntent(ctx, s, Status.ATTENDED, 0))
            .addAction(0, "Hayır", actionIntent(ctx, s, Status.ABSENT, 1))
            .addAction(0, "Ders yapılmadı", actionIntent(ctx, s, Status.CANCELLED, 2))
            .build()
        NotificationManagerCompat.from(ctx).notify(notificationId(s), n)
    }
}

/** Ders bitiminde tetiklenir: soruyu sorar ve bir sonraki alarmı kurar. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY)
        val session = key?.let { Repo.findSession(ctx, it) }
        val now = LocalDateTime.now()
        if (session != null) {
            // Bu ders + (alarm gecikmişse) arada biten diğer dersler
            Repo.sessions(ctx)
                .filter { !it.end.isBefore(session.end) && !it.end.isAfter(maxOf(now, session.end)) }
                .filter { Repo.status(ctx, it.key) == null }
                .forEach { Notifier.askAttendance(ctx, it) }
        }
        // Aynı oturum için tekrar alarm kurulmasın diye bitiş anından sonrasına bak.
        val after = if (session != null && session.end.isAfter(now)) session.end else now
        Alarms.scheduleNext(ctx, after)
    }
}

/** Bildirimdeki Evet / Hayır / Ders yapılmadı tuşları. Uygulamayı açmadan kaydeder. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val status = intent.getStringExtra(EXTRA_STATUS)?.let { Status.valueOf(it) } ?: return
        Repo.setStatus(ctx, key, status)
        val session = Repo.findSession(ctx, key)
        NotificationManagerCompat.from(ctx).cancel(key.hashCode())
        val code = session?.slot?.code ?: "Ders"
        val msg = when (status) {
            Status.ATTENDED -> "$code: girdin olarak kaydedildi ✓"
            Status.ABSENT -> {
                val st = Repo.stats(ctx).firstOrNull { it.code == code }
                when {
                    st == null -> "$code: devamsızlık kaydedildi"
                    st.remaining < 0 -> "$code: devamsızlık sınırını AŞTIN! (${st.absent}/${st.allowed})"
                    st.remaining == 0 -> "$code: devamsızlık hakkın BİTTİ! (${st.absent}/${st.allowed})"
                    else -> "$code: devamsızlık kaydedildi (kalan hak: ${st.remaining})"
                }
            }
            Status.CANCELLED -> "$code: ders yapılmadı olarak kaydedildi"
        }
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
    }
}

/** Telefon yeniden başlayınca / saat değişince alarmı tekrar kurar. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Alarms.scheduleNext(ctx)
    }
}
