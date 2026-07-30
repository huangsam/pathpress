# PathPress

**PathPress** is an AI-powered road trip planner that turns trip ideas into accurate, multi-day itineraries and publication-ready PDF travel guides using real map data.

<img src="images/roadtrip.png" alt="PathPress Sample Itinerary" width="600" />

<sub>*Sample itinerary cover page presenting a trip overview before diving into day-by-day legs.*</sub>

## Motivation

Pure LLM travel planners frequently suffer from "spatial amnesia"—hallucinating routes, driving times, and non-existent locations. Conversely, standard navigation apps lack contextual storytelling and custom trip vibes.

**PathPress** bridges this gap by decoupling deterministic spatial routing from AI narrative curation—combining real-world map data with LLM storytelling to generate physically accurate, publication-ready travel guides.

## Key Features

- 🗺️ **Deterministic Spatial Engine**: OpenStreetMap corridor filtering and GraphHopper leg splitting with zero geographic hallucinations.
- 🧠 **Pluggable LLM Architecture**: Enriches verified POIs using Gemini, Claude, OpenAI, local Ollama, or offline fallback.
- 📄 **Publication-Ready PDF Export**: Self-contained itineraries rendered with custom styling and turn-by-turn QR navigation links.
- 🚘 **Fuzzy Geocoding & Scenic Profiles**: Automatic location resolution and custom weighting for scenic or coastal drives.

## Quick Start

### 1. Build the Project
```bash
./gradlew build
```

### 2. Run PathPress Examples
```bash
# Example 1: Quick offline run (no LLM required)
java -jar build/libs/pathpress-0.1.0-standalone.jar --start "San Jose, CA" --end "Monterey, CA" --days 1 --pbf data/california-latest.osm.pbf

# Example 2: Multi-day AI trip with Ollama, custom prompt & imperial units
java -jar build/libs/pathpress-0.1.0-standalone.jar --start "San Jose, CA" --end "San Diego, CA" --days 2 --pbf data/california-latest.osm.pbf --llm-provider ollama --llm-model gemma4:31b-mlx --prompt "toddler friendly, coastal highway route, prefer scenic beach town or historic village for overnight stay" --units imperial
```

> 💡 **Tip**: Run `java -jar build/libs/pathpress-0.1.0-standalone.jar --help` to view all available CLI flags, default values, and usage options.

### 3. Downloading Map Data for Other US States
PathPress includes a helper script to fetch free OpenStreetMap data for any US state on demand:
```bash
# Download Texas map data
./scripts/download_state.sh texas

# Run PathPress using the downloaded Texas map data
java -jar build/libs/pathpress-0.1.0-standalone.jar --start "Austin, TX" --end "San Antonio, TX" --pbf data/texas-latest.osm.pbf
```
