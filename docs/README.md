# PathPress Documentation

Welcome to the PathPress technical documentation. This documentation suite provides an in-depth architecture blueprint, spatial routing engine specifications, itinerary planning heuristics, and verification guides.

---

## Documentation Index

| Document | Description |
|---|---|
| 📐 [**Architecture Blueprint**](architecture.md) | High-level system architecture, end-to-end Mermaid dataflow, core design principles, and component responsibilities. |
| 🗺️ [**Spatial Data & Routing Engine**](spatial-routing.md) | OpenStreetMap PBF hierarchy (State, Regional, Nationwide), GraphHopper caching (`.graphhopper/<slug>`), memory scaling, and spatial indexing. |
| 🧭 [**Itinerary Planning & POI Engine**](itinerary-planning.md) | Multi-day leg segmentation algorithms, overnight settlement snapping, deterministic OSM tag extraction, and silent AI degradation. |
| 🧪 [**Automated Matrix Testing**](testing-matrix.md) | The 3-tier automated test matrix runner (`--state`, `--region`, `--nationwide`), Geofabrik boundary maps, benchmarks, and verification rules. |
| 📄 [**PDF Visual Rendering & Escaping**](pdf-rendering.md) | OpenHTMLtoPDF engine, dynamic vector SVG elevation profiles, layout design, and strict HTML/CSS template escaping. |
| 💻 [**CLI & Configuration Reference**](cli-reference.md) | Complete CLI options, AI/LLM configurations, environment variables, standardized exit codes, and Python helper scripts. |

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
