@file:OptIn(ExperimentalMaterial3Api::class)

package com.deveciahmet.devamsizlik

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Arşivlenmiş dönemlerin listesi. */
@Composable
fun ArchivesScreen(snap: Snapshot, nav: Nav) {
    val ctx = LocalContext.current
    val list = remember(snap) { Archive.list(ctx) }
    Scaffold(topBar = { BackBar("Geçmiş dönemler", nav::pop) }) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (list.isEmpty()) item(key = "none") { Hint("Arşivlenmiş dönem yok.") }
            items(list, key = { it.id }) { a ->
                SettingRow(
                    a.name,
                    "${rangeText(a.start, semesterEnd(a.start, a.weeks))} • ${a.weeks} hafta • ${a.courseCount} ders",
                ) { nav.push(Screen.ArchiveDetail(a.id)) }
            }
        }
    }
}

/** Arşivlenmiş bir dönemin özeti: toplamlar, derslerin son durumu ve sınavlar. */
@Composable
fun ArchiveDetailScreen(snap0: Snapshot, id: String, nav: Nav) {
    val ctx = LocalContext.current
    val snap = remember(snap0, id) { Archive.snapshot(ctx, id) }
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = {
        BackBar(snap?.termName ?: "Dönem", nav::pop) {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Diğer") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Bu dönemi geri yükle") }, onClick = { menu = false; confirm = "restore" })
                DropdownMenuItem(text = { Text("Arşivden sil", color = Red) }, onClick = { menu = false; confirm = "delete" })
            }
        }
    }) { pad ->
        if (snap == null) {
            Text("Dönem bulunamadı.", Modifier.padding(pad).padding(16.dp))
            return@Scaffold
        }
        val totals = remember(snap) { termTotals(snap) }
        val sorted = remember(snap) { snap.stats.sortedWith(compareBy<CourseStats>({ it.remaining }, { it.code })) }
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "summary") {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Hint("${rangeText(snap.semesterStart, snap.endDate)} • ${snap.weeks} hafta")
                        Row(Modifier.fillMaxWidth()) {
                            StatTile("Girdin", "${totals.attended}", Modifier.weight(1f), Green)
                            StatTile("Girmedin", "${totals.absent}", Modifier.weight(1f), Red)
                            StatTile("Yapılmadı", "${totals.cancelled}", Modifier.weight(1f))
                            StatTile("Ders", "${totals.courses}", Modifier.weight(1f))
                        }
                        val attendedPct = if (totals.attended + totals.absent > 0) {
                            totals.attended * 100.0 / (totals.attended + totals.absent)
                        } else 0.0
                        Text("Derslerin ${pct(attendedPct)}'ine girdin.", style = MaterialTheme.typography.bodyMedium)
                        if (totals.failed > 0) {
                            Text("⛔ ${totals.failed} derste devamsızlık sınırı aşıldı.", color = Red, fontWeight = FontWeight.SemiBold)
                        } else {
                            Text("Bütün derslerde devamsızlık sınırının altında kaldın ✓", color = Green, fontWeight = FontWeight.SemiBold)
                        }
                        if (totals.unanswered > 0) Hint("${totals.unanswered} yoklama işaretlenmeden kaldı.")
                    }
                }
            }
            item(key = "courses") { SectionTitle("Dersler", Modifier.padding(top = 4.dp)) }
            items(sorted, key = { "c_${it.code}" }) { s -> CourseCard(s, snap.notify.warnThreshold) }
            if (snap.exams.isNotEmpty()) {
                item(key = "exams") { SectionTitle("Sınavlar", Modifier.padding(top = 4.dp)) }
                items(snap.exams.sortedBy { it.date }, key = { "e_${it.id}" }) { e ->
                    Column {
                        Text(snap.examTitle(e), fontWeight = FontWeight.SemiBold)
                        Hint(examWhen(e))
                    }
                }
            }
            item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
        }
    }

    when (confirm) {
        "restore" -> ConfirmDialog(
            title = "Bu dönem geri yüklensin mi?",
            text = "${snap?.termName} tekrar şu anki dönem olur. Şu anki dönemde kayıt varsa o da arşivlenir; " +
                "hiçbir şey silinmez.",
            confirm = "Geri yükle",
            onDismiss = { confirm = null },
        ) {
            confirm = null
            Archive.restore(ctx, id)
            Scheduler.reschedule(ctx, resetCursor = true)
            Toast.makeText(ctx, "Dönem geri yüklendi", Toast.LENGTH_SHORT).show()
            nav.tab(Screen.Home)
        }
        "delete" -> ConfirmDialog(
            title = "Arşivden silinsin mi?",
            text = "${snap?.termName} ve bütün kayıtları kalıcı olarak silinir. Önce yedek almak isteyebilirsin.",
            confirm = "Sil",
            onDismiss = { confirm = null },
        ) {
            confirm = null
            Archive.delete(ctx, id)
            nav.pop()
        }
    }
}
