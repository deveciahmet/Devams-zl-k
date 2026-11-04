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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat

@Composable
fun HomeScreen(snap: Snapshot, nav: Nav) {
    val threshold = snap.notify.warnThreshold
    val sorted = remember(snap) { snap.stats.sortedWith(compareBy<CourseStats>({ it.remaining }, { it.code })) }
    val exhausted = sorted.filter { it.exhausted }
    val unanswered = snap.unansweredPast.size
    val next = remember(snap) { Scheduler.nextAsk(snap) }
    val week = snap.currentWeek
    val ctx = LocalContext.current
    val notificationsOn = remember(snap) { NotificationManagerCompat.from(ctx).areNotificationsEnabled() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Devamsızlık Takibi") },
            actions = { IconButton(onClick = { nav.push(Screen.Settings) }) { Icon(Icons.Default.Settings, "Ayarlar") } },
        )
    }) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        val label = when {
                            week < 1 -> "Dönem henüz başlamadı"
                            week > snap.weeks -> "Dönem bitti"
                            else -> "Hafta $week / ${snap.weeks}"
                        }
                        Text(label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { week.coerceIn(0, snap.weeks).toFloat() / snap.weeks.coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Hint("Varsayılan devam şartı: %${snap.requiredPercent} (en fazla %${100 - snap.requiredPercent} devamsızlık)")
                        if (next != null) {
                            val part = if (next.count > 1) " (${next.index}. saat)" else ""
                            Hint("Sonraki soru: ${snap.title(next.code)}$part • ${dayShortName(next.session.slot.day)} " +
                                "${next.time.toLocalDate().format(dateFmt)} ${next.time.toLocalTime().format(timeFmt)}")
                        }
                    }
                }
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
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Ders programın boş", fontWeight = FontWeight.Bold)
                            Hint("Programını bir fotoğraftan aktarabilir ya da elle ekleyebilirsin.")
                            Button(onClick = { nav.push(Screen.Import) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Programı fotoğraftan ekle")
                            }
                            OutlinedButton(onClick = { nav.push(Screen.Settings) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Elle ekle (Ayarlar)")
                            }
                        }
                    }
                }
            }

            if (unanswered > 0) {
                item(key = "unanswered") {
                    Card(
                        Modifier.fillMaxWidth().clickable { nav.push(Screen.Fill) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("$unanswered geçmiş yoklama işaretlenmemiş", fontWeight = FontWeight.Bold)
                                Hint("Önceki haftaları doldurmadan istatistikler eksik olur.")
                            }
                            Button(onClick = { nav.push(Screen.Fill) }) { Text("Doldur") }
                        }
                    }
                }
            }

            if (exhausted.isNotEmpty()) {
                item(key = "exhausted") {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Red, contentColor = Color.White),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, null)
                                Spacer(Modifier.width(8.dp))
                                Text("DİKKAT! Devamsızlık hakkın kalmadı", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            }
                            Spacer(Modifier.height(8.dp))
                            exhausted.forEach {
                                val txt = if (it.remaining < 0) {
                                    "• ${it.title}: sınırı ${-it.remaining} ${it.unit} AŞTIN (${it.absent}/${it.allowed})"
                                } else {
                                    "• ${it.title}: hakkın bitti (${it.absent}/${it.allowed}) — bir daha kaçırma!"
                                }
                                Text(txt, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            if (sorted.isNotEmpty()) item(key = "title") { SectionTitle("Dersler") }
            items(sorted, key = { "c_${it.code}" }) { s ->
                CourseCard(s, threshold) { nav.push(Screen.Course(s.code)) }
            }
            item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
        }
    }
}
