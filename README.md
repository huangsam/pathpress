# PathPress

A robust, hybrid AI road trip planner using OpenStreetMap data and GraphHopper. Calculates dynamic multi-day driving legs, extracts real POI corridors, supports natural language trip themes with pluggable LLMs (Gemini, Claude, OpenAI, Ollama), fuzzy geocodes locations, and renders publication-ready PDF itineraries.

## Motivation

Pure LLM travel planners frequently suffer from "spatial amnesia"—hallucinating closed businesses, incorrect driving times, and geography that doesn't exist. Conversely, standard navigation apps provide turn-by-turn directions without contextual storytelling, custom trip vibes, or easy offline export.

**PathPress** bridges this gap by decoupling spatial routing from narrative curation:
1. **Spatial Grounding**: Real OpenStreetMap PBF spatial indexing and GraphHopper routing guarantee 100% physical accuracy for roads, drive times, and POI corridors.
2. **AI Narrative Curation**: Pluggable LLM providers (Ollama, Gemini, Claude, OpenAI) enrich verified real-world points of interest with personalized trip themes.
3. **Publication-Ready Offline Guides**: Generates self-contained, beautifully styled PDF itineraries complete with QR navigation links for turn-by-turn routing on the road.

## Key Features

- 🗺️ **Deterministic Spatial Engine**: Real OpenStreetMap corridor filtering and GraphHopper multi-day leg splitting with zero geographic hallucinations.
- 🧠 **Pluggable LLM Architecture**: Native support for Gemini, Claude, OpenAI, local Ollama (`gemma4:31b-mlx`), or pure offline fallback mode.
- 📄 **Offline Visual PDF Export**: Renders modern itineraries using OpenHTMLtoPDF, custom CSS, vector icons, and interactive QR navigation appendices.
- 🚘 **Fuzzy Geocoding & Scenic Profiles**: Resolves location names/typos automatically and applies custom weighting for scenic and coastal drives.

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
# Example 1: Multi-day trip with Ollama (gemma4:31b-mlx), town-centric pacing & verbose POI breakdown
./gradlew run --args="--start 'San Francisco, CA' --end 'San Luis Obispo, CA' --days 2 --llm-provider ollama --llm-model 'gemma4:31b-mlx' --prompt 'scenic coastal highlights' --verbose"

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
  --llm-model=<text>     Model name for LLM (default: gemma4:31b-mlx)
  --llm-key=<text>       API Key for the chosen LLM provider
  --llm-url=<text>       Endpoint URL for LLM (e.g. http://localhost:11434/api/generate)
  -v, --verbose          Print detailed real POI corridor breakdown, distances & storytelling to terminal
  --output=<text>        Output PDF file path (default: itinerary.pdf)
  --pbf=<text>           Path to OSM PBF file (default: california-latest.osm.pbf)
  --graph=<text>         GraphHopper graph storage directory (default: .graphhopper)
```
