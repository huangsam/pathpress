#!/usr/bin/env bash
# ==============================================================================
# PathPress - US State OSM PBF Downloader
# Fetches free OpenStreetMap spatial PBF files for any US state from Geofabrik.
# ==============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
DATA_DIR="$ROOT_DIR/data"

mkdir -p "$DATA_DIR"

if [ -z "$1" ]; then
    echo "Usage: ./scripts/download_state.sh <state_name>"
    echo "Examples:"
    echo "  ./scripts/download_state.sh texas"
    echo "  ./scripts/download_state.sh new-york"
    echo "  ./scripts/download_state.sh florida"
    exit 1
fi

STATE_INPUT="$1"
# Format input state name to lowercase and replace spaces with hyphens
STATE_SLUG=$(echo "$STATE_INPUT" | tr '[:upper:]' '[:lower:]' | tr ' ' '-')

FILENAME="${STATE_SLUG}-latest.osm.pbf"
DEST_PATH="$DATA_DIR/$FILENAME"
URL="https://download.geofabrik.de/north-america/us/${FILENAME}"

echo "================================================================="
echo "PathPress OSM State Downloader"
echo "State: $STATE_INPUT -> $STATE_SLUG"
echo "URL:   $URL"
echo "Dest:  $DEST_PATH"
echo "================================================================="

if [ -f "$DEST_PATH" ]; then
    echo "✓ $FILENAME already exists in data/ directory."
    exit 0
fi

echo "Downloading $FILENAME from Geofabrik..."
if command -v curl >/dev/null 2>&1; then
    curl -L --progress-bar -o "$DEST_PATH" "$URL"
elif command -v wget >/dev/null 2>&1; then
    wget -O "$DEST_PATH" "$URL"
else
    echo "Error: Neither curl nor wget is installed on your system."
    exit 1
fi

if [ -f "$DEST_PATH" ] && [ -s "$DEST_PATH" ]; then
    echo "✓ Download complete: $DEST_PATH ($(du -h "$DEST_PATH" | cut -f1))"
    echo "You can now run PathPress specifying this OSM file if needed:"
    echo "  java -jar build/libs/pathpress-0.5.0-standalone.jar --start \"Austin, TX\" --end \"San Antonio, TX\" --pbf data/$FILENAME ..."
else
    echo "❌ Download failed or file is empty."
    rm -f "$DEST_PATH"
    exit 1
fi
