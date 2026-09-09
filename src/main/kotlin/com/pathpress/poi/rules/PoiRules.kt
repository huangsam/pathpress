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

internal val CHILDREN_MUSEUM_REGEX =
    Regex("""\b(?:children'?s?|discovery|child)\b""", RegexOption.IGNORE_CASE)

internal fun isChildrenMuseum(poi: POI): Boolean {
    val museumTag = poi.tags["museum"]?.lowercase() ?: ""
    val childrenTag = poi.tags["children"]?.lowercase() ?: ""
    val name = poi.name ?: ""
    return museumTag == "children" ||
        childrenTag in setOf("yes", "only") ||
        CHILDREN_MUSEUM_REGEX.containsMatchIn(name)
}

internal fun isCastleOrStatelyMansion(poi: POI): Boolean {
    val historic = poi.tags["historic"]?.lowercase()
    val castleType = poi.tags["castle_type"]?.lowercase()
    return historic == "castle" || castleType != null || poi.tags["building"] == "castle"
}

internal fun isFormalAdultMuseumOrCastle(poi: POI): Boolean {
    val pType = poi.type.lowercase()
    val tourism = poi.tags["tourism"]?.lowercase()
    val isMuseum = (pType == "museum" || tourism == "museum") && !isChildrenMuseum(poi)
    val isGallery = tourism == "gallery"
    return isCastleOrStatelyMansion(poi) || isMuseum || isGallery
}
