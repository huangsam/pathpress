package com.pathpress

import com.pathpress.config.Config
import com.pathpress.llm.LlmProvider
import com.pathpress.llm.TripPlanResponse
import com.pathpress.model.DistanceUnit
import com.pathpress.model.LocationCoords
import com.pathpress.model.Route
import com.pathpress.pbf.PbfPathResolver
import com.pathpress.routing.GeocodedLocation
import com.pathpress.routing.Geocoder
import com.pathpress.routing.RouteCalculator
import com.pathpress.routing.WaypointValidator
import org.slf4j.LoggerFactory

/** Options required by [TripPlannerOrchestrator] to plan a trip. */
data class TripPlannerRequest(
    val startLocation: String,
    val endLocation: String,
    val days: Int = 1,
    val profile: String = "scenic",
    val prompt: String? = null,
    val llmProviderName: String = "none",
    val llmModel: String? = null,
    val llmKey: String? = null,
    val llmUrl: String? = null,
    val poisPerLeg: Int = Config.DEFAULT_POIS_PER_LEG,
    val distanceUnit: DistanceUnit = DistanceUnit.METRIC,
    val pbfPath: String = PbfPathResolver.defaultPath(),
    val graphPath: String? = null,
)

/** Result produced by [TripPlannerOrchestrator]. */
data class TripPlannerResult(
    val route: Route,
    val startGeo: GeocodedLocation,
    val endGeo: GeocodedLocation,
)

/** Pure application service that orchestrates the trip planning execution pipeline. */
class TripPlannerOrchestrator(
    private val config: Config = Config.fromEnv(),
    private val geocoder: (String) -> GeocodedLocation? = { Geocoder.geocode(it, config) },
    private val routeCalculatorFactory:
        (graphPath: String, pbfPath: String, config: Config) -> RouteCalculator =
        { graphPath, pbfPath, cfg ->
            RouteCalculator.create(graphPath, pbfPath, cfg)
        },
    private val llmProviderFactory:
        (
            provider: String, key: String?, url: String?, model: String?, config: Config,
        ) -> LlmProvider =
        { provider, key, url, model, cfg ->
            LlmProvider.create(provider, key, url, model, config = cfg)
        },
) {
    private val logger = LoggerFactory.getLogger(TripPlannerOrchestrator::class.java)

    fun planTrip(request: TripPlannerRequest): TripPlannerResult {
        val pbfResult = PbfPathResolver.resolveWithSupplementaryHints(request.pbfPath)
        val pbfPath = pbfResult.primaryPath
        val mappedProfile = if (request.profile.lowercase() == "scenic") "car" else request.profile

        if (pbfResult.supplementaryHints.isNotEmpty()) {
            logger.warn(
                "Selected PBF extract '{}' is a micro-extract. Administrative border clipping may disconnect regional highways. " +
                    "For full graph connectivity across state lines, consider using adjacent region(s): {}.",
                pbfPath,
                pbfResult.supplementaryHints.joinToString(", "),
            )
        }

        // 1. Geocode start and end locations
        logger.info("Geocoding locations...")
        val startGeo =
            geocoder(request.startLocation)
                ?: throw com.pathpress.routing.GeocodingException(request.startLocation)
        val endGeo =
            geocoder(request.endLocation)
                ?: throw com.pathpress.routing.GeocodingException(request.endLocation)
        logger.info(
            "  -> Start: '${startGeo.displayName}' (${startGeo.coords.lat}, ${startGeo.coords.lng})"
        )
        logger.info(
            "  -> End:   '${endGeo.displayName}' (${endGeo.coords.lat}, ${endGeo.coords.lng})"
        )

        // 2. Initialize GraphHopper Routing Engine
        val resolvedGraphPath = PbfPathResolver.resolveGraphPath(request.graphPath, pbfPath)
        logger.info(
            "Loading spatial routing data from $pbfPath (graph storage: $resolvedGraphPath)..."
        )
        val routeCalculator =
            try {
                routeCalculatorFactory(resolvedGraphPath, pbfPath, config)
            } catch (e: Exception) {
                throw com.pathpress.routing.TripPlanningException(
                    "Failed to initialize GraphHopper. Ensure PBF file exists at $pbfPath\n" +
                        "Error: ${e.message}",
                    cause = e,
                )
            }

        // 3. Initialize AI Trip Planner & Plan Trip Concept
        logger.info("Initializing AI Trip Planner (${request.llmProviderName})...")
        val llm =
            llmProviderFactory(
                request.llmProviderName,
                request.llmKey,
                request.llmUrl,
                request.llmModel,
                config,
            )
        val initialTripPlan =
            llm.planTrip(
                startName = startGeo.displayName,
                endName = endGeo.displayName,
                startCoords = startGeo.coords,
                endCoords = endGeo.coords,
                days = request.days,
                userPrompt = request.prompt,
            )

        val resolution =
            resolveAndValidateWaypoints(
                initialTripPlan = initialTripPlan,
                startGeo = startGeo,
                endGeo = endGeo,
                days = request.days,
                prompt = request.prompt,
                llm = llm,
                geocoder = geocoder,
            )
        val resolvedWaypoints = resolution.waypoints
        val tripPlan = resolution.tripPlan

        if (resolvedWaypoints.isNotEmpty()) {
            logger.info(
                "Routing via ${resolvedWaypoints.size} intermediate waypoints: ${resolvedWaypoints.joinToString { it.name ?: "(${it.lat}, ${it.lng})" }}"
            )
        }

        // 4. Calculate Driving Route & Extract Real Corridor POIs
        logger.info("Calculating driving route & extracting real OSM corridor POIs...")
        val rawLegs =
            routeCalculator.calculateRouteWithLegs(
                startLat = startGeo.coords.lat,
                startLng = startGeo.coords.lng,
                endLat = endGeo.coords.lat,
                endLng = endGeo.coords.lng,
                days = request.days,
                profile = mappedProfile,
                limitPerLeg = request.poisPerLeg,
                userPrompt = request.prompt,
                waypoints = resolvedWaypoints,
                startName = startGeo.displayName,
                endName = endGeo.displayName,
            )

        // 5. Curate POIs & Apply Leg Storytelling
        logger.info("Curating POIs & applying leg storytelling...")
        val curatedLegs = rawLegs.map { leg ->
            val curation = llm.curateLegPois(leg, request.prompt, request.distanceUnit)
            leg.copy(legStory = curation.legStory, pois = curation.curatedPois)
        }

        val totalDistance = curatedLegs.sumOf { it.distanceMeters ?: 0.0 }
        val totalDuration = curatedLegs.sumOf { it.durationSeconds ?: 0.0 }
        val route = Route(curatedLegs, totalDistance, totalDuration, narrative = tripPlan.narrative)

        return TripPlannerResult(route, startGeo, endGeo)
    }
}

internal data class WaypointResolution(
    val waypoints: List<LocationCoords>,
    val tripPlan: TripPlanResponse,
)

internal fun resolveAndValidateWaypoints(
    initialTripPlan: TripPlanResponse,
    startGeo: GeocodedLocation,
    endGeo: GeocodedLocation,
    days: Int,
    prompt: String?,
    llm: LlmProvider,
    geocoder: (String) -> GeocodedLocation? = { Geocoder.geocode(it) },
): WaypointResolution {
    val logger = LoggerFactory.getLogger("com.pathpress.Main")

    fun geocodeWaypoints(rawWaypoints: List<LocationCoords>): List<LocationCoords> {
        return rawWaypoints.mapNotNull { wp ->
            if (wp.lat != 0.0 || wp.lng != 0.0) {
                wp
            } else if (!wp.name.isNullOrBlank()) {
                try {
                    logger.info("Geocoding intermediate waypoint '${wp.name}'...")
                    val geo = geocoder(wp.name)
                    if (geo != null) {
                        LocationCoords(geo.coords.lat, geo.coords.lng, geo.displayName)
                    } else {
                        logger.warn("Could not geocode LLM waypoint '${wp.name}'")
                        null
                    }
                } catch (e: Exception) {
                    logger.warn("Could not geocode LLM waypoint '${wp.name}': ${e.message}")
                    null
                }
            } else {
                null
            }
        }
    }

    var currentTripPlan = initialTripPlan
    var resolvedWaypoints = geocodeWaypoints(currentTripPlan.waypoints)

    if (resolvedWaypoints.isNotEmpty()) {
        val valResult =
            WaypointValidator.validateWaypoints(resolvedWaypoints, startGeo.coords, endGeo.coords)

        if (!valResult.isValid) {
            if (valResult.validWaypoints.isNotEmpty()) {
                logger.warn(
                    "LLM waypoint validation failed: ${valResult.reason}. " +
                        "Accepting the ${valResult.validWaypoints.size} valid waypoint(s) and dropping the rest."
                )
                resolvedWaypoints = valResult.validWaypoints
            } else {
                logger.warn(
                    "LLM waypoint validation failed: ${valResult.reason}. " +
                        "Retrying trip planning with LLM (attempt 2/2)..."
                )
                val retryTripPlan =
                    llm.planTrip(
                        startName = startGeo.displayName,
                        endName = endGeo.displayName,
                        startCoords = startGeo.coords,
                        endCoords = endGeo.coords,
                        days = days,
                        userPrompt = prompt,
                    )
                val retryWaypoints = geocodeWaypoints(retryTripPlan.waypoints)
                val retryValResult =
                    WaypointValidator.validateWaypoints(
                        retryWaypoints,
                        startGeo.coords,
                        endGeo.coords,
                    )

                if (retryValResult.validWaypoints.isNotEmpty()) {
                    logger.info(
                        "Retry attempt produced ${retryValResult.validWaypoints.size} valid waypoint(s)."
                    )
                    resolvedWaypoints = retryValResult.validWaypoints
                    currentTripPlan = retryTripPlan
                } else {
                    logger.warn(
                        "LLM waypoint validation failed on retry attempt. " +
                            "Clearing invalid waypoints and using deterministic route fallback."
                    )
                    resolvedWaypoints = emptyList()
                }
            }
        }
    }

    return WaypointResolution(resolvedWaypoints, currentTripPlan)
}
