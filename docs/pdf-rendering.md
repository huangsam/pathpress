# PDF Visual Rendering, Dynamic SVG & Template Security

PathPress compiles road trip itinerary data into publication-ready PDF guidebooks. It achives this through the use of OpenHTMLtoPDF, dynamic SVG charting, and sanitized CSS templates.

---

## Document Generation Pipeline

```mermaid
flowchart LR
    Data["Itinerary Model and SVGs"] --> Template["HTML/CSS Template Engine"]
    Template --> Engine["OpenHTMLtoPDF Engine"]
    Engine --> PDF["PDF Guidebook"]
```

---

## Document Layout & Visual Architecture

PathPress itineraries are formatted into clean, magazine-grade layouts designed for both digital viewing and high-resolution printing:

### Cover Page & Executive Overview
- **Header & Hero Title**: Origin ➔ Destination, trip duration in days, and total mileage/kilometer summary.
- **Trip Statistics Ribbon**: Total driving time, cumulative elevation gain/loss, total POIs curated, and routing profile used (`scenic` or `car`).
- **Overview Route Map**: Global route polyline thumbnail with marked overnight milestones.

### Day-by-Day Journey Spreads
- **Daily Leg Header**: Day number, start/end locations, leg distance, estimated driving duration, and overnight settlement.
- **Vector Elevation Profile**: Dynamic SVG chart showing elevation changes, climbs, and summits across that specific day.
- **Curated POI Cards**: OSM-derived attraction entries detailing names, descriptions, categories, opening hours, and elevation notes.
- **Narrative & Local Context**: Additive storytelling, scenic highlights, and driving tips.

### Navigation Appendix & QR Links
- **Turn-by-Turn Waypoints**: Milestone coordinate table.
- **Digital Navigation QR Codes**: Embedded QR codes allowing travelers to scan and open the exact route on mobile navigation apps.

---

## Dynamic Vector SVG Charting

### Elevation Profile Generation
The `ElevationSvgGenerator` dynamically calculates and renders scalable vector graphics (SVG) directly into the XHTML template:
- **Sampling**: Samples 3D polyline elevation coordinates from GraphHopper.
- **Path Smoothing**: Computes SVG Bézier curve paths (`<path d="..." />`) representing the terrain profile.
- **Gradient Fill**: Generates vertical gradient fills (`<linearGradient>`) highlighting valley-to-peak transitions.
- **Axis & Distance Markers**: Injects tick marks and distance milestones formatted according to the selected distance unit (`imperial` or `metric`).

---

## Security & Template Escaping (`AGENTS.md` Rule 4)

In accordance with `AGENTS.md` Rule 4:
> **"Anything reaching `raw()` or the CSS template must be escaped."**

### Injection Prevention Strategy
Because user prompts, LLM narrative text, and raw OpenStreetMap tag strings originate outside the core codebase, they are strictly sanitized prior to template insertion:

```kotlin
// Sanitization pattern in template rendering:
fun escapeHtml(input: String?): String {
    if (input == null) return ""
    return input
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
```

### CSS & Font Isolation
- CSS properties and inline styles are verified against a strict whitelist.
- Dynamic colors and style properties are sanitized to prevent CSS injection or broken PDFBox page rendering.
