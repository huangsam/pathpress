# AGENTS.md

1. No LLM may invent a place. They all must come from OSM/GraphHopper only.
2. POI descriptions are derived from OSM tags, never generated.
3. Every LLM failure degrades silently. Log complaints and RCA them.
4. Anything reaching `raw()` or the CSS template must be escaped.
5. A guard test doesn't count until it has been observed failing.
6. Verify with `CI=1 ./gradlew ktfmtFormat test` and report pass count.
7. Unused code is deleted, not wired up. Reviving it needs a measurement.
8. Two values that must agree are never supplied separately.
9. User-text matching uses word boundaries instead of bare `contains`.
10. A failure must never return a value indistinguishable from success.
