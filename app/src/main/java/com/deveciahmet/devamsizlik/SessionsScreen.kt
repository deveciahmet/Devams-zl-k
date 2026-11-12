@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.deveciahmet.devamsizlik

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.max

private sealed interface SessionListRow {
    val key: String
}

private data class WeekRow(val week: Int) : SessionListRow {
    override val key: String get() = "w$week"
}

private data class SessionRow(val session: Session, val polls: List<Poll>) : SessionListRow {
    override val key: String get() = session.key
}

/**
 * courseCode == null → "Geçmiş dersleri doldur" (bütün derslerin işaretlenmemiş geçmiş yoklamaları)
 * courseCode != null → o dersin bütün dönemi; açılınca [focus] dersine (yoksa bu haftaya) kaydırılır.
 */
@Composable
fun SessionsScreen(snap: Snapshot, courseCode: String?, nav: Nav, focus: String? = null) {
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
    val rows = remember(snap, courseCode, onlyUnanswered) {
        val shown = if (onlyUnanswered) unansweredPolls else scope
        val out = ArrayList<SessionListRow>()
        var lastWeek = Int.MIN_VALUE
        for ((_, polls) in shown.groupBy { it.session.key }) {
            val s = polls.first().session
            if (s.week != lastWeek) {
                lastWeek = s.week
                out += WeekRow(s.week)
            }
            out += SessionRow(s, polls)
        }
        out
    }
    val stat = courseCode?.let { snap.stat(it) }
    val title = if (courseCode == null) "Geçmiş dersleri doldur" else snap.title(courseCode)

    val onSet: (List<String>, Status?) -> Unit = { keys, st ->
        Repo.setStatuses(ctx, keys, st)
        if (st != null) keys.forEach { Notifier.cancelPoll(ctx, it) }
        Scheduler.reschedule(ctx)
    }

    // Listede satırlardan önceki öğeler (kaydırma için sayılıyor)
    val showStat = courseCode != null && stat != null
    val showBulk = unansweredPolls.isNotEmpty()
    val headerCount = (if (showStat) 1 else 0) + 1 + (if (showBulk) 1 else 0) + (if (rows.isEmpty()) 1 else 0)
    val listState = rememberLazyListState()
    LaunchedEffect(courseCode, focus) {
        if (courseCode == null) return@LaunchedEffect
        val target = focus?.let { f -> rows.indexOfFirst { it.key == f } }?.takeIf { it >= 0 }?.let { max(0, it - 1) }
            ?: rows.indexOfFirst { it is WeekRow && it.week >= snap.currentWeek }.takeIf { it >= 0 }
            ?: return@LaunchedEffect
        listState.scrollToItem(headerCount + target)
    }

    Scaffold(topBar = {
        BackBar(title, nav::pop) {
            if (courseCode != null) {
                IconButton(onClick = { nav.push(Screen.CourseEdit(courseCode)) }) { Icon(Icons.Default.Edit, "Ders ayarları") }
            }
        }
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            // Özet hep üstte kalır: liste bu haftaya kaydırılmış açılsa da kalan hak görünür
            if (showStat) CourseStatsBar(stat, snap)
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (showStat) {
                    item(key = "stat") {
                        CourseRuleCard(stat, snap, courseCode) { nav.push(Screen.CourseEdit(courseCode)) }
                    }
                }
                item(key = "switch") {
                    SwitchRow("Sadece işaretlenmemişler", onlyUnanswered) { onlyUnanswered = it }
                }
                if (showBulk) {
                    item(key = "bulk") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedButton(onClick = { confirmAll = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("İşaretlenmemiş ${unansweredPolls.size} yoklamanın hepsini \"Girdim\" yap")
                            }
                            Hint("İpucu: Önce hepsini \"Girdim\" yap, sonra sadece girmediklerini \"Girmedim\" olarak değiştir.")
                        }
                    }
                }
                if (rows.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            if (onlyUnanswered) "İşaretlenmemiş geçmiş ders yok 🎉" else "Ders yok",
                            Modifier.padding(vertical = 24.dp),
                        )
                    }
                }
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is WeekRow -> WeekHeader(snap, row.week)
                        is SessionRow -> SessionItem(
                            row.session, row.polls, snap,
                            showCode = courseCode == null,
                            highlighted = row.session.key == focus,
                            onSet = onSet,
                        )
                    }
                }
                item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
            }
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

/** Dersin özeti (sabit üst şerit): kalan hak, devamsızlık, girilen ve kalan ders sayısı. */
@Composable
private fun CourseStatsBar(s: CourseStats, snap: Snapshot) {
    val c = riskColor(s, snap.notify.warnThreshold)
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth()) {
                StatTile(remainingCaption(s).replaceFirstChar { it.uppercase(TR) }, "${max(0, s.remaining)}", Modifier.weight(1f), c)
                StatTile("Devamsızlık", "${s.absent}/${s.allowed}", Modifier.weight(1f))
                StatTile("Girdin", "${s.attended}", Modifier.weight(1f))
                StatTile("Kalan ${s.unit}", "${s.upcoming}", Modifier.weight(1f))
            }
            RiskBar(s, c)
        }
    }
}

/** Uyarı ve devam kuralı; ayarlara geçiş. */
@Composable
private fun CourseRuleCard(s: CourseStats, snap: Snapshot, code: String, onSettings: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                s.remaining < 0 -> Text("Devamsızlık sınırını ${-s.remaining} ${s.unit} aştın!", color = Red, fontWeight = FontWeight.Bold)
                s.remaining == 0 -> Text("Hakkın bitti! Bir kez daha girmezsen devamsızlıktan kalırsın.", color = Red,
                    fontWeight = FontWeight.Bold)
                s.remaining <= max(1, snap.notify.warnThreshold) -> Text("Son ${s.remaining} hakkın kaldı.", color = Orange,
                    fontWeight = FontWeight.SemiBold)
            }
            Hint(buildString {
                if (s.name.isNotBlank()) append("${s.code} • ")
                append(ruleSummary(s, snap.config(code), snap.requiredPercent))
                if (s.cancelled > 0) append(" • Yapılmadı:\u00A0${s.cancelled}")
                if (s.unanswered > 0) append(" • İşaretsiz:\u00A0${s.unanswered}")
            })
            TextButton(onClick = onSettings, contentPadding = PaddingValues(0.dp)) {
                Text("Ders adı, saatleri, devam şartı ve yoklama sayısı")
            }
        }
    }
}

@Composable
private fun WeekHeader(snap: Snapshot, week: Int) {
    val monday = mondayOf(snap.semesterStart).plusWeeks((week - 1).toLong())
    val current = week == snap.currentWeek
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            "Hafta $week  (${monday.format(dateFmt)} – ${monday.plusDays(6).format(dateFmt)})" + if (current) "  • bu hafta" else "",
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
            color = if (current) MaterialTheme.colorScheme.primary else Color.Unspecified,
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
    highlighted: Boolean,
    onSet: (List<String>, Status?) -> Unit,
) {
    val bg = if (highlighted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else Color.Transparent
    Column(
        Modifier.fillMaxWidth().testTag("session_${session.key}")
            .background(bg, RoundedCornerShape(12.dp)).padding(vertical = 4.dp, horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (showCode) {
                    Text(snap.title(session.slot.code), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    "${dayShortName(session.slot.day)} ${session.date.format(dateFmt)} • ${timeRange(session.slot.start, session.slot.end)}",
                    style = if (showCode) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge,
                )
            }
            if (session.date in snap.holidays) {
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text("Tatil", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
                }
            }
        }

        if (polls.all { it.time.isAfter(snap.now) }) {
            // Henüz yapılmamış ders: sadece "yapılmayacak" (iptal) olarak işaretlenebilir
            val allCancelled = polls.all { snap.status(it.key) == Status.CANCELLED }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = allCancelled,
                    onClick = { onSet(polls.map { it.key }, if (allCancelled) null else Status.CANCELLED) },
                    label = { Text("Ders yapılmayacak") },
                )
                if (!allCancelled) Hint("Henüz yapılmadı")
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
    Column {
        if (p.count > 1) {
            Text(
                "${p.index}. saat · ${p.time.toLocalTime().format(timeFmt)}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 2.dp, bottom = 2.dp),
            )
        }
        if (!past) {
            Hint("Henüz sorulmadı")
            return
        }
        val options = listOf(Status.ATTENDED to "Girdim", Status.ABSENT to "Girmedim", Status.CANCELLED to "Yapılmadı")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (st, label) ->
                val color = when (st) {
                    Status.ATTENDED -> Green
                    Status.ABSENT -> Red
                    Status.CANCELLED -> Color.Gray
                }
                SegmentedButton(
                    selected = status == st,
                    onClick = { onSet(if (status == st) null else st) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    colors = SegmentedButtonDefaults.colors(activeContainerColor = color, activeContentColor = Color.White),
                    icon = {},
                    label = { Text(label, maxLines = 1) },
                )
            }
        }
    }
}
