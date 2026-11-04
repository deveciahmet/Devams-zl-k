@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.deveciahmet.devamsizlik

import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalTime
import kotlin.math.max

val Red = Color(0xFFC62828)
val Orange = Color(0xFFEF6C00)
val Green = Color(0xFF2E7D32)

fun riskColor(s: CourseStats, threshold: Int): Color = when {
    s.remaining <= 0 -> Red
    s.remaining <= max(1, threshold) -> Orange
    else -> Green
}

fun pct(d: Double): String = String.format(TR, "%%%.1f", d)

fun pickTime(ctx: Context, initial: LocalTime, onPick: (LocalTime) -> Unit) {
    TimePickerDialog(ctx, { _, h, m -> onPick(LocalTime.of(h, m)) }, initial.hour, initial.minute, true).show()
}

/** "Devam şartı %70 (varsayılan) • Dönemde 13 ders (programdan) • Toplam 13 ders" */
fun ruleSummary(s: CourseStats, cfg: CourseConfig, defaultRequired: Int): String {
    val rule = when (cfg.mode) {
        LimitMode.DEFAULT -> "Devam şartı %$defaultRequired (varsayılan)"
        LimitMode.PERCENT -> "Devam şartı %${cfg.requiredPercent}"
        LimitMode.COUNT -> "Gelmeme hakkı: ${cfg.allowedCount} ${s.unit}"
    }
    val sessions = if (s.manualTotal) "Dönemde ${cfg.totalSessions} ders (elle)" else "Dönemde ${s.sessionCount} ders"
    val polls = if (s.pollsPerSession > 1) " × ${s.pollsPerSession} yoklama" else ""
    return "$rule • $sessions$polls • Hak:\u00A0${s.allowed}"
}

@Composable
fun BackBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") } },
        actions = actions,
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
fun Hint(text: String, color: Color = Color.Unspecified) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color,
    )
}

@Composable
fun StatusChip(label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = color, selectedLabelColor = Color.White),
    )
}

@Composable
fun <T> ChoiceRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

@Composable
fun NumberField(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(3)) },
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
fun SwitchRow(title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (subtitle != null) Hint(subtitle)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}

@Composable
fun CourseCard(s: CourseStats, threshold: Int, onClick: (() -> Unit)? = null) {
    val c = riskColor(s, threshold)
    val base = Modifier.fillMaxWidth()
    Card(
        modifier = if (onClick != null) base.clickable(onClick = onClick) else base,
        colors = if (s.exhausted) CardDefaults.cardColors(containerColor = Red.copy(alpha = 0.12f)) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (s.name.isNotBlank()) Hint(s.code)
                }
                Text(pct(s.absentPercent), color = c, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = {
                    if (s.allowed <= 0) (if (s.absent > 0) 1f else 0f) else (s.absent.toFloat() / s.allowed).coerceIn(0f, 1f)
                },
                color = c,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text("Devamsızlık:\u00A0${s.absent}\u00A0/\u00A0${s.allowed}\u00A0hak   •   Kalan\u00A0hak:\u00A0${max(0, s.remaining)}",
                color = c, fontWeight = FontWeight.SemiBold)
            Hint(buildString {
                append("Girdin:\u00A0${s.attended}  •  Kalan\u00A0${s.unit}:\u00A0${s.upcoming}  •  Toplam:\u00A0${s.total}")
                if (s.unanswered > 0) append("  •  İşaretsiz:\u00A0${s.unanswered}")
                if (s.cancelled > 0) append("  •  Yapılmadı:\u00A0${s.cancelled}")
                if (s.pollsPerSession > 1) append("\nHer derste ${s.pollsPerSession} yoklama alınıyor")
            })
            when {
                s.remaining < 0 -> Text("⚠ Devamsızlık sınırını ${-s.remaining} ${s.unit} aştın!",
                    color = Red, fontWeight = FontWeight.Bold)
                s.remaining == 0 -> Text("⚠ Hakkın bitti! Bir kez daha girmezsen devamsızlıktan kalırsın.",
                    color = Red, fontWeight = FontWeight.Bold)
                s.remaining <= max(1, threshold) -> Text("Son ${s.remaining} hakkın kaldı.",
                    color = Orange, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

data class SlotDraft(
    val code: String = "",
    val name: String = "",
    val day: Int = 1,
    val start: LocalTime = LocalTime.of(8, 30),
    val end: LocalTime = LocalTime.of(10, 29),
)

@Composable
fun SlotDialog(title: String, initial: SlotDraft, showName: Boolean, onDismiss: () -> Unit, onSave: (SlotDraft) -> Unit) {
    val ctx = LocalContext.current
    var code by remember { mutableStateOf(initial.code) }
    var name by remember { mutableStateOf(initial.name) }
    var day by remember { mutableIntStateOf(initial.day) }
    var start by remember { mutableStateOf(initial.start) }
    var end by remember { mutableStateOf(initial.end) }
    val validTime = end.isAfter(start)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(code, { code = it }, label = { Text("Ders kodu (örn. MAK 335)") }, singleLine = true)
                if (showName) {
                    OutlinedTextField(name, { name = it }, label = { Text("Ders adı (isteğe bağlı)") }, singleLine = true)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..7).forEach { d ->
                        FilterChip(selected = day == d, onClick = { day = d }, label = { Text(dayShortName(d)) })
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickTime(ctx, start) { start = it } }) { Text("Başlangıç ${start.format(timeFmt)}") }
                    OutlinedButton(onClick = { pickTime(ctx, end) { end = it } }) { Text("Bitiş ${end.format(timeFmt)}") }
                }
                if (!validTime) Hint("Bitiş saati başlangıçtan sonra olmalı.", Red)
            }
        },
        confirmButton = {
            TextButton(enabled = code.isNotBlank() && validTime, onClick = {
                onSave(SlotDraft(code.trim(), name.trim(), day, start, end))
            }) { Text("Kaydet") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}
