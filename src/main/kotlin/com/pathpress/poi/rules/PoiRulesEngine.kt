package com.pathpress.poi.rules

import com.pathpress.model.POI

/**
 * Rules engine for filtering out unsuitable POIs and scoring candidate POIs for inclusion in route
 * itineraries.
 *
 * Evaluation consists of two stages:
 * 1. Exclusion filtering ([filterRules]): Any matching exclusion rule drops the POI immediately.
 * 2. Quality scoring ([scoringRules]): Accumulates scores based on metadata completeness, category
 *    fit, detour distance, and persona preferences.
 */
class PoiRulesEngine(
    val filterRules: List<PoiFilterRule> = defaultFilterRules(),
    val scoringRules: List<PoiScoringRule> = defaultScoringRules(),
) {
    /** Returns `true` if any registered filter rule excludes the given [poi]. */
    fun isExcluded(poi: POI, context: PoiEvaluationContext): Boolean = filterRules.any {
        it.isExcluded(poi, context)
    }

    /**
     * Sums quality and relevance score adjustments across all registered scoring rules for the
     * given [poi].
     */
    fun calculatePoiQualityScore(poi: POI, context: PoiEvaluationContext): Double =
        scoringRules.sumOf {
            it.calculateScore(poi, context)
        }

    companion object {
        /** Default set of hard-exclusion rules. */
        fun defaultFilterRules(): List<PoiFilterRule> =
            listOf(DisusedAndClosedFilterRule, ThemeParkFilterRule, PersonaExclusionFilterRule)

        /** Default set of additive quality/relevance scoring rules. */
        fun defaultScoringRules(): List<PoiScoringRule> =
            listOf(
                ChainPenaltyScoringRule,
                UnverifiedCommercialScoringRule,
                MetadataNotabilityScoringRule,
                CategoryAndPersonaScoringRule,
                DetourDistanceScoringRule,
            )

        /** Global default rules engine instance. */
        val default = PoiRulesEngine()
    }
}
