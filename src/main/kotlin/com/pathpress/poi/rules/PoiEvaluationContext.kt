package com.pathpress.poi.rules

/**
 * Contextual preferences and parameters used by [PoiRulesEngine] to evaluate candidate POIs.
 *
 * @property userPrompt Natural language prompt from the user (e.g., "family road trip with kids").
 * @property excludePeaks Whether high-altitude peaks/mountain passes should be excluded.
 * @property excludeIndustrial Whether industrial areas or non-scenic nodes should be filtered out.
 */
data class PoiEvaluationContext(
    val userPrompt: String? = null,
    val excludePeaks: Boolean = false,
    val excludeIndustrial: Boolean = true,
) {
    /** `true` if the user prompt mentions family, kids, toddlers, or quick highway rest stops. */
    val isFamilyOrToddlerOrQuickBreak: Boolean by lazy {
        val prompt = userPrompt?.lowercase() ?: return@lazy false
        prompt.contains("toddler") ||
            prompt.contains("toddlers") ||
            prompt.contains("kid") ||
            prompt.contains("kids") ||
            prompt.contains("family") ||
            prompt.contains("child") ||
            prompt.contains("children") ||
            prompt.contains("baby") ||
            prompt.contains("highway break") ||
            prompt.contains("quick break") ||
            prompt.contains("rest stop")
    }

    /** `true` if the user prompt explicitly requests theme parks or amusement attractions. */
    val allowsThemeParksFromPrompt: Boolean by lazy {
        userPrompt?.lowercase()?.let { prompt ->
            prompt.contains("theme park") ||
                prompt.contains("disney") ||
                prompt.contains("six flags") ||
                prompt.contains("amusement") ||
                prompt.contains("roller coaster") ||
                prompt.contains("coaster")
        } ?: false
    }

    /** `true` if peaks should be excluded explicitly or due to a family/toddler travel persona. */
    val shouldExcludePeaks: Boolean by lazy { excludePeaks || isFamilyOrToddlerOrQuickBreak }
}
