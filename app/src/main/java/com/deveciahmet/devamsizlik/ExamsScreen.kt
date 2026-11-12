@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.deveciahmet.devamsizlik

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalTime

private const val OTHER = "\u0000diger"

@Composable
fun ExamsScreen(snap: Snapshot, nav: Nav) {
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf<Exam?>(null) }
    var adding by remember { mutableStateOf(false) }

    TabScaffold(
        nav, "Sınavlar",
        fab = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                // Metin erişilebilirlik ağacında görünmediği için ad simgede
                icon = { Icon(Icons.Default.Add, "Sınav ekle") },
                text = { Text("Sınav ekle") },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (snap.exams.isEmpty()) {
                item(key = "empty") {
                    InfoCard(
                        "Henüz sınav eklemedin",
                        "Vize, final ve quiz tarihlerini ekle; her sınava kaç gün kaldığını burada ve ana ekran " +
                            "widget'ında görürsün. Sınavdan önceki akşam da hatırlatılır.",
                    )
                }
            }
            items(snap.upcomingExams, key = { "u_${it.id}" }) { e ->
                ExamCard(snap, e) { editing = e }
            }
            if (snap.pastExams.isNotEmpty()) {
                item(key = "past_title") { SectionTitle("Geçmiş sınavlar", Modifier.padding(top = 8.dp)) }
                items(snap.pastExams, key = { "p_${it.id}" }) { e ->
                    ExamCard(snap, e, Modifier.alpha(0.6f)) { editing = e }
                }
            }
            item(key = "bottom") { Spacer(Modifier.height(80.dp)) }
        }
    }

    if (adding || editing != null) {
        ExamDialog(
            snap = snap,
            initial = editing,
            onDismiss = { adding = false; editing = null },
            onDelete = editing?.let { e ->
                {
                    Repo.removeExam(ctx, e.id)
                    Scheduler.reschedule(ctx)
                    editing = null
                }
            },
        ) { exam ->
            Repo.saveExam(ctx, exam)
            Scheduler.reschedule(ctx)
            adding = false
            editing = null
        }
    }
}

@Composable
private fun ExamCard(snap: Snapshot, e: Exam, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            DaysBadge(daysUntil(snap.today, e.date))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(snap.examTitle(e), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Hint(examWhen(e))
            }
        }
    }
}

/** Sınav ekleme / düzenleme penceresi. */
@Composable
private fun ExamDialog(snap: Snapshot, initial: Exam?, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (Exam) -> Unit) {
    val ctx = LocalContext.current
    val codes = snap.courseCodes
    var code by remember { mutableStateOf(initial?.code?.takeIf { it.isNotBlank() } ?: codes.firstOrNull() ?: OTHER) }
    var otherName by remember { mutableStateOf(if (initial != null && initial.code.isBlank()) initial.title else "") }
    var title by remember { mutableStateOf(if (initial != null && initial.code.isNotBlank()) initial.title else "Vize") }
    var date by remember { mutableStateOf(initial?.date ?: snap.today.plusDays(7)) }
    var time by remember { mutableStateOf(initial?.time) }
    var place by remember { mutableStateOf(initial?.place.orEmpty()) }
    var menu by remember { mutableStateOf(false) }

    val isOther = code == OTHER
    val finalTitle = if (isOther) otherName.trim() else title.trim()
    val valid = finalTitle.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Sınav ekle" else "Sınavı düzenle") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box {
                    OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("Ders", style = MaterialTheme.typography.labelSmall)
                            Text(if (isOther) "Diğer (programda olmayan)" else snap.title(code), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        (codes + OTHER).forEach { c ->
                            DropdownMenuItem(
                                text = { Text(if (c == OTHER) "Diğer (programda olmayan)" else snap.title(c), maxLines = 2) },
                                onClick = {
                                    code = c
                                    menu = false
                                },
                            )
                        }
                    }
                }
                if (isOther) {
                    OutlinedTextField(otherName, { otherName = it }, label = { Text("Sınav adı") },
                        placeholder = { Text("örn. İngilizce yeterlik") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(title, { title = it }, label = { Text("Sınav") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Vize", "2. Vize", "Final", "Quiz", "Bütünleme").forEach { t ->
                            FilterChip(selected = title == t, onClick = { title = t }, label = { Text(t) })
                        }
                    }
                }
                OutlinedButton(onClick = { pickDate(ctx, date) { date = it } }, modifier = Modifier.fillMaxWidth()) {
                    Text("Tarih: ${date.format(dateDayFmt)}")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { pickTime(ctx, time ?: LocalTime.of(9, 30)) { time = it } },
                        modifier = Modifier.weight(1f),
                    ) { Text(time?.let { "Saat: ${it.format(timeFmt)}" } ?: "Saat ekle (isteğe bağlı)") }
                    if (time != null) IconButton(onClick = { time = null }) { Icon(Icons.Default.Close, "Saati kaldır") }
                }
                OutlinedTextField(place, { place = it }, label = { Text("Yer (isteğe bağlı)") },
                    placeholder = { Text("örn. MKB A501") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val days = daysUntil(snap.today, date)
                Hint(
                    when {
                        days >= 2 -> "Sınava $days gün kaldı."
                        days == 1L -> "Sınav yarın."
                        days == 0L -> "Sınav bugün."
                        else -> "Bu tarih geçti."
                    }
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(
                    Exam(
                        id = initial?.id ?: Repo.newExamId(),
                        code = if (isOther) "" else code,
                        title = finalTitle,
                        date = date,
                        time = time,
                        place = place.trim(),
                    )
                )
            }) { Text("Kaydet") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Sil", color = Red) }
                TextButton(onClick = onDismiss) { Text("Vazgeç") }
            }
        },
    )
}
