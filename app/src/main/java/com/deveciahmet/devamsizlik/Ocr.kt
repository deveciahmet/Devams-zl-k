package com.deveciahmet.devamsizlik

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Fotoğraftaki metni telefonda okur (Google ML Kit, internet gerekmez). */
object Ocr {
    suspend fun read(ctx: Context, uri: Uri): List<OcrLine> {
        // Fotoğrafı çözmek büyük dosyalarda zaman alır: arayüzü kilitlemesin
        val image = withContext(Dispatchers.IO) { InputImage.fromFilePath(ctx, uri) }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val text: Text = suspendCancellableCoroutine { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            return text.textBlocks.flatMap { block ->
                block.lines.mapNotNull { line ->
                    val r = line.boundingBox ?: return@mapNotNull null
                    OcrLine(
                        line.text, r.left, r.top, r.right, r.bottom,
                        line.elements.mapNotNull { e ->
                            e.boundingBox?.let { b -> OcrWord(e.text, b.left, b.top, b.right, b.bottom) }
                        },
                    )
                }
            }
        } finally {
            recognizer.close()
        }
    }
}
