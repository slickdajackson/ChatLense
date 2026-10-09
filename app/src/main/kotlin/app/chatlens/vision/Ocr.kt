package app.chatlens.vision

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** ML Kit Text Recognition (bundled, Latin script, runs offline on the device). */
object Ocr {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Returns recognized text, or null on error or when empty. */
    suspend fun recognize(bitmap: Bitmap): String? = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { r -> cont.resume(r.text.trim().ifEmpty { null }) }
            .addOnFailureListener { cont.resume(null) }
    }
}
