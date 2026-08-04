# AGENTS.md

1. No LLM may invent a place. They all must come from OSM/GraphHopper only.
2. POI descriptions are derived from OSM tags, never generated.
3. Every LLM failure degrades silently. Log complaints and RCA them.
4. Anything reaching `raw()` or the CSS template must be escaped.
5. A guard test doesn't count until it has been observed failing.
6. Verify with `./gradlew ktfmtFormat test` and report pass count.
7. Unused code is deleted, not wired up. Reviving it needs a measurement.
