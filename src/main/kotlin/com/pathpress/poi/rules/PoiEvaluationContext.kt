package com.pathpress.poi.rules

data class PoiEvaluationContext(
    val userPrompt: String? = null,
    val allowsThemeParks: Boolean = false,
    val excludePeaks: Boolean = false,
    val excludeIndustrial: Boolean = true,
) {
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

    val allowsThemeParksFromPrompt: Boolean by lazy {
        allowsThemeParks ||
            (userPrompt?.lowercase()?.let { prompt ->
                prompt.contains("theme park") ||
                    prompt.contains("disney") ||
                    prompt.contains("six flags") ||
                    prompt.contains("amusement") ||
                    prompt.contains("roller coaster") ||
                    prompt.contains("coaster")
            } ?: false)
    }

    val shouldExcludePeaks: Boolean by lazy { excludePeaks || isFamilyOrToddlerOrQuickBreak }
}
