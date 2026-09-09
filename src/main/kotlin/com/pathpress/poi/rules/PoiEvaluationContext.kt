package com.pathpress.poi.rules

/**
 * Contextual preferences and parameters used by [PoiRulesEngine] to evaluate candidate POIs.
 *
 * @property userPrompt Natural language user prompt (e.g., "family road trip with kids").
 * @property excludePeaks Whether high-altitude peaks/mountain passes should be excluded.
 * @property excludeIndustrial Whether industrial or non-scenic nodes should be filtered out.
 */
data class PoiEvaluationContext(
    val userPrompt: String? = null,
    val excludePeaks: Boolean = false,
    val excludeIndustrial: Boolean = true,
) {
    /** `true` if the user prompt mentions family, kids, toddlers, or quick highway rest stops. */
    val isFamilyOrToddlerOrQuickBreak: Boolean by lazy {
        val prompt = userPrompt ?: return@lazy false
        FAMILY_PROMPT_REGEX.containsMatchIn(prompt)
    }

    /** `true` if the user prompt mentions toddlers, babies, or infants specifically. */
    val isToddlerOrBaby: Boolean by lazy {
        val prompt = userPrompt ?: return@lazy false
        TODDLER_PROMPT_REGEX.containsMatchIn(prompt)
    }

    /** `true` if the user prompt explicitly requests theme parks or amusement attractions. */
    val allowsThemeParksFromPrompt: Boolean by lazy {
        val prompt = userPrompt ?: return@lazy false
        THEME_PARK_PROMPT_REGEX.containsMatchIn(prompt)
    }

    /** `true` if the user prompt explicitly asks to avoid museums or galleries. */
    val avoidsMuseumsFromPrompt: Boolean by lazy {
        val prompt = userPrompt ?: return@lazy false
        AVOID_MUSEUM_PROMPT_REGEX.containsMatchIn(prompt)
    }

    /**
     * `true` if the user prompt explicitly asks to avoid castles, formal mansions, or guided tours.
     */
    val avoidsCastlesFromPrompt: Boolean by lazy {
        val prompt = userPrompt ?: return@lazy false
        AVOID_CASTLE_PROMPT_REGEX.containsMatchIn(prompt)
    }

    /** `true` if peaks should be excluded explicitly or due to a family/toddler travel persona. */
    val shouldExcludePeaks: Boolean by lazy { excludePeaks || isFamilyOrToddlerOrQuickBreak }

    companion object {
        private val FAMILY_PROMPT_REGEX =
            Regex(
                """\b(?:family|families|kid|kids|child|children|toddler|toddlers|baby|babies|infant|infants|highway break|quick break|rest stop)\b""",
                RegexOption.IGNORE_CASE,
            )

        private val TODDLER_PROMPT_REGEX =
            Regex(
                """\b(?:toddler|toddlers|baby|babies|infant|infants|1-year-old|2-year-old|3-year-old)\b""",
                RegexOption.IGNORE_CASE,
            )

        private val THEME_PARK_PROMPT_REGEX =
            Regex(
                """\b(?:theme park|theme parks|disney|six flags|amusement|roller coaster|roller coasters|coaster|coasters|legoland|seaworld|knott'?s?)\b""",
                RegexOption.IGNORE_CASE,
            )

        private val AVOID_MUSEUM_PROMPT_REGEX =
            Regex(
                """\b(?:avoid|no)\s+(?:formal\s+|quiet\s+|indoor\s+|art\s+)?(?:museums?|galleries)\b""",
                RegexOption.IGNORE_CASE,
            )

        private val AVOID_CASTLE_PROMPT_REGEX =
            Regex(
                """\b(?:avoid|no)\s+(?:formal\s+|guided\s+|stately\s+)?(?:castles?|historic homes?|mansions?|tours?)\b""",
                RegexOption.IGNORE_CASE,
            )
    }
}
