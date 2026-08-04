package com.pathpress.llm

/**
 * Detects LLM-fabricated road/highway specifics that cannot be grounded in OSM/GraphHopper data
 * (e.g. "I-5", "US-101", "SR-1", "Highway 1", "Route 66"). The prompt forbids these outright, but
 * LLM output must still be post-filtered since prompt instructions are not guaranteed to hold.
 */
object FalsifiableSpecificsFilter {
    // Matches interstate/US/state route shorthand (I-5, US-101, SR-1, CA-1) and spelled-out
    // highway/route references (Highway 1, Hwy 101, Route 66, Rte. 9).
    private val ROAD_REFERENCE_REGEX =
        Regex(
            """\b(?:I|US|SR|CR|FM|CA|TX)-\d+\b|\b(?:Highway|Hwy|Route|Rte)\.?\s*\d+\b""",
            RegexOption.IGNORE_CASE,
        )

    /**
     * Returns true if [text] contains a falsifiable road name, route number, or highway
     * designation.
     */
    fun containsRoadReference(text: String?): Boolean =
        !text.isNullOrBlank() && ROAD_REFERENCE_REGEX.containsMatchIn(text)
}
