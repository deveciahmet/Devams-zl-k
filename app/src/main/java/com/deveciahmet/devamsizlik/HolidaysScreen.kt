@file:OptIn(ExperimentalMaterial3Api::class)

package com.deveciahmet.devamsizlik

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val dayMonthFmt = DateTimeFormatter.ofPattern("d MMMM", TR)

/** "29 Ekim Perşembe", "1–3 Nisan", "30 Mart – 2 Nisan" */
fun rangeText(a: LocalDate, b: LocalDate): String = when {
    a == b -> a.format(dateDayFmt)
    a.month == b.month && a.year == b.year -> "${a.dayOfMonth}–${b.format(dayMonthFmt)}"
    else -> "${a.format(dayMonthFmt)} – ${b.format(dayMonthFmt)}"
}

fun datesBetween(a: LocalDate, b: LocalDate): List<LocalDate> {
    val (from, to) = if (b.isBefore(a)) b to a else a to b
    return generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()
}

/**
 * Tatil (ya da toplu iptal) günleri: bir gün seçince o günün bütün dersleri "yapılmadı" olur, soru bildirimi gelmez
 * ve devamsızlık hakları buna göre yeniden hesaplanır.
 */
@Composable
fun HolidaysScreen(snap: Snapshot, nav: Nav) {
    val ctx = LocalContext.current
    var adding by remember { mutableStateOf<LocalDate?>(null) }
    val ranges = remember(snap) { holidayRanges(snap.holidays).sortedByDescending { it.first } }

    Scaffold(topBar = { BackBar("Tatil günleri", nav::pop) }) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "info") {
                Hint("Resmî tatil, bayram ya da toplu iptal olan bir günü seç: o günün bütün dersleri \"yapılmadı\" " +
                    "sayılır, soru bildirimi gelmez ve devamsızlık hakların buna göre hesaplanır.")
            }
            item(key = "add") {
                Button(onClick = { pickDate(ctx, snap.today) { adding = it } }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Tatil günü ekle")
                }
            }
            if (ranges.isEmpty()) item(key = "none") { Hint("Henüz tatil günü eklemedin.") }
            items(ranges, key = { "h_${it.first}" }) { (a, b) ->
                val count = snap.sessions.count { it.date in a..b }
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(rangeText(a, b), fontWeight = FontWeight.SemiBold)
                            Hint(if (count == 0) "Bu günlerde dersin yok" else "$count ders yapılmadı sayılıyor")
                        }
                        IconButton(onClick = {
                            Repo.removeHolidays(ctx, datesBetween(a, b))
                            Scheduler.reschedule(ctx)
                        }) { Icon(Icons.Default.Delete, "Tatili kaldır") }
                    }
                }
            }
            item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
        }
    }

    adding?.let { start ->
        HolidayDialog(snap, start, onDismiss = { adding = null }) { from, to ->
            val days = datesBetween(from, to)
            Repo.addHolidays(ctx, days)
            snap.polls.filter { it.session.date in days }.forEach { Notifier.cancelPoll(ctx, it.key) }
            Scheduler.reschedule(ctx)
            adding = null
        }
    }
}

@Composable
private fun HolidayDialog(snap: Snapshot, start: LocalDate, onDismiss: () -> Unit, onConfirm: (LocalDate, LocalDate) -> Unit) {
    val ctx = LocalContext.current
    var from by remember { mutableStateOf(start) }
    var to by remember { mutableStateOf(start) }
    val sessions = snap.sessions.filter { it.date in from..to }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tatil günü") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    pickDate(ctx, from) {
                        from = it
                        if (to.isBefore(it)) to = it
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Gün: ${from.format(dateDayFmt)}") }
                OutlinedButton(onClick = {
                    pickDate(ctx, to) { to = if (it.isBefore(from)) from else it }
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (to == from) "Birden çok gün mü? Son günü seç" else "Son gün: ${to.format(dateDayFmt)}")
                }
                if (sessions.isEmpty()) {
                    Hint("Bu günlerde dersin yok.")
                } else {
                    Text("${sessions.size} ders \"yapılmadı\" olarak işaretlenecek:", style = MaterialTheme.typography.bodyMedium)
                    sessions.take(8).forEach {
                        Hint("• ${dayShortName(it.slot.day)} ${it.slot.start.format(timeFmt)}  ${snap.title(it.slot.code)}")
                    }
                    if (sessions.size > 8) Hint("… ve ${sessions.size - 8} ders daha")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(from, to) }) { Text("Tatil yap") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}
