package app.chatlens.llm

/**
 * Decides from the file name whether the soft switch "/no_think" is appended to the prompt.
 * Qwen3 (files Qwen3-..., Qwen3_..., qwen3_...) knows it. Qwen3.5 (files Qwen3.5-...) does not. There, thinking
 * is switched off inside the model (empty think block in the conversion template). Gemma does not need it.
 */
fun noThinkSuffixFor(fileName: String): Boolean {
    val n = fileName.lowercase()
    return n.contains("qwen") && !n.contains("qwen3.5") && !n.contains("qwen3_5") && !n.contains("qwen35")
}
