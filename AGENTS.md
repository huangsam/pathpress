# AGENTS.md

1. No LLM may invent a place. They all must come from OSM/GraphHopper only.
2. POI descriptions are derived from OSM tags, never generated.
3. The LLM's primary surface is `planTrip` and `filterNearbyPois`, nothing else.
4. Every LLM failure degrades silently. Log complaints and RCA them.
5. Anything reaching `raw()` or the CSS template must be escaped.
6. A guard test doesn't count until it has been observed failing.
7. Verify with `./gradlew ktfmtFormat test` and report pass count.
