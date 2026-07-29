package com.pathpress.poi.rules

import com.pathpress.model.POI

/** Rule interface for determining if a POI should be strictly excluded from selection. */
fun interface PoiFilterRule {
    fun isExcluded(poi: POI, context: PoiEvaluationContext): Boolean
}

/** Rule interface for calculating an additive score adjustment for a candidate POI. */
fun interface PoiScoringRule {
    fun calculateScore(poi: POI, context: PoiEvaluationContext): Double
}
