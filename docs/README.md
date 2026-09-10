# PathPress Documentation

Welcome to the PathPress technical documentation. This documentation suite provides an in-depth architecture blueprint, spatial routing engine specifications, itinerary planning heuristics, and verification guides.

---

## Documentation Index

| Document | Description |
|---|---|
| [**Architecture Blueprint**](architecture.md) | System architecture, dataflow, and component design. |
| [**Spatial Data & Routing Engine**](spatial-routing.md) | OSM PBF hierarchy, GraphHopper caching, and spatial indexing. |
| [**Itinerary Planning & POI Engine**](itinerary-planning.md) | Leg segmentation, settlement snapping, and OSM POI extraction. |
| [**Automated Matrix Testing**](testing-matrix.md) | 3-tier test matrix runner, Geofabrik maps, and benchmarks. |
| [**PDF Visual Rendering & Escaping**](pdf-rendering.md) | OpenHTMLtoPDF rendering, SVG elevation profiles, and template escaping. |
| [**CLI & Configuration Reference**](cli-reference.md) | CLI options, environment variables, exit codes, and helper scripts. |

---

## Quick Navigation

```
docs/
├── README.md               # Documentation Index (this file)
├── architecture.md         # System Blueprint & Component Interactions
├── spatial-routing.md      # PBF Tiers & GraphHopper Caching (.graphhopper/<slug>)
├── itinerary-planning.md   # Daily Leg Segmentation & OSM POI Ground Truth
├── testing-matrix.md       # 3-Tier Matrix Test Suite & Geofabrik Taxonomy
├── pdf-rendering.md        # OpenHTMLtoPDF, SVG Elevation & Template Escaping
└── cli-reference.md        # CLI Flags, Environment Variables & Exit Codes
```

---

## Related References
- [AGENTS.md](../AGENTS.md): Inviolable operational engineering rules and quality guardrails.
- [README.md](../README.md): Project overview, prerequisites, and quickstart guide.
