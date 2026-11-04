@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.deveciahmet.devamsizlik

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * courseCode == null → "Geçmiş dersleri doldur" (bütün derslerin geçmiş yoklamaları)
 * courseCode != null → o dersin bütün dönemi (geçmiş + gelecek)
 */
@Composable
fun SessionsScreen(snap: Snapshot, courseCode: String?, nav: Nav) {
    val ctx = LocalContext.current
    var onlyUnanswered by rememberSaveable(courseCode) { mutableStateOf(courseCode == null) }
    var confirmAll by remember { mutableStateOf(false) }
    val now = snap.now

    // Hepsi snap'e (kimliğine) bağlı: yoklama listesi aynı kalsa bile cevaplar değişince yeniden hesaplanmalı.
    val scope = remember(snap, courseCode) {
        snap.polls.filter { if (courseCode == null) !it.time.isAfter(now) else it.code == courseCode }
    }
    val unansweredPolls = remember(snap, courseCode) {
        scope.filter { !it.time.isAfter(now) && snap.status(it.key) == null }
    }
    val shown = if (onlyUnanswered) unansweredPolls else scope
    val groups = remember(snap, courseCode, onlyUnanswered) {
        shown.groupBy { it.session.key }.values.map { it.first().session to it }
    }
    val stat = courseCode?.let { snap.stat(it) }
    val title = if (courseCode == null) "Geçmiş dersleri doldur" else snap.title(courseCode)

    val onSet: (List<String>, Status?) -> Unit = { keys, st ->
        Repo.setStatuses(ctx, keys, st)
        if (st != null) keys.forEach { Notifier.cancelPoll(ctx, it) }
        Scheduler.reschedule(ctx)
    }

    Scaffold(topBar = {
        BackBar(title, nav::pop) {
            if (courseCode != null) {
                IconButton(onClick = { nav.push(Screen.CourseEdit(courseCode)) }) { Icon(Icons.Default.Edit, "Ders ayarları") }
            }
        }
    }) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (courseCode != null && stat != null) {
                item(key = "stat") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        CourseCard(stat, snap.notify.warnThreshold)
                        Hint(ruleSummary(stat, snap.config(courseCode), snap.requiredPercent))
                        TextButton(onClick = { nav.push(Screen.CourseEdit(courseCode)) }) {
                            Text("Ders adı, devam şartı ve yoklama sayısını değiştir")
                        }
                    }
                }
            }
            item(key = "switch") {
                SwitchRow("Sadece işaretlenmemişler", onlyUnanswered) { onlyUnanswered = it }
            }
            if (unansweredPolls.isNotEmpty()) {
                item(key = "bulk") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(onClick = { confirmAll = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("İşaretlenmemiş ${unansweredPolls.size} yoklamanın hepsini \"Girdim\" yap")
                        }
                        Hint("İpucu: Önce hepsini \"Girdim\" yap, sonra sadece girmediklerini \"Girmedim\" olarak değiştir.")
                    }
                }
            }
            if (groups.isEmpty()) {
                item(key = "empty") {
                    Text(
                        if (onlyUnanswered) "İşaretlenmemiş geçmiş ders yok 🎉" else "Ders yok",
                        Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            var lastWeek = Int.MIN_VALUE
            for ((session, polls) in groups) {
                if (session.week != lastWeek) {
                    lastWeek = session.week
                    val week = session.week
                    item(key = "w$week") { WeekHeader(snap, week) }
                }
                item(key = session.key) {
                    SessionItem(session, polls, snap, showCode = courseCode == null, onSet = onSet)
                }
            }
            item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (confirmAll) {
        ConfirmDialog(
            title = "Emin misin?",
            text = "İşaretlenmemiş ${unansweredPolls.size} geçmiş yoklama \"Girdim\" olarak kaydedilecek. " +
                "Sonradan tek tek değiştirebilirsin.",
            confirm = "Evet",
            onDismiss = { confirmAll = false },
        ) {
            onSet(unansweredPolls.map { it.key }, Status.ATTENDED)
            confirmAll = false
        }
    }
}

@Composable
private fun WeekHeader(snap: Snapshot, week: Int) {
    val monday = mondayOf(snap.semesterStart).plusWeeks((week - 1).toLong())
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            "Hafta $week  (${monday.format(dateFmt)} – ${monday.plusDays(6).format(dateFmt)})",
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
        )
        HorizontalDivider()
    }
}

@Composable
private fun SessionItem(
    session: Session,
    polls: List<Poll>,
    snap: Snapshot,
    showCode: Boolean,
    onSet: (List<String>, Status?) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val head = buildString {
            if (showCode) append(snap.title(session.slot.code)).append("  •  ")
            append("${dayShortName(session.slot.day)} ${session.date.format(dateFmt)}  ")
            append("${session.slot.start.format(timeFmt)}–${session.slot.end.format(timeFmt)}")
        }
        Text(head, fontWeight = if (showCode) FontWeight.SemiBold else FontWeight.Normal)

        val allFuture = polls.all { it.time.isAfter(snap.now) }
        if (allFuture) {
            // Henüz yapılmamış ders: sadece "yapılmayacak" (tatil, iptal) olarak işaretlenebilir
            val allCancelled = polls.all { snap.status(it.key) == Status.CANCELLED }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusChip("Ders yapılmadı", allCancelled, Color.Gray) {
                    onSet(polls.map { it.key }, if (allCancelled) null else Status.CANCELLED)
                }
                if (!allCancelled) {
                    Hint("Henüz yapılmadı")
                }
            }
        } else {
            polls.forEach { p ->
                PollRow(p, snap.status(p.key), past = !p.time.isAfter(snap.now)) { st -> onSet(listOf(p.key), st) }
            }
        }
    }
}

@Composable
private fun PollRow(p: Poll, status: Status?, past: Boolean, onSet: (Status?) -> Unit) {
    fun toggle(s: Status) = onSet(if (status == s) null else s)
    Column {
        if (p.count > 1) {
            Text(
                "${p.index}. saat · ${p.time.toLocalTime().format(timeFmt)}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (past) {
                StatusChip("Girdim", status == Status.ATTENDED, Green) { toggle(Status.ATTENDED) }
                StatusChip("Girmedim", status == Status.ABSENT, Red) { toggle(Status.ABSENT) }
            }
            StatusChip("Yapılmadı", status == Status.CANCELLED, Color.Gray) { toggle(Status.CANCELLED) }
            if (!past && status == null) {
                Text("Henüz sorulmadı", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.CenterVertically))
            }
        }
    }
}
