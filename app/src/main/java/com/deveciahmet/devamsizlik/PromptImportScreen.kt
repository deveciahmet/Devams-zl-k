@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.deveciahmet.devamsizlik

import android.content.Intent
import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Programı yapay zekâ ile ekleme (ŞİMDİLİK KAPALI, bkz. [PROMPT_IMPORT_ENABLED]; yerine Kepler'den ekleme var):
 * 1) Kullanıcı hazır metni (prompt) kopyalayıp fotoğrafla birlikte bir yapay zekâya gönderir.
 * 2) Yapay zekânın JSON cevabını buraya yapıştırır.
 * 3) Uygulama cevabı çözer, kontrol listesi gösterir, onaylanınca programa kaydeder.
 */
@Composable
fun PromptImportScreen(nav: Nav) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var answer by rememberSaveable { mutableStateOf("") }
    var parsed by remember { mutableStateOf<ParseResult?>(null) }
    val entries = remember { mutableStateListOf<ImportedSlot>() }
    var replace by rememberSaveable { mutableStateOf(true) }
    var editIndex by remember { mutableIntStateOf(-1) }
    var promptExpanded by remember { mutableStateOf(false) }

    Scaffold(topBar = { BackBar("Yapay zekâ ile ekle", nav::pop) }) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "step1") {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("1. Bu metni kopyala", fontWeight = FontWeight.Bold)
                        Hint("ChatGPT, Gemini, Claude gibi bir yapay zekâ uygulamasını aç. Ders programının fotoğrafını " +
                            "ya da ekran görüntüsünü ekle, bu metni yapıştır ve gönder.")
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                            Text(
                                IMPORT_PROMPT,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = if (promptExpanded) Int.MAX_VALUE else 6,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable { promptExpanded = !promptExpanded }.padding(8.dp),
                            )
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                clipboard.setText(AnnotatedString(IMPORT_PROMPT))
                                Toast.makeText(ctx, "Metin kopyalandı", Toast.LENGTH_SHORT).show()
                            }) { Text("Kopyala") }
                            OutlinedButton(onClick = {
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(Intent.EXTRA_TEXT, IMPORT_PROMPT)
                                ctx.startActivity(Intent.createChooser(send, "Yapay zekâ uygulamasını seç"))
                            }) { Text("Paylaş") }
                            TextButton(onClick = { promptExpanded = !promptExpanded }) {
                                Text(if (promptExpanded) "Daralt" else "Tamamını göster")
                            }
                        }
                    }
                }
            }

            item(key = "step2") {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("2. Yapay zekânın cevabını buraya yapıştır", fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = answer,
                            onValueChange = { answer = it },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 4,
                            maxLines = 10,
                            textStyle = MaterialTheme.typography.bodySmall,
                            placeholder = { Text("[{\"code\":\"MAK 335\",\"day\":1,\"start\":\"10:30\",\"end\":\"13:29\"}, …]") },
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                val clip = clipboard.getText()?.text
                                if (clip.isNullOrBlank()) Toast.makeText(ctx, "Pano boş", Toast.LENGTH_SHORT).show()
                                else answer = clip
                            }) { Text("Panodan yapıştır") }
                            Button(enabled = answer.isNotBlank(), onClick = {
                                val r = ScheduleImport.parse(answer)
                                parsed = r
                                entries.clear()
                                entries.addAll(r.entries)
                            }) { Text("Çözümle") }
                        }
                    }
                }
            }

            val r = parsed
            if (r != null) {
                if (r.error != null) {
                    item(key = "error") {
                        Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        ) { Text(r.error, Modifier.padding(16.dp)) }
                    }
                }
                if (r.warnings.isNotEmpty()) {
                    item(key = "warnings") {
                        Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Bazı kayıtlar alınamadı:", fontWeight = FontWeight.Bold)
                                r.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
                if (r.error == null) {
                    item(key = "step3") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("3. Kontrol et (${entries.size} ders saati)", fontWeight = FontWeight.Bold)
                                Hint("Yanlış okunan bir şey varsa dokunup düzelt ya da sil.")
                            }
                            IconButton(onClick = { editIndex = entries.size }) { Icon(Icons.Default.Add, "Ders saati ekle") }
                        }
                    }
                    itemsIndexed(entries) { i, e ->
                        ImportRow(e, onEdit = { editIndex = i }, onDelete = { entries.removeAt(i) })
                    }
                    item(key = "save") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Spacer(Modifier.height(4.dp))
                            ChoiceRow(listOf(true to "Mevcut programın yerine koy", false to "Mevcut programa ekle"), replace) {
                                replace = it
                            }
                            Hint(
                                if (replace) "Eski program silinir. Aynı ders, gün ve saatteki derslerin yoklama geçmişi korunur."
                                else "Yeni ders saatleri mevcut programın yanına eklenir; aynı olanlar tekrar eklenmez."
                            )
                            Button(enabled = entries.isNotEmpty(), modifier = Modifier.fillMaxWidth(), onClick = {
                                val n = Repo.importSlots(ctx, entries.toList(), replace)
                                Scheduler.reschedule(ctx, resetCursor = true)
                                Toast.makeText(
                                    ctx,
                                    "$n ders saati kaydedildi. Yoklama sayısı ve devam şartı için: Ayarlar → Dersler",
                                    Toast.LENGTH_LONG,
                                ).show()
                                nav.pop()
                            }) { Text("Programa kaydet") }
                        }
                    }
                }
            }
            item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (editIndex >= 0) {
        val existing = entries.getOrNull(editIndex)
        SlotDialog(
            title = if (existing != null) "Ders saatini düzenle" else "Ders saati ekle",
            initial = existing?.let { SlotDraft(it.code, it.name, it.day, it.start, it.end) } ?: SlotDraft(),
            showName = true,
            onDismiss = { editIndex = -1 },
        ) { d ->
            val slot = ImportedSlot(normalizeCode(d.code), d.name, d.day, d.start, d.end)
            if (editIndex < entries.size) entries[editIndex] = slot else entries.add(slot)
            editIndex = -1
        }
    }
}

@Composable
private fun ImportRow(e: ImportedSlot, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onEdit), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(e.code + (if (e.name.isNotBlank()) " · ${e.name}" else ""), fontWeight = FontWeight.SemiBold)
            Hint("${dayLongName(e.day)}  ${e.start.format(timeFmt)}–${e.end.format(timeFmt)}")
        }
        IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Düzenle") }
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Sil") }
    }
}
