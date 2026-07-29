# PathPress

A robust, hybrid AI road trip planner using OpenStreetMap data and GraphHopper. Calculates dynamic multi-day driving legs, extracts real POI corridors, supports natural language trip themes with pluggable LLMs (Gemini, Claude, OpenAI, Ollama), fuzzy geocodes locations, and renders publication-ready PDF itineraries.

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
# Example 1: Multi-day trip with Ollama (gemma4:31b-mlx), town-centric pacing & verbose POI breakdown
./gradlew run --args="--start 'San Francisco, CA' --end 'San Luis Obispo, CA' --days 2 --llm-provider ollama --llm-model 'gemma4:31b-mlx' --prompt 'scenic coastal highlights' --verbose"

# Example 2: 1-Day trip with coastal scenic prompt & terminal breakdown
./gradlew run --args="--start 'San Jose, CA' --end 'Monterey, CA' --days 1 --prompt 'coastal scenic bakeries' --verbose"
```
