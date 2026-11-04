@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.deveciahmet.devamsizlik

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

private val Red = Color(0xFFC62828)
private val Orange = Color(0xFFEF6C00)
private val Green = Color(0xFF2E7D32)

private fun riskColor(s: CourseStats) = when {
    s.remaining <= 0 -> Red
    s.remaining == 1 -> Orange
    else -> Green
}

private fun pct(d: Double) = String.format(Locale("tr"), "%%%.1f", d)

@Composable
private fun BackBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") }
        },
    )
}

// ======================= ANA EKRAN =======================

@Composable
fun HomeScreen(version: Int, onFill: () -> Unit, onSettings: () -> Unit, onCourse: (String) -> Unit) {
    val ctx = LocalContext.current
    // version değiştikçe yeniden hesapla
    val data = remember(version) {
        val now = LocalDateTime.now()
        Triple(Repo.stats(ctx, now), Repo.unansweredPast(ctx, now).size, Alarms.nextSession(ctx, now))
    }
    val (stats, unanswered, next) = data
    val week = remember(version) { Repo.currentWeek(ctx) }
    val weeks = remember(version) { Repo.weeks(ctx) }
    val limit = remember(version) { Repo.limitPercent(ctx) }
    val sorted = stats.sortedWith(compareBy<CourseStats> { it.remaining }.thenBy { it.code })
    val exhausted = sorted.filter { it.exhausted }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Devamsızlık Takibi") },
            actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Ayarlar") } },
        )
    }) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        val label = when {
                            week < 1 -> "Dönem henüz başlamadı"
                            week > weeks -> "Dönem bitti"
                            else -> "Hafta $week / $weeks"
                        }
                        Text(label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { (week.coerceIn(0, weeks)).toFloat() / weeks },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text("Devamsızlık sınırı: $limit% (derslerin en az ${100 - limit}%'ine girmelisin)",
                            style = MaterialTheme.typography.bodySmall)
                        if (next != null) {
                            Text("Sonraki soru: ${next.slot.code} • ${dayShortName(next.slot.day)} " +
                                "${next.date.format(dateFmt)} ${next.slot.end.format(timeFmt)}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (unanswered > 0) {
                item {
                    Card(
                        Modifier.fillMaxWidth().clickable { onFill() },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("$unanswered geçmiş ders işaretlenmemiş",
                                    fontWeight = FontWeight.Bold)
                                Text("Önceki haftaları doldurmadan istatistikler eksik olur.",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Button(onClick = onFill) { Text("Doldur") }
                        }
                    }
                }
            }

            if (exhausted.isNotEmpty()) {
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Red, contentColor = Color.White),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, null)
                                Spacer(Modifier.width(8.dp))
                                Text("DİKKAT! Devamsızlık hakkın kalmadı",
                                    fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            }
                            Spacer(Modifier.height(8.dp))
                            exhausted.forEach {
                                val txt = if (it.remaining < 0)
                                    "• ${it.code}: sınırı ${-it.remaining} ders AŞTIN (${it.absent}/${it.allowed})"
                                else
                                    "• ${it.code}: hakkın bitti (${it.absent}/${it.allowed}) — bir daha kaçırma!"
                                Text(txt, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            item {
                Text("Dersler", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            items(sorted, key = { it.code }) { s -> CourseCard(s) { onCourse(s.code) } }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun CourseCard(s: CourseStats, onClick: () -> Unit) {
    val c = riskColor(s)
    Card(
        Modifier.fillMaxWidth().clickable { onClick() },
        colors = if (s.exhausted) CardDefaults.cardColors(containerColor = Red.copy(alpha = 0.12f))
        else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.code, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                Text(pct(s.absentPercent), color = c, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { if (s.allowed == 0) (if (s.absent > 0) 1f else 0f) else (s.absent.toFloat() / s.allowed).coerceIn(0f, 1f) },
                color = c,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text("Devamsızlık: ${s.absent} / ${s.allowed} hak   •   Kalan hak: ${s.remaining.coerceAtLeast(0)}",
                color = c, fontWeight = FontWeight.SemiBold)
            val extra = buildString {
                append("Girdin: ${s.attended}  •  Kalan ders: ${s.upcoming}")
                if (s.unanswered > 0) append("  •  İşaretsiz: ${s.unanswered}")
                if (s.cancelled > 0) append("  •  Yapılmadı: ${s.cancelled}")
            }
            Text(extra, style = MaterialTheme.typography.bodySmall)
            when {
                s.remaining < 0 -> Text("⚠ Devamsızlık sınırını aştın!", color = Red, fontWeight = FontWeight.Bold)
                s.remaining == 0 -> Text("⚠ Hakkın bitti! Bir kez daha girmezsen devamsızlıktan kalırsın.", color = Red, fontWeight = FontWeight.Bold)
                s.remaining == 1 -> Text("Son 1 hakkın kaldı.", color = Orange, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ======================= OTURUM LİSTESİ (Doldurma / Ders detayı) =======================

@Composable
fun SessionListScreen(version: Int, bump: () -> Unit, courseCode: String?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var onlyUnanswered by remember { mutableStateOf(courseCode == null) }
    var confirmAll by remember { mutableStateOf(false) }
    val now = remember(version) { LocalDateTime.now() }
    val all = remember(version) { Repo.sessions(ctx) }
    val statuses = remember(version) { all.associate { it.key to Repo.status(ctx, it.key) } }
    val stat = remember(version) { courseCode?.let { c -> Repo.stats(ctx, now).firstOrNull { it.code == c } } }

    val list = all.filter { s ->
        (courseCode == null || s.slot.code == courseCode) &&
            (courseCode != null || !s.end.isAfter(now)) &&
            (!onlyUnanswered || (statuses[s.key] == null && !s.end.isAfter(now)))
    }
    val unansweredCount = all.count {
        (courseCode == null || it.slot.code == courseCode) && !it.end.isAfter(now) && statuses[it.key] == null
    }

    Scaffold(topBar = { BackBar(courseCode ?: "Geçmiş dersleri doldur", onBack) }) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (stat != null) {
                item { CourseCard(stat) {} }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sadece işaretlenmemişler", Modifier.weight(1f))
                    Switch(checked = onlyUnanswered, onCheckedChange = { onlyUnanswered = it })
                }
            }
            if (unansweredCount > 0) {
                item {
                    OutlinedButton(onClick = { confirmAll = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("İşaretlenmemiş $unansweredCount dersin hepsini \"Girdim\" yap")
                    }
                    Text("İpucu: Önce hepsini \"Girdim\" yap, sonra sadece girmediklerini \"Girmedim\" olarak değiştir.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (list.isEmpty()) {
                item {
                    Text(if (onlyUnanswered) "İşaretlenmemiş geçmiş ders yok 🎉" else "Ders yok",
                        Modifier.padding(vertical = 24.dp))
                }
            }
            var lastWeek = -1
            for (s in list) {
                if (s.week != lastWeek) {
                    lastWeek = s.week
                    val monday = Repo.semesterStart(ctx).plusWeeks((s.week - 1).toLong())
                    item(key = "w${s.week}") {
                        Text("Hafta ${s.week}  (${monday.format(dateFmt)} – ${monday.plusDays(6).format(dateFmt)})",
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp))
                        HorizontalDivider()
                    }
                }
                item(key = s.key) {
                    SessionRow(s, statuses[s.key], past = !s.end.isAfter(now), showCode = courseCode == null) { st ->
                        Repo.setStatus(ctx, s.key, st)
                        if (st != null) {
                            androidx.core.app.NotificationManagerCompat.from(ctx).cancel(Notifier.notificationId(s))
                        }
                        Alarms.scheduleNext(ctx)
                        bump()
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("Emin misin?") },
            text = { Text("İşaretlenmemiş $unansweredCount geçmiş ders \"Girdim\" olarak kaydedilecek. Sonradan tek tek değiştirebilirsin.") },
            confirmButton = {
                TextButton(onClick = {
                    all.filter {
                        (courseCode == null || it.slot.code == courseCode) && !it.end.isAfter(now) && statuses[it.key] == null
                    }.forEach { Repo.setStatus(ctx, it.key, Status.ATTENDED) }
                    confirmAll = false
                    bump()
                }) { Text("Evet") }
            },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Vazgeç") } },
        )
    }
}

@Composable
private fun SessionRow(s: Session, status: Status?, past: Boolean, showCode: Boolean, onSet: (Status?) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        val title = buildString {
            if (showCode) append("${s.slot.code}  •  ")
            append("${dayShortName(s.slot.day)} ${s.date.format(dateFmt)}  ${s.slot.start.format(timeFmt)}–${s.slot.end.format(timeFmt)}")
        }
        Text(title, fontWeight = if (showCode) FontWeight.SemiBold else FontWeight.Normal)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (past) {
                StatusChip("Girdim", status == Status.ATTENDED, Green) { onSet(if (status == Status.ATTENDED) null else Status.ATTENDED) }
                StatusChip("Girmedim", status == Status.ABSENT, Red) { onSet(if (status == Status.ABSENT) null else Status.ABSENT) }
            }
            StatusChip("Ders yapılmadı", status == Status.CANCELLED, Color.Gray) {
                onSet(if (status == Status.CANCELLED) null else Status.CANCELLED)
            }
            if (!past && status == null) {
                Text("Henüz yapılmadı", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.CenterVertically))
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = color,
            selectedLabelColor = Color.White,
        ),
    )
}

// ======================= AYARLAR =======================

@Composable
fun SettingsScreen(version: Int, bump: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val start = remember(version) { Repo.semesterStart(ctx) }
    val slots = remember(version) { Repo.slots(ctx).sortedWith(compareBy({ it.day }, { it.start })) }
    var weekText by remember(version) { mutableStateOf(Repo.currentWeek(ctx).toString()) }
    var weeksText by remember(version) { mutableStateOf(Repo.weeks(ctx).toString()) }
    var limitText by remember(version) { mutableStateOf(Repo.limitPercent(ctx).toString()) }
    var showAdd by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    Scaffold(topBar = { BackBar("Ayarlar", onBack) }) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("Dönem", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item {
                OutlinedButton(onClick = {
                    DatePickerDialog(ctx, { _, y, m, d ->
                        Repo.setSemesterStart(ctx, LocalDate.of(y, m + 1, d)); Alarms.scheduleNext(ctx); bump()
                    }, start.year, start.monthValue - 1, start.dayOfMonth).show()
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("1. hafta başlangıcı: ${start.format(dateLongFmt)}")
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Şu anki hafta", weekText, Modifier.weight(1f)) {
                        weekText = it
                        it.toIntOrNull()?.takeIf { w -> w in 1..30 }?.let { w ->
                            Repo.setCurrentWeek(ctx, w); Alarms.scheduleNext(ctx)
                        }
                    }
                    NumberField("Toplam hafta", weeksText, Modifier.weight(1f)) {
                        weeksText = it
                        it.toIntOrNull()?.takeIf { w -> w in 1..30 }?.let { w ->
                            Repo.setWeeks(ctx, w); Alarms.scheduleNext(ctx)
                        }
                    }
                    NumberField("Sınır %", limitText, Modifier.weight(1f)) {
                        limitText = it
                        it.toIntOrNull()?.takeIf { p -> p in 0..100 }?.let { p -> Repo.setLimitPercent(ctx, p) }
                    }
                }
                Text("Sınır %: en fazla devamsızlık oranı (İTÜ'de %70 devam zorunluluğu → %30).",
                    style = MaterialTheme.typography.bodySmall)
            }

            item { HorizontalDivider() }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ders programı", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, "Ders ekle") }
                }
            }
            items(slots, key = { it.id }) { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.code, fontWeight = FontWeight.SemiBold)
                        Text("${dayLongName(s.day)}  ${s.start.format(timeFmt)}–${s.end.format(timeFmt)}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { Repo.removeSlot(ctx, s.id); Alarms.scheduleNext(ctx); bump() }) {
                        Icon(Icons.Default.Delete, "Sil")
                    }
                }
            }
            item {
                TextButton(onClick = { confirmReset = true }) { Text("Programı varsayılana (OBS) döndür") }
            }

            item { HorizontalDivider() }
            item { Text("Bildirimler", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item {
                Button(onClick = {
                    val now = LocalDateTime.now()
                    val s = Repo.sessions(ctx).lastOrNull { !it.end.isAfter(now) } ?: Repo.sessions(ctx).firstOrNull()
                    if (s != null) Notifier.askAttendance(ctx, s)
                }, modifier = Modifier.fillMaxWidth()) { Text("Test bildirimi gönder") }
                Text("Test, en son biten dersi sorar. Cevabın gerçekten kaydedilir.",
                    style = MaterialTheme.typography.bodySmall)
            }
            item {
                OutlinedButton(onClick = {
                    val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    ctx.startActivity(i)
                }, modifier = Modifier.fillMaxWidth()) { Text("Bildirim izinlerini aç") }
            }
            item {
                OutlinedButton(onClick = {
                    val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                    ctx.startActivity(i)
                }, modifier = Modifier.fillMaxWidth()) { Text("Pil optimizasyonu / uygulama ayarları") }
                Text("Bazı telefonlarda (Samsung, Xiaomi vb.) bildirimlerin zamanında gelmesi için " +
                    "uygulamanın pil kısıtlamasını \"Kısıtlanmamış\" yap.",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (Build.VERSION.SDK_INT >= 31) {
                item {
                    val am = ctx.getSystemService(android.app.AlarmManager::class.java)
                    if (!am.canScheduleExactAlarms()) {
                        OutlinedButton(onClick = {
                            ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.parse("package:${ctx.packageName}")))
                        }, modifier = Modifier.fillMaxWidth()) { Text("Tam zamanlı alarm iznini ver") }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showAdd) AddSlotDialog(onDismiss = { showAdd = false }) { code, day, st, en ->
        Repo.addSlot(ctx, code, day, st, en); Alarms.scheduleNext(ctx); showAdd = false; bump()
    }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Programı sıfırla?") },
        text = { Text("Ders programı OBS'deki varsayılan haline döner. Yoklama kayıtların silinmez.") },
        confirmButton = {
            TextButton(onClick = { Repo.resetSlots(ctx); Alarms.scheduleNext(ctx); confirmReset = false; bump() }) { Text("Sıfırla") }
        },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Vazgeç") } },
    )
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(3)) },
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun AddSlotDialog(onDismiss: () -> Unit, onAdd: (String, Int, LocalTime, LocalTime) -> Unit) {
    val ctx = LocalContext.current
    var code by remember { mutableStateOf("") }
    var day by remember { mutableStateOf(1) }
    var start by remember { mutableStateOf(LocalTime.of(8, 30)) }
    var end by remember { mutableStateOf(LocalTime.of(10, 29)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ders ekle") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(code, { code = it }, label = { Text("Ders kodu (örn. MAK 335)") }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..7).forEach { d ->
                        FilterChip(selected = day == d, onClick = { day = d }, label = { Text(dayShortName(d)) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        TimePickerDialog(ctx, { _, h, m -> start = LocalTime.of(h, m) }, start.hour, start.minute, true).show()
                    }) { Text("Başlangıç ${start.format(timeFmt)}") }
                    OutlinedButton(onClick = {
                        TimePickerDialog(ctx, { _, h, m -> end = LocalTime.of(h, m) }, end.hour, end.minute, true).show()
                    }) { Text("Bitiş ${end.format(timeFmt)}") }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = code.isNotBlank() && end.isAfter(start), onClick = { onAdd(code, day, start, end) }) {
                Text("Ekle")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}
