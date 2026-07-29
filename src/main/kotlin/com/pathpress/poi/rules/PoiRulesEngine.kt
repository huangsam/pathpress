package com.pathpress.poi.rules

import com.pathpress.model.POI

class PoiRulesEngine(
    val filterRules: List<PoiFilterRule> = defaultFilterRules(),
    val scoringRules: List<PoiScoringRule> = defaultScoringRules(),
) {
    fun isExcluded(poi: POI, context: PoiEvaluationContext): Boolean = filterRules.any {
        it.isExcluded(poi, context)
    }

    fun calculatePoiQualityScore(poi: POI, context: PoiEvaluationContext): Double =
        scoringRules.sumOf {
            it.calculateScore(poi, context)
        }

    companion object {
        fun defaultFilterRules(): List<PoiFilterRule> =
            listOf(DisusedAndClosedFilterRule, ThemeParkFilterRule, PersonaExclusionFilterRule)

        fun defaultScoringRules(): List<PoiScoringRule> =
            listOf(
                ChainPenaltyScoringRule,
                UnverifiedCommercialScoringRule,
                MetadataNotabilityScoringRule,
                CategoryAndPersonaScoringRule,
                DetourDistanceScoringRule,
            )

        val default = PoiRulesEngine()
    }
}
