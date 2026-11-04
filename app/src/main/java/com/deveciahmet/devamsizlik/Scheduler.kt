package com.deveciahmet.devamsizlik

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Tek bir alarm zinciri: bir sonraki olayın (soru, hatırlatma, ders öncesi uyarı, haftalık özet) saatine alarm
 * kurulur; alarm çalınca o ana kadar gelmiş olaylar işlenir ve sıradaki olaya yeni alarm kurulur.
 */
object Scheduler {
    /** Telefon kapalıyken kaçan olaylardan en fazla bu kadar eskisi sonradan bildirilir. */
    private const val WINDOW_HOURS = 6L

    // Eski sürümle aynı Intent (aynı PendingIntent) → güncellemede eski alarmın yerine geçer.
    private fun tick(ctx: Context, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(ctx, 0, Intent(ctx, AlarmReceiver::class.java), flags or PendingIntent.FLAG_IMMUTABLE)

    fun riskyCodes(snap: Snapshot): Set<String> =
        snap.stats.filter { it.remaining <= snap.notify.warnThreshold }.map { it.code }.toSet()

    fun events(snap: Snapshot): List<Event> =
        computeEvents(snap.polls, snap::status, riskyCodes(snap), snap.notify, snap.semesterStart, snap.weeks)

    fun nextAsk(snap: Snapshot): Poll? =
        snap.polls.filter { it.time.isAfter(snap.now) && snap.status(it.key) == null }.minByOrNull { it.time }

    /**
     * Sıradaki olaya alarm kurar. [resetCursor]: ayarlar değiştiğinde geçmişte kalan olayları
     * (ör. yeni eklenen bir dersin bugünkü saatini) bildirim yağmuruna çevirmemek için.
     */
    fun reschedule(ctx: Context, resetCursor: Boolean = false) {
        val now = AppClock.now()
        var cursor = Repo.cursor(ctx)
        if (cursor == null || (resetCursor && cursor.isBefore(now))) {
            cursor = now
            Repo.setCursor(ctx, now)
        }
        val from = maxOf(cursor, now.minusHours(WINDOW_HOURS))
        val next = events(Repo.snapshot(ctx, now)).filter { it.time.isAfter(from) }.minByOrNull { it.time }
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        if (next == null) {
            tick(ctx, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it) }
            return
        }
        val pi = tick(ctx, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val millis = next.time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        }
    }

    fun onAlarm(ctx: Context) {
        val now = AppClock.now()
        val cursor = Repo.cursor(ctx) ?: now.minusMinutes(1)
        val from = maxOf(cursor, now.minusHours(WINDOW_HOURS))
        val snap = Repo.snapshot(ctx, now)
        val due = events(snap).filter { it.time.isAfter(from) && !it.time.isAfter(now) }
        Repo.setCursor(ctx, now)
        handle(ctx, snap, due)
        reschedule(ctx)
    }

    private fun handle(ctx: Context, snap: Snapshot, due: List<Event>) {
        if (due.isEmpty()) return
        val byKey = snap.polls.associateBy { it.key }
        val asks = due.filter { it.type == EventType.ASK }.map { it.key }.toSet()
        val reminds = due.filter { it.type == EventType.REMIND }.map { it.key }.toSet() - asks
        for (k in asks) byKey[k]?.let { Notifier.askPoll(ctx, it, snap.config(it.code), reminder = false) }
        for (k in reminds) {
            val p = byKey[k] ?: continue
            if (snap.status(k) == null) Notifier.askPoll(ctx, p, snap.config(p.code), reminder = true)
        }
        if (snap.notify.warnEnabled) {
            val risky = riskyCodes(snap)
            for (sk in due.filter { it.type == EventType.PRECLASS }.map { it.key }.toSet()) {
                val session = snap.sessions.firstOrNull { it.key == sk } ?: continue
                val st = snap.stat(session.slot.code) ?: continue
                if (st.code in risky) Notifier.preclass(ctx, session, st)
            }
        }
        due.lastOrNull { it.type == EventType.WEEKLY }?.let { ev ->
            val sum = summarize(snap.polls, snap::status, ev.time.minusDays(7), ev.time)
            if (sum.total > 0) Notifier.weekly(ctx, sum, snap, ev.time)
        }
    }
}

/** Zamanı gelen bildirimleri gösterir ve sıradakine alarm kurar. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = Scheduler.onAlarm(ctx)
}

/** Telefon yeniden başlayınca / saat değişince / uygulama güncellenince alarmı tekrar kurar. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = Scheduler.reschedule(ctx)
}

/** Bildirimdeki Evet / Hayır / Ders yapılmadı tuşları. Uygulamayı açmadan kaydeder. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val status = intent.getStringExtra(EXTRA_STATUS)?.let { runCatching { Status.valueOf(it) }.getOrNull() } ?: return
        Repo.setStatus(ctx, key, status)
        Notifier.cancelPoll(ctx, key)

        val snap = Repo.snapshot(ctx)
        val poll = snap.polls.firstOrNull { it.key == key }
        val st = poll?.let { snap.stat(it.code) }
        val name = poll?.let { snap.title(it.code) } ?: "Ders"
        val part = if (poll != null && poll.count > 1) " (${poll.index}. saat)" else ""
        val msg = when (status) {
            Status.ATTENDED -> "$name$part: girdin olarak kaydedildi ✓"
            Status.CANCELLED -> "$name$part: ders yapılmadı olarak kaydedildi"
            Status.ABSENT -> when {
                st == null -> "$name$part: devamsızlık kaydedildi"
                st.remaining < 0 -> "$name: devamsızlık sınırını AŞTIN! (${st.absent}/${st.allowed})"
                st.remaining == 0 -> "$name: devamsızlık hakkın BİTTİ! (${st.absent}/${st.allowed})"
                else -> "$name$part: devamsızlık kaydedildi (kalan hak: ${st.remaining})"
            }
        }
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

        if (status == Status.ABSENT && st != null && snap.notify.warnEnabled && st.remaining <= snap.notify.warnThreshold) {
            Notifier.warnAbsence(ctx, st)
        }
        Scheduler.reschedule(ctx)
    }
}
