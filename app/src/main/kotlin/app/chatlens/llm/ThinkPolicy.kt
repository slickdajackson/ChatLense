package app.chatlens.llm

/**
 * Entscheidet nach dem Dateinamen, ob an den Prompt der Soft-Switch "/no_think" gehaengt wird.
 * Qwen3 (Dateien Qwen3-..., Qwen3_..., qwen3_...) kennt ihn. Qwen3.5 (Dateien Qwen3.5-...) hat ihn nicht; dort ist das Denken
 * im Modell abgeschaltet (leerer think-Block in der Vorlage der Konvertierung). Gemma braucht ihn nicht.
 */
fun noThinkSuffixFor(fileName: String): Boolean {
    val n = fileName.lowercase()
    return n.contains("qwen") && !n.contains("qwen3.5") && !n.contains("qwen3_5") && !n.contains("qwen35")
}
