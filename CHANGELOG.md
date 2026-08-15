# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org).

## [Unreleased]

### Changed
- Enforced strict configuration injection across core spatial and AI planning services.

## [0.6.0] - 2026-08-11

### Added
- Sharded 1-degree spatial tile disk caching for faster regional POI queries.
- Hierarchical slippy-map tile storage for rendered map assets.
- Regional corridor and coast-to-coast test matrix (`scripts/matrix_cases.py`).
- Technical documentation hub under `docs/`.
- Standalone unversioned JAR task (`pathpress-standalone.jar`).
- Aspirational project `ROADMAP.md`.

### Changed
- Increased default HTTP LLM request timeout from 60s to 120s.
- Adjusted overnight town scoring to favor coastal towns and villages.
- Added boundary buffer support to spatial POI tile caching.
- Reduced memory allocation and GC pressure during POI indexing and PDF generation.
- Streamlined PBF path resolution and graph cache isolation.

### Fixed
- Excluded county-level results and fixed fallback logic in geocoding.
- Fixed regex word boundaries and plural matching for town scoring keywords.
- Hardened road network snapping for off-road waypoint coordinates.
- Fixed POI description formatting for mountain peaks.
- Hardened multi-state boundary resolution and waypoint validation.
- Hardened settlement geocoding and drivable road network snapping.

## [0.5.0] - 2026-08-08

### Added
- Free OpenStreetMap US state PBF downloader script (`scripts/download_state.sh`).
- Location pair matrix automated test runner and test cases (`scripts/run_matrix_test.py`).
- Model latency and inference benchmark suite (`scripts/benchmark_models.py`).
- Dedicated `--graph` storage isolation supporting multi-state and regional PBF routing.

### Changed
- Bumped project version to `0.5.0` across build configuration and documentation.
- Standardized generic `SNAPSHOT` fallback in Gradle and Kotlin metadata.
- Updated map tile stitcher User-Agent to `PathPress/0.5.0`.

### Fixed
- Resolved GraphHopper graph cache collisions (`PointOutOfBoundsException`) when switching states.
- Hardened waypoint linear pruning and corridor polyline validation.

## [0.4.0] - 2026-08-05

### Added
- Native CartoDB Voyager map tile stitcher with Web Mercator projection.
- Antialiased route rendering with start/end pins and CartoDB tile attribution.
- Automatic turn-by-turn Google Maps QR code appendix in PDF generation.
- Support for configurable distance units (`--units imperial` and `--units metric`).

### Changed
- Enhanced PDF export layout with Merriweather & Inter Google fonts and responsive page breaks.

### Fixed
- Resolved map tile coordinate bounding math and polyline drawing boundaries.

## [0.3.0] - 2026-08-03

### Added
- Overnight town scorer weighted by lodging, attractions, and dining density.
- Theme park boundary clustering for Disney, Universal, and SeaWorld.
- Spatial grid index partitioned at 0.1-degree resolution for fast POI lookups.
- Disused/closed tag filtering to exclude abandoned POIs.
- Dual-layer Jackson JSON POI disk caching (`.pois_cache/`).

### Changed
- Refactored POI extraction into modular components (ranking, clustering, spatial indexing).

### Fixed
- Fixed POI duplicates across overlapping leg corridors.

## [0.2.0] - 2026-08-01

### Added
- Pluggable multi-LLM architecture supporting Gemini, Claude, OpenAI, and Ollama.
- Silent fallback degradation for offline and deterministic trip planning.
- Linear waypoint pruning to eliminate hallucinated LLM waypoints.
- Clikt 5.1.0 CLI entry point with structured argument parsing.
- Rich terminal output reporter with `--verbose` mode.

### Changed
- Decoupled routing from AI narrative to prevent hallucinated waypoints.

### Fixed
- Fixed waypoint ordering validation failures and unbounded routing probe retry loops.

## [0.1.0] - 2026-07-28

### Added
- Initial release of PathPress: hybrid AI road trip planner using OpenStreetMap and GraphHopper.
- 2-pass streaming OSM PBF reader.
- Publication-ready PDF itinerary export with OpenHTMLtoPDF.

[Unreleased]: https://github.com/huangsam/pathpress/compare/v0.6.0...HEAD
[0.6.0]: https://github.com/huangsam/pathpress/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/huangsam/pathpress/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/huangsam/pathpress/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/huangsam/pathpress/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/huangsam/pathpress/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/huangsam/pathpress/releases/tag/v0.1.0
