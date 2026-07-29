package com.pathpress.poi.rules

import com.pathpress.model.POI

fun interface PoiFilterRule {
    fun isExcluded(poi: POI, context: PoiEvaluationContext): Boolean
}

fun interface PoiScoringRule {
    fun calculateScore(poi: POI, context: PoiEvaluationContext): Double
}
