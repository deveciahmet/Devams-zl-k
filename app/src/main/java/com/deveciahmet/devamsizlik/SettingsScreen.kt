@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.deveciahmet.devamsizlik

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(snap: Snapshot, nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val ns = snap.notify
    var adding by remember { mutableStateOf(false) }
    var newTerm by remember { mutableStateOf(false) }
    var fetching by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<Pair<String, BackupInfo>?>(null) }
    val archives = remember(snap) { Archive.list(ctx) }
    val codes = snap.courseCodes

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

    fun saveNotify(n: NotifySettings) {
        Repo.setNotifySettings(ctx, n)
        Scheduler.reschedule(ctx, resetCursor = true)
    }

    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(Backup.export(ctx).toByteArray()) }
        }.onSuccess { toast("Yedek kaydedildi. Telefon değiştirirken bu dosyayı yeni telefona taşı.") }
            .onFailure { toast("Yedek kaydedilemedi: ${it.message}") }
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val json = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            json to Backup.inspect(json)
        }.onSuccess { pendingRestore = it }
            .onFailure { toast((it as? IllegalArgumentException)?.message ?: "Dosya okunamadı.") }
    }

    TabScaffold(nav, "Ayarlar", snackbar = snackbar) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ---------------- Dönem ----------------
            item(key = "s_term") {
                Column {
                    SectionTitle("Dönem")
                    Hint(snap.termName)
                }
            }
            item(key = "start") {
                SettingRow("1. haftanın başlangıcı", snap.semesterStart.format(dateLongFmt)) {
                    pickDate(ctx, snap.semesterStart) {
                        Repo.setSemesterStart(ctx, it)
                        Scheduler.reschedule(ctx, resetCursor = true)
                    }
                }
            }
            item(key = "numbers") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberSetting("Şu anki hafta", snap.currentWeek, 1..30, Modifier.weight(1f)) {
                            Repo.setCurrentWeek(ctx, it)
                            Scheduler.reschedule(ctx, resetCursor = true)
                        }
                        NumberSetting("Toplam hafta", snap.weeks, 1..30, Modifier.weight(1f)) {
                            Repo.setWeeks(ctx, it)
                            Scheduler.reschedule(ctx, resetCursor = true)
                        }
                        NumberSetting("Devam şartı %", snap.requiredPercent, 0..100, Modifier.weight(1f)) {
                            Repo.setRequiredPercent(ctx, it)
                            Scheduler.reschedule(ctx)
                        }
                    }
                    Hint("Devam şartı: derslerin en az yüzde kaçına girmen gerektiği (İTÜ'de genelde %70). " +
                        "Bir derse özel şart için dersin ayarlarını aç.")
                }
            }
            item(key = "holidays") {
                val n = snap.holidays.size
                SettingRow(
                    "Tatil günleri",
                    if (n == 0) "Bir günü seçince o günün bütün dersleri \"yapılmadı\" olur" else "$n gün tatil olarak işaretli",
                ) { nav.push(Screen.Holidays) }
            }
            item(key = "new_term") {
                SettingRow("Yeni dönem başlat", "Bu dönemi arşivle, yeni döneme temiz başla") { newTerm = true }
            }
            if (archives.isNotEmpty()) {
                item(key = "archives") {
                    SettingRow("Geçmiş dönemler", archives.joinToString(", ") { it.name }) { nav.push(Screen.Archives) }
                }
            }

            // ---------------- Dersler ----------------
            item(key = "div1") { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item(key = "s_courses") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle("Dersler")
                    Button(onClick = { nav.push(Screen.Import) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Programı Kepler'den ekle")
                    }
                    if (PROMPT_IMPORT_ENABLED) {
                        OutlinedButton(onClick = { nav.push(Screen.PromptImport) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Yapay zekâ ile ekle")
                        }
                    }
                }
            }
            items(codes, key = { "course_$it" }) { code ->
                val slots = snap.slots.filter { it.code == code }.sortedWith(compareBy({ it.day }, { it.start }))
                val cfg = snap.config(code)
                SettingRow(
                    cfg.name.ifBlank { code },
                    (if (cfg.name.isNotBlank()) "$code • " else "") +
                        slots.joinToString(", ") { "${dayShortName(it.day)} ${timeRange(it.start, it.end)}" },
                ) { nav.push(Screen.CourseEdit(code)) }
            }
            if (codes.isEmpty()) item(key = "noslots") { Hint("Programda ders yok.") }
            item(key = "course_actions") {
                Column {
                    TextButton(onClick = { adding = true }) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Elle ders ekle")
                    }
                    if (codes.isNotEmpty()) {
                        TextButton(enabled = !fetching, onClick = {
                            fetching = true
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { runCatching { Itu.fillCourseInfo(ctx) } }
                                fetching = false
                                r.onSuccess { n ->
                                    Scheduler.reschedule(ctx)
                                    toast(if (n > 0) "$n dersin bilgisi İTÜ'den güncellendi." else "Güncellenecek bilgi bulunamadı.")
                                }.onFailure {
                                    toast(if (it is IOException) "İTÜ'ye ulaşılamadı. İnternet bağlantını kontrol et." else "Hata: ${it.message}")
                                }
                            }
                        }) {
                            if (fetching) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (fetching) "İTÜ'den alınıyor…" else "Boş ders adlarını ve derslikleri İTÜ'den doldur")
                        }
                    }
                }
            }

            // ---------------- Bildirimler ----------------
            item(key = "div2") { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item(key = "s_notif") { SectionTitle("Bildirimler") }
            item(key = "reminder") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Cevapsız soruyu tekrar sor")
                    ChoiceRow(listOf(0 to "Kapalı", 15 to "15 dk", 30 to "30 dk", 60 to "1 saat"), ns.reminderMinutes) {
                        saveNotify(ns.copy(reminderMinutes = it))
                    }
                }
            }
            item(key = "weekly") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SwitchRow("Haftalık özet", ns.weeklyEnabled, "Son 7 günde kaç derse girdiğin, riskli dersler ve yaklaşan sınavlar") {
                        saveNotify(ns.copy(weeklyEnabled = it))
                    }
                    if (ns.weeklyEnabled) {
                        ChoiceRow((1..7).map { it to dayShortName(it) }, ns.weeklyDay) { saveNotify(ns.copy(weeklyDay = it)) }
                        OutlinedButton(onClick = {
                            pickTime(ctx, ns.weeklyTime) { t -> saveNotify(ns.copy(weeklyTime = t)) }
                        }) { Text("Saat: ${ns.weeklyTime.format(timeFmt)}") }
                    }
                }
            }
            item(key = "warn") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SwitchRow("Erken uyarı", ns.warnEnabled, "Devamsızlık hakkın azalınca uyarır, riskli derslerden önce hatırlatır") {
                        saveNotify(ns.copy(warnEnabled = it))
                    }
                    if (ns.warnEnabled) {
                        Text("Kaç hak kala uyarsın?")
                        ChoiceRow(listOf(1 to "1 hak", 2 to "2 hak", 3 to "3 hak"), ns.warnThreshold) {
                            saveNotify(ns.copy(warnThreshold = it))
                        }
                        Text("Riskli derslerden önce hatırlat")
                        ChoiceRow(
                            listOf(0 to "Kapalı", 30 to "30 dk önce", 60 to "1 saat önce", 120 to "2 saat önce"),
                            ns.preclassMinutes,
                        ) { saveNotify(ns.copy(preclassMinutes = it)) }
                    }
                }
            }
            item(key = "exam_reminder") {
                SwitchRow("Sınav hatırlatması", ns.examReminder, "Sınavdan önceki akşam saat 20:00'de hatırlatır") {
                    saveNotify(ns.copy(examReminder = it))
                }
            }
            item(key = "test") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = {
                        val now = AppClock.now()
                        val p = snap.polls.lastOrNull { !it.time.isAfter(now) } ?: snap.polls.firstOrNull()
                        if (p == null) {
                            toast("Programda ders yok")
                        } else {
                            if (!Notifier.canPost(ctx)) toast("Bildirim izni kapalı. \"Bildirim izinlerini aç\"a dokun.")
                            Notifier.askPoll(ctx, p, snap.config(p.code), reminder = false, test = true)
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Test bildirimi gönder") }
                    Hint("Bildirimlerin geldiğini kontrol etmek için. Teste verdiğin cevap kaydedilmez.")
                }
            }
            item(key = "perm") {
                SettingRow("Bildirim izinlerini aç") {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    )
                }
            }
            item(key = "battery") {
                SettingRow(
                    "Pil kullanımı",
                    "Bazı telefonlarda (Samsung, Xiaomi vb.) bildirimlerin zamanında gelmesi için \"Kısıtlanmamış\" yap",
                ) {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val am = ctx.getSystemService(AlarmManager::class.java)
                if (am != null && !am.canScheduleExactAlarms()) {
                    item(key = "exact") {
                        SettingRow("Tam zamanlı alarm iznini ver", "Sorular ders bitiminde tam zamanında gelsin") {
                            ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
                        }
                    }
                }
            }

            // ---------------- Yedekleme ----------------
            item(key = "div3") { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item(key = "s_backup") {
                Column {
                    SectionTitle("Yedekleme ve aktarma")
                    Hint("Bütün kayıtlar (program, yoklamalar, sınavlar, geçmiş dönemler) tek bir dosyaya kaydedilir. " +
                        "Dosyayı Drive'a ya da yeni telefona gönder, orada \"Yedekten geri yükle\" de.")
                }
            }
            item(key = "backup") {
                SettingRow("Yedek dosyası oluştur", Backup.fileName()) { createBackup.launch(Backup.fileName()) }
            }
            item(key = "restore") {
                SettingRow("Yedekten geri yükle", "Şu anki kayıtların yerine yedektekiler gelir") {
                    openBackup.launch(arrayOf("*/*"))
                }
            }

            item(key = "version") { Hint("Sürüm ${versionName(ctx)}", modifier = Modifier.padding(top = 12.dp)) }
            item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (adding) {
        SlotDialog("Ders ekle", SlotDraft(), showName = true, onDismiss = { adding = false }) { d ->
            val slot = Repo.newSlot(d.code, d.day, d.start, d.end, d.place)
            Repo.addSlot(ctx, slot)
            if (d.name.isNotBlank()) Repo.saveCourse(ctx, slot.code, slot.code, snap.config(slot.code).copy(name = d.name))
            Scheduler.reschedule(ctx, resetCursor = true)
            adding = false
        }
    }

    if (newTerm) {
        NewTermDialog(snap, onDismiss = { newTerm = false }) { name, start ->
            newTerm = false
            val id = Archive.startNewTerm(ctx, name, start)
            Scheduler.reschedule(ctx, resetCursor = true)
            scope.launch {
                val r = snackbar.showSnackbar(
                    "\"$name\" arşivlendi. Yeni dönem ${start.format(dateLongFmt)} haftasında başlıyor.",
                    actionLabel = "Geri al",
                    duration = SnackbarDuration.Long,
                )
                if (r == SnackbarResult.ActionPerformed) {
                    Archive.restore(ctx, id)
                    Scheduler.reschedule(ctx, resetCursor = true)
                }
            }
        }
    }

    pendingRestore?.let { (json, info) ->
        ConfirmDialog(
            title = "Yedek geri yüklensin mi?",
            text = buildString {
                append(info.termName.ifBlank { "Yedek" })
                info.createdAt?.let { append(" • ${it.toLocalDate().format(dateLongFmt)} tarihli") }
                append("\n${info.courses} ders, ${info.records} yoklama kaydı")
                if (info.exams > 0) append(", ${info.exams} sınav")
                if (info.archives > 0) append(", ${info.archives} geçmiş dönem")
                append(".\n\nŞu anki bütün kayıtlar silinip yedektekiler yüklenecek.")
            },
            confirm = "Geri yükle",
            onDismiss = { pendingRestore = null },
        ) {
            pendingRestore = null
            runCatching { Backup.restore(ctx, json) }
                .onSuccess {
                    Scheduler.reschedule(ctx, resetCursor = true)
                    toast("Yedek geri yüklendi.")
                }
                .onFailure { toast(it.message ?: "Geri yüklenemedi.") }
        }
    }
}

/** "Yeni dönem başlat": arşiv adı ve yeni dönemin ilk haftası. */
@Composable
private fun NewTermDialog(snap: Snapshot, onDismiss: () -> Unit, onConfirm: (String, LocalDate) -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(snap.termName) }
    var start by remember {
        val t = snap.today
        mutableStateOf(if (t.dayOfWeek == java.time.DayOfWeek.MONDAY) t else mondayOf(t).plusWeeks(1))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Yeni dönem başlat") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Bu dönemin programı, yoklamaları, tatilleri ve sınavları arşivlenir; özetine Ayarlar → Geçmiş " +
                    "dönemler'den bakabilirsin. Yeni dönem boş bir programla başlar.")
                OutlinedTextField(name, { name = it }, label = { Text("Arşivlenecek dönemin adı") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { pickDate(ctx, start) { start = it } }, modifier = Modifier.fillMaxWidth()) {
                    Text("Yeni dönemin 1. haftası: ${mondayOf(start).format(dateLongFmt)}")
                }
                Hint("Sonra yeni programını Kepler'den ekleyebilirsin. Bildirim ayarların ve devam şartın korunur.")
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim(), start) }) { Text("Başlat") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}

@Suppress("DEPRECATION")
private fun versionName(ctx: Context): String =
    runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
