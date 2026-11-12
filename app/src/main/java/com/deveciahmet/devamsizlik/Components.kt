@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.deveciahmet.devamsizlik

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.LocalTime
import java.util.Calendar
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

fun pickDate(ctx: Context, initial: LocalDate, onPick: (LocalDate) -> Unit) {
    DatePickerDialog(ctx, { _, y, m, d -> onPick(LocalDate.of(y, m + 1, d)) }, initial.year, initial.monthValue - 1, initial.dayOfMonth)
        .apply { datePicker.firstDayOfWeek = Calendar.MONDAY }
        .show()
}

/** "Devam şartı %70 (varsayılan) • Dönemde 13 ders • Hak: 3" */
fun ruleSummary(s: CourseStats, cfg: CourseConfig, defaultRequired: Int): String {
    val rule = when (cfg.mode) {
        LimitMode.DEFAULT -> "Devam şartı %$defaultRequired (varsayılan)"
        LimitMode.PERCENT -> "Devam şartı %${cfg.requiredPercent}"
        LimitMode.COUNT -> "Gelmeme hakkı: ${cfg.allowedCount} ${s.unit}"
    }
    val sessions = if (s.manualTotal) "Dönemde ${cfg.totalSessions} ders (elle)" else "Dönemde ${s.sessionCount} ders"
    val polls = if (s.pollsPerSession > 1) " × ${s.pollsPerSession} yoklama" else ""
    return "$rule • $sessions$polls • Hak: ${s.allowed}"
}

// ---------------- İskelet ----------------

@Composable
fun BackBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") } },
        actions = actions,
    )
}

private data class Tab(val screen: Screen, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab(Screen.Home, "Dersler", Icons.Default.Home),
    Tab(Screen.Exams, "Sınavlar", Icons.Default.DateRange),
    Tab(Screen.Settings, "Ayarlar", Icons.Default.Settings),
)

@Composable
fun BottomBar(nav: Nav) {
    NavigationBar {
        TABS.forEach { t ->
            NavigationBarItem(
                selected = nav.current == t.screen,
                onClick = { nav.tab(t.screen) },
                icon = { Icon(t.icon, null) },
                label = { Text(t.label) },
            )
        }
    }
}

/** Ana sekmelerin (Dersler, Sınavlar, Ayarlar) iskeleti: üstte başlık, altta sekme çubuğu. */
@Composable
fun TabScaffold(
    nav: Nav,
    title: String,
    actions: @Composable RowScope.() -> Unit = {},
    fab: @Composable () -> Unit = {},
    snackbar: SnackbarHostState? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, actions = actions) },
        bottomBar = { BottomBar(nav) },
        floatingActionButton = fab,
        snackbarHost = { if (snackbar != null) SnackbarHost(snackbar) },
        content = content,
    )
}

// ---------------- Küçük parçalar ----------------

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = modifier)
}

@Composable
fun Hint(text: String, color: Color = Color.Unspecified, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color,
        modifier = modifier,
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

/**
 * Hemen kaydedilen sayı ayarı. Yazılan metin kutunun kendi durumunda tutulur: kayıt sonrası ekran yenilense bile
 * kullanıcı yazarken kutu sıfırlanmaz. Kutudan çıkınca geçersiz bir değer kaldıysa kayıtlı değere döner.
 */
@Composable
fun NumberSetting(label: String, value: Int, range: IntRange, modifier: Modifier = Modifier, onCommit: (Int) -> Unit) {
    var text by remember(label) { mutableStateOf(value.toString()) }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    // Değer dışarıdan değiştiyse (başka bir ayar, yedekten geri yükleme) ve kullanıcı yazmıyorsa göster
    LaunchedEffect(value) { if (!focused) text = value.toString() }
    val valid = text.toIntOrNull()?.let { it in range } == true
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v.filter(Char::isDigit).take(3)
            text.toIntOrNull()?.takeIf { it in range && it != value }?.let(onCommit)
        },
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        // Dönem başlamadan "Şu anki hafta" 0 olabilir: hata görünümü sadece yazarken
        isError = focused && !valid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        modifier = modifier.onFocusChanged { f ->
            if (focused && !f.isFocused && !valid) text = value.toString()
            focused = f.isFocused
        },
    )
}

/** Satırın tamamına dokununca açılıp kapanan anahtar. */
@Composable
fun SwitchRow(title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .toggleable(value = checked, onValueChange = onChange, role = Role.Switch)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (subtitle != null) Hint(subtitle)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Ayarlar listesindeki dokunulabilir satır. */
@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    titleColor: Color = Color.Unspecified,
    trailing: (@Composable () -> Unit)? = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = titleColor)
            if (!subtitle.isNullOrBlank()) Hint(subtitle)
        }
        trailing?.invoke()
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
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
fun RiskBar(s: CourseStats, color: Color, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { if (s.allowed <= 0) (if (s.absent > 0) 1f else 0f) else (s.absent.toFloat() / s.allowed).coerceIn(0f, 1f) },
        color = color,
        trackColor = color.copy(alpha = 0.18f),
        gapSize = 0.dp,
        drawStopIndicator = {},
        modifier = modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
    )
}

fun remainingCaption(s: CourseStats): String = when {
    s.remaining < 0 -> "sınır aşıldı"
    s.remaining == 0 -> "hak kalmadı"
    else -> "hak kaldı"
}

/** Ana ekrandaki sade ders kartı: ad, devamsızlık çubuğu ve kalan hak. */
@Composable
fun CourseCard(s: CourseStats, threshold: Int, onClick: (() -> Unit)? = null) {
    val c = riskColor(s, threshold)
    val base = Modifier.fillMaxWidth()
    Card(
        modifier = if (onClick != null) base.clickable(onClick = onClick) else base,
        colors = if (s.exhausted) CardDefaults.cardColors(containerColor = Red.copy(alpha = 0.10f)) else CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (s.name.isNotBlank()) Hint(s.code)
                Spacer(Modifier.height(8.dp))
                RiskBar(s, c)
                Spacer(Modifier.height(4.dp))
                Hint(buildString {
                    append("${s.absent}/${s.allowed} devamsızlık")
                    if (s.unanswered > 0) append("  •  ${s.unanswered} işaretsiz")
                })
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.widthIn(min = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${max(0, s.remaining)}", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = c)
                Text(remainingCaption(s), style = MaterialTheme.typography.labelSmall, color = c)
            }
        }
    }
}

/** Sınava kalan gün rozeti: "5 gün", "Yarın", "Bugün". */
@Composable
fun DaysBadge(days: Long, modifier: Modifier = Modifier) {
    val (big, small) = when {
        days >= 2 -> "$days" to "gün"
        days == 1L -> "Yarın" to ""
        days == 0L -> "Bugün" to ""
        else -> "✓" to "geçti"
    }
    val container = when {
        days < 0 -> MaterialTheme.colorScheme.surfaceVariant
        days <= 2 -> MaterialTheme.colorScheme.errorContainer
        days <= 7 -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    Surface(shape = RoundedCornerShape(14.dp), color = container, modifier = modifier.size(60.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(big, fontWeight = FontWeight.Bold,
                style = if (big.length > 3) MaterialTheme.typography.titleSmall else MaterialTheme.typography.headlineSmall)
            if (small.isNotEmpty()) Text(small, style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ---------------- Ders saati penceresi ----------------

data class SlotDraft(
    val code: String = "",
    val name: String = "",
    val day: Int = 1,
    val start: LocalTime = LocalTime.of(8, 30),
    val end: LocalTime = LocalTime.of(10, 29),
    val place: String = "",
)

@Composable
fun SlotDialog(
    title: String,
    initial: SlotDraft,
    showName: Boolean,
    showCode: Boolean = true,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onSave: (SlotDraft) -> Unit,
) {
    val ctx = LocalContext.current
    var code by remember { mutableStateOf(initial.code) }
    var name by remember { mutableStateOf(initial.name) }
    var day by remember { mutableIntStateOf(initial.day) }
    var start by remember { mutableStateOf(initial.start) }
    var end by remember { mutableStateOf(initial.end) }
    var place by remember { mutableStateOf(initial.place) }
    val validTime = end.isAfter(start)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showCode) {
                    OutlinedTextField(code, { code = it }, label = { Text("Ders kodu (örn. MAK 335)") }, singleLine = true)
                }
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
                OutlinedTextField(place, { place = it }, label = { Text("Derslik (isteğe bağlı)") },
                    placeholder = { Text("örn. MKB A501") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = (!showCode || code.isNotBlank()) && validTime, onClick = {
                onSave(SlotDraft(code.trim(), name.trim(), day, start, end, place.trim()))
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

/** Boş liste ya da bilgi kartı. */
@Composable
fun InfoCard(title: String, text: String, modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Hint(text)
            content()
        }
    }
}
