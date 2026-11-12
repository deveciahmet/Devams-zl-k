@file:OptIn(ExperimentalMaterial3Api::class)

package com.deveciahmet.devamsizlik

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat

@Composable
fun HomeScreen(snap: Snapshot, nav: Nav) {
    val ctx = LocalContext.current
    val threshold = snap.notify.warnThreshold
    val sorted = remember(snap) { snap.stats.sortedWith(compareBy<CourseStats>({ it.remaining }, { it.code })) }
    val courseAlerts = remember(snap) { alerts(snap).filter { it.code != null } }
    val unanswered = snap.unansweredPast.size
    val notificationsOn = remember(snap) { NotificationManagerCompat.from(ctx).areNotificationsEnabled() }

    TabScaffold(nav, "Devamsızlık") { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "today") { TodayCard(snap, nav) }

            snap.upcomingExams.firstOrNull()?.let { e ->
                item(key = "exam") { NextExamCard(snap, e, snap.upcomingExams.size - 1) { nav.tab(Screen.Exams) } }
            }

            if (!notificationsOn) {
                item(key = "notif_off") {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Bildirimler kapalı", fontWeight = FontWeight.Bold)
                                Hint("Ders sonlarında \"derse girdin mi?\" sorusu gelmez.")
                            }
                            Button(onClick = {
                                ctx.startActivity(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                                )
                            }) { Text("Aç") }
                        }
                    }
                }
            }

            if (snap.slots.isEmpty()) {
                item(key = "empty") {
                    InfoCard(
                        "Ders programın boş",
                        "Kepler'deki ders programının ekran görüntüsünden programını ekleyebilirsin. " +
                            "Ders adları, günler ve saatler İTÜ'nün açılan dersler listesinden gelir.",
                    ) {
                        Button(onClick = { nav.push(Screen.Import) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Kepler'den ekle")
                        }
                        OutlinedButton(onClick = { nav.tab(Screen.Settings) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Elle ekle (Ayarlar)")
                        }
                    }
                }
            }

            if (unanswered > 0) {
                item(key = "unanswered") {
                    Card(
                        Modifier.fillMaxWidth().clickable { nav.push(Screen.Fill) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("$unanswered yoklama işaretlenmemiş", fontWeight = FontWeight.Bold)
                                Hint("Doldurmazsan devamsızlık hesabı eksik kalır.")
                            }
                            Button(onClick = { nav.push(Screen.Fill) }) { Text("Doldur") }
                        }
                    }
                }
            }

            if (courseAlerts.isNotEmpty()) {
                item(key = "alerts") { AlertsCard(courseAlerts) { nav.push(Screen.Course(it)) } }
            }

            if (sorted.isNotEmpty()) item(key = "title") { SectionTitle("Dersler", Modifier.padding(top = 4.dp)) }
            items(sorted, key = { "c_${it.code}" }) { s ->
                CourseCard(s, threshold) { nav.push(Screen.Course(s.code)) }
            }
            item(key = "bottom") { Spacer(Modifier.height(8.dp)) }
        }
    }
}

private fun weekLabel(snap: Snapshot): String {
    val week = snap.currentWeek
    return when {
        week < 1 -> "Dönem başlamadı"
        week > snap.weeks -> "Dönem bitti"
        else -> "Hafta $week/${snap.weeks}"
    }
}

/** Bugünkü dersler ve durumları; ders yoksa sıradaki ders. */
@Composable
private fun TodayCard(snap: Snapshot, nav: Nav) {
    val today = remember(snap) { todaysClasses(snap) }
    val next = remember(snap) { currentOrNextClass(snap) }
    val week = snap.currentWeek
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Bugün", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Hint(snap.today.format(dateDayFmt))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(weekLabel(snap), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Hint(snap.termName)
                }
            }
            if (week in 1..snap.weeks) {
                LinearProgressIndicator(
                    progress = { week.toFloat() / snap.weeks },
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
                )
            }
            when {
                snap.slots.isEmpty() -> Hint("Programına ders ekleyince bugünkü derslerin burada görünür.")
                snap.today in snap.holidays && today.isNotEmpty() ->
                    Text("Bugün tatil: derslerin \"yapılmadı\" sayılıyor.", style = MaterialTheme.typography.bodyMedium)
                today.isEmpty() -> {
                    Text("Bugün dersin yok.", style = MaterialTheme.typography.bodyMedium)
                    if (next != null) {
                        Hint("Sıradaki: ${snap.title(next.session.slot.code)} • ${classDetail(snap, next)}")
                    } else if (week > snap.weeks) {
                        Hint("Dönem bitti. Ayarlar → Yeni dönem başlat ile bahar dönemine temiz başlayabilirsin.")
                    }
                }
                else -> today.forEach { s ->
                    TodayRow(snap, s, isNext = next?.session == s) { nav.push(Screen.Course(s.slot.code, s.key)) }
                }
            }
        }
    }
}

@Composable
private fun TodayRow(snap: Snapshot, s: Session, isNext: Boolean, onClick: () -> Unit) {
    val state = sessionState(snap, s)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(s.slot.start.format(timeFmt), Modifier.width(52.dp), fontWeight = FontWeight.SemiBold)
        Column(Modifier.weight(1f)) {
            Text(snap.title(s.slot.code), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Hint(listOf(timeRange(s.slot.start, s.slot.end), s.slot.place).filter { it.isNotBlank() }.joinToString(" • "))
        }
        StateTag(state, isNext)
    }
}

@Composable
fun StateTag(state: SessionState, isNext: Boolean = false) {
    val primary = MaterialTheme.colorScheme.primary
    val (text, color) = when (state) {
        SessionState.ATTENDED -> "Girdin" to Green
        SessionState.ABSENT -> "Girmedin" to Red
        SessionState.CANCELLED -> "Yapılmadı" to Color.Gray
        SessionState.PARTIAL -> "Kısmen" to Orange
        SessionState.UNANSWERED -> "İşaretsiz" to MaterialTheme.colorScheme.tertiary
        SessionState.ONGOING -> "Şu an" to primary
        SessionState.UPCOMING -> (if (isNext) "Sıradaki" else "") to primary
    }
    if (text.isEmpty()) return
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.14f)) {
        Text(text, color = color, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

/** "17 Kasım Salı • 13:30 • MKB A501" */
fun examWhen(e: Exam): String =
    listOfNotNull(e.date.format(dateDayFmt), e.time?.format(timeFmt), e.place.ifBlank { null }).joinToString(" • ")

@Composable
private fun NextExamCard(snap: Snapshot, e: Exam, more: Int, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            DaysBadge(daysUntil(snap.today, e.date))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Hint("Sıradaki sınav")
                Text(snap.examTitle(e), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Hint(examWhen(e))
                if (more > 0) Hint("+$more sınav daha")
            }
        }
    }
}

@Composable
private fun AlertsCard(alerts: List<Alert>, onOpen: (String) -> Unit) {
    val danger = alerts.any { it.level == Level.DANGER }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (danger) MaterialTheme.colorScheme.errorContainer else Orange.copy(alpha = 0.14f),
        ),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, null, tint = if (danger) Red else Orange)
                Spacer(Modifier.width(8.dp))
                Text(if (danger) "Dikkat: devamsızlık sınırı" else "Hakkı azalan dersler", fontWeight = FontWeight.Bold)
            }
            alerts.forEach { a ->
                Row(
                    Modifier.fillMaxWidth().clickable { a.code?.let(onOpen) }.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text((if (a.level == Level.DANGER) "⛔ " else "⚠ ") + a.text, Modifier.weight(1f),
                        fontWeight = if (a.level == Level.DANGER) FontWeight.SemiBold else FontWeight.Normal)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                }
            }
        }
    }
}
