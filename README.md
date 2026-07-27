# PathPress

A robust, hybrid AI road trip planner using OpenStreetMap data and GraphHopper. Calculates dynamic multi-day driving legs, extracts POIs, supports natural language prompts with pluggable LLMs (Gemini, Claude, OpenAI, Ollama), fuzzy geocodes location names, and renders modern PDF itineraries.

## Features

- **Hybrid Routing Pipeline**: GraphHopper spatial engine + pluggable AI trip themes
- **Fuzzy Geocoding**: Resolves location names and typos (e.g. `"San Josee"` -> `"San Jose, CA"`)
- **Scenic Profiles**: Custom weighting for scenic drives and coastal views
- **Multi-Day Legs**: Automatically divides routes into daily segments based on pacing
- **Pluggable LLM Support**: Works with Gemini, Claude, OpenAI, Ollama, or pure offline fallback
- **PDF Generation**: Creates modern visual PDF itineraries using openhtmltopdf (JVM-native)
- **Google Maps Integration**: Generates zero-dependency navigation URLs

## Dependencies

| Library | Purpose |
|---------|---------|
| `com.graphhopper:graphhopper-core:11.0` | Spatial routing engine |
| `com.github.ajalt.clikt:clikt:4.2.2` | CLI argument parsing |
| `com.openhtmltopdf:openhtmltopdf-core:1.0.10` | HTML to PDF conversion |
| `com.openhtmltopdf:openhtmltopdf-pdfbox:1.0.10` | PDF Box renderer |
| `com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2` | JSON parsing |
| `com.gradleup.shadow:8.3.6` | Standalone Fat-JAR builder |

## Quick Start

### 1. Build the Project
```bash
./gradlew build
```

### 2. Build Standalone Runnable JAR
```bash
./gradlew shadowJar
```

### 3. Run PathPress Examples

```bash
# Example 1: Multi-day trip with Ollama (qwen3.6:35b-mlx), town-centric pacing & verbose POI breakdown
./gradlew run --args="--start 'San Francisco, CA' --end 'San Luis Obispo, CA' --days 2 --llm-provider ollama --llm-model 'qwen3.6:35b-mlx' --prompt 'scenic coastal highlights' --verbose"

# Example 2: 1-Day trip with coastal scenic prompt & terminal breakdown
./gradlew run --args="--start 'San Jose, CA' --end 'Monterey, CA' --days 1 --prompt 'coastal scenic bakeries' --verbose"

# Example 3: Standalone Fat-JAR
java -jar build/libs/pathpress-1.2.0-all.jar --start "San Jose" --end "San Diego" --days 3 --llm-provider gemini --output itinerary.pdf
```

## CLI Usage

```bash
pathpress [OPTIONS]

Options:
  --start=<text>         Starting location name or lat,lng coordinates (required)
  --end=<text>           Destination location name or lat,lng coordinates (required)
  --days=<int>           Number of days to spread the trip across (default: 1)
  --prompt=<text>        Natural language trip themes, vibe, or preferences
  --profile=<text>       Routing profile ('scenic' or 'car') (default: scenic)
  --llm-provider=<text>  LLM provider: gemini, claude, openai, ollama, or none (default: none)
  --llm-model=<text>     Model name for LLM (default: qwen3.6:35b-mlx)
  --llm-key=<text>       API Key for the chosen LLM provider
  --llm-url=<text>       Endpoint URL for LLM (e.g. http://localhost:11434/api/generate)
  -v, --verbose          Print detailed real POI corridor breakdown, distances & storytelling to terminal
  --output=<text>        Output PDF file path (default: itinerary.pdf)
  --pbf=<text>           Path to OSM PBF file (default: california-latest.osm.pbf)
  --graph=<text>         GraphHopper graph storage directory (default: .graphhopper)
```

## License

MIT
