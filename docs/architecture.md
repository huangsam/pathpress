# PathPress Architecture Blueprint

PathPress is an AI-augmented, deterministic road trip planner. It generates multi-day driving itineraries and publication-ready PDF guidebooks using OpenStreetMap (OSM) spatial data and GraphHopper routing.

---

## Core Architectural Principles

1. **Deterministic Ground Truth**: All geographic locations, routing geometry, driving distances, and points of interest (POIs) originate exclusively from OpenStreetMap and GraphHopper. No LLM may invent a place or synthesize non-existent geographic facts.
2. **POI Descriptions from OSM Tags**: POI metadata (names, categories, opening hours, elevation, historic notes) are parsed directly from OSM key-value tags.
3. **Silent AI Degradation**: LLM narrative augmentation is strictly additive. If an LLM call fails, times out, or has no configured API key, the system silently degrades to deterministic OSM tag summaries without interrupting itinerary generation or PDF compilation.
4. **Isolated Spatial Caching**: GraphHopper graphs are cached on disk per dataset slug under `.graphhopper/<slug>`, ensuring clean isolation across individual states, regions, and nationwide extracts.
5. **Template & CSS Escaping**: All user inputs, LLM outputs, and OSM tag strings reaching HTML/CSS templates are strictly sanitized and escaped before rendering.

---

## End-to-End System Dataflow

```mermaid
flowchart LR
    CLI["CLI Input"] --> GH["Spatial Routing"]
    GH --> Seg["Leg Segmentation"]
    Seg --> POI["POI Extraction"]
    POI --> AI["Hybrid AI"]
    AI --> PDF["PDF Export"]
```

---

## Subsystem Breakdown

### CLI & Orchestration Layer
- **`com.pathpress.Main`**: Clikt CLI defining user options (`--start`, `--end`, `--days`, `--pbf`, `--prompt`, etc.).
- **`com.pathpress.TripPlannerOrchestrator`**: Central coordinator sequencing geocoding, routing, segmentation, POI extraction, and export.

### Spatial & Routing Layer
- **`com.pathpress.pbf.PbfPathResolver`**: Extracts dataset slugs (e.g. `california`, `us`) and isolates `.graphhopper/<slug>` cache directories.
- **`com.pathpress.routing.GraphHopperRouter`**: Manages GraphHopper lifecycle, graph cache loading, and `scenic`/`car` vehicle routing.
- **`com.pathpress.routing.GeocodingService`**: Resolves place names and `lat,lng` strings with local and online fallback.

### Itinerary Segmentation & POI Engine
- **`com.pathpress.planner.TripSegmenter`**: Splits multi-day routes into daily intervals and snaps overnight stops to populated settlements.
- **`com.pathpress.poi.PoiExtractor`**: Buffers route polylines and queries high-value OSM tags (`tourism`, `historic`, `natural`, `amenity`).

### Hybrid AI & Resilience Layer
- **`com.pathpress.llm.LlmClient`**: Multi-provider AI interface (Gemini, Claude, OpenAI, Ollama, and No-Op fallback).
- **`com.pathpress.llm.PromptBuilder`**: Builds prompt payloads grounded strictly in verified OSM facts.
- **Degradation Handler**: Silently handles rate limits and API outages without interrupting itinerary or PDF generation.

### PDF Visual & Export Engine
- **`com.pathpress.export.PdfExporter`**: Renders XHTML templates into multi-page vector PDFs via OpenHTMLtoPDF.
- **`com.pathpress.export.ElevationSvgGenerator`**: Generates vector SVG elevation charts with climbs, descents, and waypoints.

---

## Subsystem Documentation Map

For deep dives into individual subsystems, refer to the dedicated guides:

| Document | Focus Area |
|---|---|
| [spatial-routing.md](spatial-routing.md) | OSM PBF data hierarchy, GraphHopper caching, memory tuning, and 2D/3D spatial sharding. |
| [itinerary-planning.md](itinerary-planning.md) | Daily leg segmentation algorithms, overnight stop heuristics, OSM tag filtering, and AI degradation. |
| [testing-matrix.md](testing-matrix.md) | 3-tier automated test matrix runner (`--state`, `--region`, `--nationwide`), benchmarks, and Geofabrik boundaries. |
| [pdf-rendering.md](pdf-rendering.md) | OpenHTMLtoPDF layout, dynamic vector SVG elevation charts, and HTML/CSS escaping security. |
| [cli-reference.md](cli-reference.md) | Complete CLI options, environment variable bindings, and standardized process exit codes. |
