package com.pathpress.model

private val SENTENCE_SPLIT_REGEX = Regex("(?<=[.!?])\\s+")
private val WHITESPACE_SPLIT_REGEX = Regex("\\s+")

/**
 * Extension helper to filter out null, blank, or `"null"` literal strings (commonly produced when
 * parsing unquoted or raw LLM output and stringified JSON).
 */
fun String?.takeValidText(): String? =
    this?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

/**
 * Limits narrative text to a clean, bounded summary of at most [maxSentences] sentences and
 * [maxWords] words total, preventing dense or overly long paragraphs on the cover page.
 */
fun String.boundNarrative(maxWords: Int = 55, maxSentences: Int = 3): String {
    val clean = this.trim()
    if (clean.isBlank()) return clean
    val sentences = clean.split(SENTENCE_SPLIT_REGEX).filter { it.isNotBlank() }
    val result = mutableListOf<String>()
    var totalWords = 0
    for (sentence in sentences) {
        val wordCount = sentence.split(WHITESPACE_SPLIT_REGEX).count { it.isNotBlank() }
        if (
            result.isEmpty() || (result.size < maxSentences && totalWords + wordCount <= maxWords)
        ) {
            result.add(sentence)
            totalWords += wordCount
        } else {
            break
        }
    }
    return result.joinToString(" ")
}
