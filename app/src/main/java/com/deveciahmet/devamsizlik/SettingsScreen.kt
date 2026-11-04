@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.deveciahmet.devamsizlik

import android.app.AlarmManager
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import java.time.LocalDateTime

@Composable
fun SettingsScreen(snap: Snapshot, nav: Nav) {
    val ctx = LocalContext.current
    val ns = snap.notify
    var weekText by remember(snap) { mutableStateOf(snap.currentWeek.toString()) }
    var weeksText by remember(snap) { mutableStateOf(snap.weeks.toString()) }
    var reqText by remember(snap) { mutableStateOf(snap.requiredPercent.toString()) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Slot?>(null) }
    var deleting by remember { mutableStateOf<Slot?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val slots = remember(snap) { snap.slots.sortedWith(compareBy<Slot>({ it.day }, { it.start })) }
    val codes = snap.courseCodes

    fun saveNotify(n: NotifySettings) {
        Repo.setNotifySettings(ctx, n)
        Scheduler.reschedule(ctx, resetCursor = true)
    }

    Scaffold(topBar = { BackBar("Ayarlar", nav::pop) }) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------------- Dönem ----------------
            item(key = "s_term") { SectionTitle("Dönem") }
            item(key = "start") {
                OutlinedButton(onClick = {
                    val s = snap.semesterStart
                    DatePickerDialog(ctx, { _, y, m, d ->
                        Repo.setSemesterStart(ctx, LocalDate.of(y, m + 1, d))
                        Scheduler.reschedule(ctx, resetCursor = true)
                    }, s.year, s.monthValue - 1, s.dayOfMonth).show()
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("1. hafta başlangıcı: ${snap.semesterStart.format(dateLongFmt)}")
                }
            }
            item(key = "numbers") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField("Şu anki hafta", weekText, Modifier.weight(1f)) { v ->
                            weekText = v
                            v.toIntOrNull()?.takeIf { it in 1..30 }?.let {
                                Repo.setCurrentWeek(ctx, it)
                                Scheduler.reschedule(ctx, resetCursor = true)
                            }
                        }
                        NumberField("Toplam hafta", weeksText, Modifier.weight(1f)) { v ->
                            weeksText = v
                            v.toIntOrNull()?.takeIf { it in 1..30 }?.let {
                                Repo.setWeeks(ctx, it)
                                Scheduler.reschedule(ctx, resetCursor = true)
                            }
                        }
                        NumberField("Devam şartı %", reqText, Modifier.weight(1f)) { v ->
                            reqText = v
                            v.toIntOrNull()?.takeIf { it in 0..100 }?.let {
                                Repo.setRequiredPercent(ctx, it)
                                Scheduler.reschedule(ctx)
                            }
                        }
                    }
                    Hint("Devam şartı: derslerin en az yüzde kaçına girmen gerektiği (İTÜ'de genelde %70). " +
                        "Bir derse özel farklı şart için aşağıdaki Dersler bölümünü kullan.")
                }
            }

            // ---------------- Program ----------------
            item(key = "div1") { HorizontalDivider() }
            item(key = "s_prog") { SectionTitle("Ders programı") }
            item(key = "prog_btns") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = { nav.push(Screen.Import) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Programı fotoğraftan ekle")
                    }
                    OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Elle ders saati ekle")
                    }
                }
            }
            items(slots, key = { "slot_${it.id}" }) { s ->
                val cfg = snap.config(s.code)
                Row(
                    Modifier.fillMaxWidth().clickable { editing = s },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(cfg.name.ifBlank { s.code }, fontWeight = FontWeight.SemiBold)
                        Hint((if (cfg.name.isNotBlank()) "${s.code} • " else "") +
                            "${dayLongName(s.day)}  ${s.start.format(timeFmt)}–${s.end.format(timeFmt)}")
                    }
                    IconButton(onClick = { editing = s }) { Icon(Icons.Default.Edit, "Düzenle") }
                    IconButton(onClick = { deleting = s }) { Icon(Icons.Default.Delete, "Sil") }
                }
            }
            if (slots.isEmpty()) item(key = "noslots") { Hint("Programda ders yok.") }
            item(key = "reset") {
                TextButton(onClick = { confirmReset = true }) { Text("Programı varsayılana (OBS) döndür") }
            }

            // ---------------- Dersler ----------------
            if (codes.isNotEmpty()) {
                item(key = "div2") { HorizontalDivider() }
                item(key = "s_courses") {
                    Column {
                        SectionTitle("Dersler")
                        Hint("Ders adı, dönemdeki ders sayısı, devam şartı / gelmeme hakkı ve yoklama sayısı için derse dokun.")
                    }
                }
                items(codes, key = { "course_$it" }) { code ->
                    val cfg = snap.config(code)
                    val st = snap.stat(code)
                    Row(
                        Modifier.fillMaxWidth().clickable { nav.push(Screen.CourseEdit(code)) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(cfg.name.ifBlank { code }, fontWeight = FontWeight.SemiBold)
                            if (st != null) {
                                Hint((if (cfg.name.isNotBlank()) "$code • " else "") + ruleSummary(st, cfg, snap.requiredPercent))
                            }
                        }
                        Icon(Icons.Default.Edit, null)
                    }
                }
            }

            // ---------------- Bildirimler ----------------
            item(key = "div3") { HorizontalDivider() }
            item(key = "s_notif") { SectionTitle("Bildirimler") }
            item(key = "reminder") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Cevapsız soruyu tekrar sor")
                    ChoiceRow(listOf(0 to "Kapalı", 15 to "15 dk", 30 to "30 dk", 60 to "1 saat"), ns.reminderMinutes) {
                        saveNotify(ns.copy(reminderMinutes = it))
                    }
                    Hint("Soruya cevap vermez ya da bildirimi kaydırıp kapatırsan bu süre sonra bir kez daha sorar.")
                }
            }
            item(key = "weekly") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SwitchRow("Haftalık özet", ns.weeklyEnabled, "Son 7 günde kaç derse girdiğini ve riskli dersleri özetler") {
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
                    SwitchRow("Erken uyarı", ns.warnEnabled, "Devamsızlık hakkın azalınca bildirimle uyarır") {
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
                        Hint("Bir derse \"Hayır\" dediğinde kalan hakkın bu sayıya inerse hemen uyarır. Ayrıca hakkı azalan " +
                            "derslerin başlamasından önce \"kaçırma\" hatırlatması gönderir.")
                    }
                }
            }
            item(key = "test") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = {
                        val now = AppClock.now()
                        val p = snap.polls.lastOrNull { !it.time.isAfter(now) } ?: snap.polls.firstOrNull()
                        if (p == null) {
                            Toast.makeText(ctx, "Programda ders yok", Toast.LENGTH_SHORT).show()
                        } else {
                            if (!Notifier.canPost(ctx)) {
                                Toast.makeText(ctx, "Bildirim izni kapalı. \"Bildirim izinlerini aç\"a dokun.",
                                    Toast.LENGTH_LONG).show()
                            }
                            Notifier.askPoll(ctx, p, snap.config(p.code), reminder = false)
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Test bildirimi gönder") }
                    Hint("Test, en son biten dersi sorar. Cevabın gerçekten kaydedilir.")
                }
            }
            item(key = "perm") {
                OutlinedButton(onClick = {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    )
                }, modifier = Modifier.fillMaxWidth()) { Text("Bildirim izinlerini aç") }
            }
            item(key = "battery") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = {
                        ctx.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                        )
                    }, modifier = Modifier.fillMaxWidth()) { Text("Pil optimizasyonu / uygulama ayarları") }
                    Hint("Bazı telefonlarda (Samsung, Xiaomi vb.) bildirimlerin zamanında gelmesi için uygulamanın pil " +
                        "kullanımını \"Kısıtlanmamış\" yap.")
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val am = ctx.getSystemService(AlarmManager::class.java)
                if (am != null && !am.canScheduleExactAlarms()) {
                    item(key = "exact") {
                        OutlinedButton(onClick = {
                            ctx.startActivity(
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))
                            )
                        }, modifier = Modifier.fillMaxWidth()) { Text("Tam zamanlı alarm iznini ver") }
                    }
                }
            }
            item(key = "version") { Hint("Sürüm ${versionName(ctx)}") }
            item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (adding) {
        SlotDialog("Ders saati ekle", SlotDraft(), showName = false, onDismiss = { adding = false }) { d ->
            Repo.addSlot(ctx, Repo.newSlot(d.code, d.day, d.start, d.end))
            Scheduler.reschedule(ctx, resetCursor = true)
            adding = false
        }
    }
    editing?.let { s ->
        SlotDialog(
            "Ders saatini düzenle",
            SlotDraft(s.code, "", s.day, s.start, s.end),
            showName = false,
            onDismiss = { editing = null },
        ) { d ->
            Repo.updateSlot(ctx, s, s.copy(code = normalizeCode(d.code), day = d.day, start = d.start, end = d.end))
            Scheduler.reschedule(ctx, resetCursor = true)
            editing = null
        }
    }
    deleting?.let { s ->
        ConfirmDialog(
            title = "Ders saati silinsin mi?",
            text = "${s.code} • ${dayLongName(s.day)} ${s.start.format(timeFmt)}–${s.end.format(timeFmt)} programdan " +
                "silinecek. Bu saate ait yoklamalar istatistiklerden çıkar.",
            confirm = "Sil",
            onDismiss = { deleting = null },
        ) {
            Repo.removeSlot(ctx, s.id)
            Scheduler.reschedule(ctx, resetCursor = true)
            deleting = null
        }
    }
    if (confirmReset) {
        ConfirmDialog(
            title = "Program sıfırlansın mı?",
            text = "Ders programı OBS'deki varsayılan haline döner. Yoklama kayıtların silinmez.",
            confirm = "Sıfırla",
            onDismiss = { confirmReset = false },
        ) {
            Repo.resetSlots(ctx)
            Scheduler.reschedule(ctx, resetCursor = true)
            confirmReset = false
        }
    }
}

@Suppress("DEPRECATION")
private fun versionName(ctx: Context): String =
    runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
