# CLI & Configuration Reference

PathPress provides a unified command-line interface implemented using Clikt, complemented by Python helper utilities for spatial data management and automated matrix testing.

---

## PathPress Standalone JAR CLI

```bash
java -jar build/libs/pathpress-standalone.jar [OPTIONS]
```

### Core Trip Planning Options

| Option | Type | Default | Description |
|---|---|---|---|
| `--start` | String | *(Required)* | Origin location name (e.g. `"Seattle, WA"`) or `lat,lng` coordinates (`"47.6062,-122.3321"`). |
| `--end` | String | *(Required)* | Destination location name (e.g. `"Miami, FL"`) or `lat,lng` coordinates (`"25.7617,-80.1918"`). |
| `--days` | Int | `1` | Number of days to spread the trip across. Automatically computes daily leg segmentation. |
| `--output` | String | `itinerary.pdf` | Destination file path for the generated PDF travel guidebook. |
| `--pbf` | String | Auto-detected | Path to the OpenStreetMap PBF spatial file (e.g. `data/california-latest.osm.pbf`). |
| `--graph` | String | `.graphhopper` | Base directory for GraphHopper graph caching (automatically isolates to `.graphhopper/<slug>`). |
| `--profile` | String | `scenic` | Vehicle routing profile (`scenic` for scenic byways/coastlines, or `car` for fastest highways). |
| `--distance-unit` | String | `imperial` | Measurement system for distances and elevations (`imperial` for mi/ft, or `metric` for km/m). |
| `-v, --verbose` | Flag | `false` | Prints detailed corridor breakdown, POI metrics, and execution times to terminal stdout. |

### Hybrid AI & Narrative Options

| Option | Type | Default | Description |
|---|---|---|---|
| `--llm-provider` | String | `none` | AI provider for narrative augmentation (`gemini`, `claude`, `openai`, `ollama`, or `none`). |
| `--llm-model` | String | Provider Default | Model name (e.g. `gemini-1.5-pro`, `claude-3-5-sonnet-20241022`, `gpt-4o`, `llama3`). |
| `--llm-key` | String | From Env | API key for the chosen LLM provider (falls back to standard environment variables). |
| `--llm-url` | String | Provider Default | Custom endpoint URL for local or self-hosted LLMs (e.g. `http://localhost:11434` for Ollama). |
| `--prompt` | String | None | Natural language trip themes, preferences, or vibes (e.g. `"family friendly bakeries and coastal lighthouses"`). |
| `--pois-per-leg` | Int | `6` | Maximum number of OpenStreetMap POIs to curate and display per daily leg. |

---

## Environment Variables

PathPress automatically reads standard environment variables when CLI options are omitted:

| Environment Variable | Equivalent CLI Option | Description |
|---|---|---|
| `PATHPRESS_PBF` | `--pbf` | Default path to OSM PBF spatial file. |
| `PATHPRESS_GRAPH_DIR` | `--graph` | Base directory for GraphHopper graph caches. |
| `GEMINI_API_KEY` | `--llm-key` | Google Gemini API key. |
| `ANTHROPIC_API_KEY` | `--llm-key` | Anthropic Claude API key. |
| `OPENAI_API_KEY` | `--llm-key` | OpenAI API key. |
| `OLLAMA_HOST` | `--llm-url` | Base URL for Ollama local inference. |

---

## Standardized Process Exit Codes

PathPress returns standardized process exit codes to facilitate CI/CD automation and shell scripting:

| Code | Constant | Meaning |
|---|---|---|
| **`0`** | `ExitCode.SUCCESS` | Execution completed successfully; PDF guidebook generated. |
| **`1`** | `ExitCode.USAGE_ERROR` | Invalid CLI arguments, missing required options, or unrecognized flags. |
| **`2`** | `ExitCode.ROUTING_ERROR` | Graph connectivity failure, road disconnected, or point outside PBF boundary. |
| **`3`** | `ExitCode.GEOCODING_ERROR` | Location name could not be resolved to spatial coordinates. |
| **`4`** | `ExitCode.INTERNAL_ERROR` | Unhandled runtime exception or PDF compilation failure. |

---

## Python Helper Utilities

### Spatial PBF Downloader (`scripts/download_pbf.py`)
```bash
# Download a specific state or region
python3 scripts/download_pbf.py california
python3 scripts/download_pbf.py us-northeast
python3 scripts/download_pbf.py usa

# Bulk download all 50 states + DC in parallel
python3 scripts/download_pbf.py --all --workers 4

# Check status of downloaded files in data/
python3 scripts/download_pbf.py --list
```

### Automated Matrix Test Runner (`scripts/run_matrix_test.py`)
```bash
# State matrix execution (Tier 1)
python3 scripts/run_matrix_test.py --state california
python3 scripts/run_matrix_test.py --state all

# Regional matrix execution (Tier 2)
python3 scripts/run_matrix_test.py --region us-west
python3 scripts/run_matrix_test.py --region us-pacific --batch-states

# Nationwide coast-to-coast matrix execution (Tier 3)
python3 scripts/run_matrix_test.py --nationwide
```
