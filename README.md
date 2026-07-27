# PathPress

A robust, offline-first CLI tool for planning road trips using OpenStreetMap data. Calculates dynamic multi-day driving legs, extracts POIs, and renders native PDF itineraries using strictly JVM-native tools.

## Features

- **Offline Routing**: Uses GraphHopper 11.0 for fast, offline route calculation
- **Multi-Day Legs**: Automatically divides routes into daily segments based on pacing
- **PDF Generation**: Creates clean, modern PDF itineraries using openhtmltopdf (JVM-native)
- **Google Maps Integration**: Generates zero-dependency navigation URLs
- **POI Extraction**: Filter Points of Interest near route locations

## Dependencies (All JVM-Native)

| Library | Purpose |
|---------|---------|
| `com.graphhopper:graphhopper-core:11.0` | Spatial routing engine |
| `com.openhtmltopdf:openhtmltopdf-core:1.1.20` | HTML to PDF conversion |
| `com.openhtmltopdf:openhtmltopdf-pdfbox:1.1.20` | PDF backend (PDFBox) |
| `com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2` | JSON processing |
| `com.github.ajalt.clikt:clikt-jvm:4.3.0` | CLI argument parsing |

## Architecture

```
src/main/kotlin/
├── Main.kt              # CLI entrypoint with Clikt-based argument parsing
├── RouteCalculator.kt   # GraphHopper wrapper for route calculation
├── MapUrlFormatter.kt   # Google Maps URL generation (zero dependencies)
├── PdfExporter.kt       # PDF rendering pipeline using openhtmltopdf
└── DataModels.kt        # Kotlin data classes with null safety (POI, RouteLeg, Route)
```

## Requirements

- JDK 17 or higher
- Kotlin 2.0+
- A local OpenStreetMap `.osm.pbf` file (e.g., California)

## Quick Start

### 1. Download an OSM PBF File

Download a California PBF file from Geofabrik:
```bash
curl -L -o california.osm.pbf https://download.geofabrik.de/north-america/us/california-latest.osm.pbf
```

### 2. Build the Project

```bash
./gradlew build
```

### 3. Run PathPress

```bash
# Using default PBF path (california.osm.pbf)
./gradlew run --args='--start "San Jose" --end "San Diego" --days 4 --output itinerary.pdf'

# Or specify a custom PBF path
./gradlew run --args='--pbf /path/to/california.osm.pbf --start "San Jose" --end "San Diego" --days 7'
```

## Usage

```bash
pathpress [OPTIONS]

Options:
  --start <location>   Starting location (name or lat,lng coordinates)
  --end <location>     Destination location (name or lat,lng coordinates)
  --days <number>      Number of days to spread the trip across (default: 1)
  --output <file>      Output PDF file path (default: itinerary.pdf)
  --pbf <file>         Path to OSM PBF file (env: PATHPRESS_PBF)
  --graph <dir>        GraphHopper graph storage directory
```

## Example Output

The tool generates a PDF itinerary containing:

- **Trip Metadata**: Start/end locations, total distance/duration
- **Daily Legs**: Breakdown by day with distances and estimated times
- **Navigation Links**: Google Maps anchor tags for each leg
- **POI Sections**: Nearby Points of Interest (cafes, rest areas, etc.)

### Sample PDF Page Layout

```
PathPress Itinerary
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Trip Details
  From: San Jose
  To: San Diego
  Total Distance: 724.3 km
  Estimated Duration: 7h 15m
  Daily Legs: 4

Itinerary
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Day 1 of 4
  San Jose → San Diego
  Distance: 181.1 km | Estimated Time: 1h 53m
  [View on Google Maps]

  Nearby Points of Interest
    - Cafe (sample)
    - Rest Area (sample)

[...additional days...]
```

## Data Models

All data classes use idiomatic Kotlin null safety:

```kotlin
// RouteLeg - Daily segment with optional computed values
data class RouteLeg(
    val startLat: Double,
    val startLng: Double,
    val endLat: Double,
    val endLng: Double,
    val dayNumber: Int,
    val totalDays: Int,
    val distanceMeters: Double?,  // Nullable, computed at runtime
    val durationSeconds: Double?   // Nullable, computed at runtime
)

// Route - Complete trip with calculated values
data class Route(
    val legs: List<RouteLeg>,
    val totalDistanceMeters: Double,
    val totalDurationSeconds: Double
)
```

## Extending PathPress

### Adding POI Filtering

The `RouteCalculator.filterNearbyPois()` method is a placeholder. To implement full POI filtering:

1. Add an OSM parser dependency (e.g., `osm-dataloader`)
2. Load POI data during GraphHopper initialization
3. Implement spatial queries using the route coordinates

### Custom HTML Templates

Modify `PdfExporter.generateHtml()` to customize the PDF output format, styling, or content structure.

## License

MIT
