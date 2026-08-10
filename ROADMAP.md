# PathPress Project Roadmap

PathPress is an offline-first, publication-grade road trip planning and itinerary generation engine. This roadmap outlines key architectural horizons across open geo-data federation, spatial algorithms, and export integrations.

> [!NOTE]
> This roadmap reflects aspirational architectural horizons and long-term research directions; it does not represent a fixed timeline or binding product commitment.
> Last reviewed: Q3 2026.

---

## Theme 1: Multi-Source Geo-Data Ingestion & Dataset Fusion

* **Overture Maps Foundation GeoParquet Ingestion (`places` Theme)**
  Asynchronously ingest the Linux Foundation's 50M+ commercial POI dataset directly into PathPress's decoupled [`.pois_cache/tiles/{lat}/{lng}.json`](.pois_cache/tiles/) spatial shards using DuckDB/GeoParquet, solving restaurant staleness while keeping GraphHopper routing 100% untouched.

---

## Theme 2: Core Engine & Algorithmic Innovations

* **Algorithmic "Scenic Sinuosity" & Topology Scoring**
  Calculate a **Scenic Quality Index (SQI)** for every road segment by evaluating mathematical road curvature (sinuosity ratio), elevation variance (mountain passes), and land-cover polygons (coastal shorelines, national park boundaries).

* **Daylight & Solar Ephemeris Pacing (Golden Hour Alignment)**
  Embed astronomical solar position calculations (azimuth/elevation) to align arrival at prime viewpoints with golden hour/sunset, while enforcing daylight safety constraints for unlit mountain routes.

* **Detour Budget Knapsack Optimization (Corridor TSP)**
  Model stopover POI selection as a **Constrained Multi-Choice Knapsack Problem**, maximizing itinerary quality within a user-specified detour time budget (e.g., *"max 45 min total detours"*).

---

## Theme 3: Ecosystem, Exports & Distribution

* **Navigation & GPX / GeoJSON Exports**
  Export `.gpx` track segments and waypoints for Garmin/Gaia GPS, along with Google My Maps `.kml` and native Apple Maps multi-stop deep links.

* **Calendar Integration (`.ics` Export)**
  Convert daily driving legs, stopover towns, and meal stops into structured `.ics` calendar events for Google Calendar, Apple Calendar, and Outlook.

* **Standalone Interactive HTML Export (`pathpress export html`)**
  Generate a single-file, self-contained HTML publication (`pathpress plan -o report.html` or `pathpress export html`) featuring an embedded CDN-powered MapLibre/Leaflet map viewer, elevation cross-sections, and daily itinerary cards ready for offline sharing or GitHub Pages hosting.

* **Native Packaging & Distribution**
  Distribution via a Homebrew formula (`brew install huangsam/tap/pathpress`) packaging a self-contained runtime / shadow JAR, eliminating local JVM dependency friction without GraalVM native image build complexity.
