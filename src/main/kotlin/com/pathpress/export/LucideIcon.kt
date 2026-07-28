package com.pathpress.export

import com.pathpress.llm.*
import com.pathpress.model.*
import com.pathpress.poi.*
import com.pathpress.routing.*

/** Vector SVG icon generator providing clean, inline SVG markup for PDF rendering. */
object LucideIcon {

    fun mapPin(color: String = "#3182ce", size: Int = 16): String =
        """<svg xmlns="http://www.w3.org/2000/svg" width="$size" height="$size" viewBox="0 0 24 24" fill="none" stroke="$color" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="display: inline; vertical-align: -0.15em; margin-right: 4px;"><path d="M20 10c0 4.993-5.539 10.193-7.399 11.799a1 1 0 0 1-1.202 0C9.539 20.193 4 14.993 4 10a8 8 0 0 1 16 0Z"/><circle cx="12" cy="10" r="3"/></svg>"""

    fun clock(color: String = "#3182ce", size: Int = 16): String =
        """<svg xmlns="http://www.w3.org/2000/svg" width="$size" height="$size" viewBox="0 0 24 24" fill="none" stroke="$color" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="display: inline; vertical-align: -0.15em; margin-right: 4px;"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>"""

    fun route(color: String = "#3182ce", size: Int = 16): String =
        """<svg xmlns="http://www.w3.org/2000/svg" width="$size" height="$size" viewBox="0 0 24 24" fill="none" stroke="$color" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="display: inline; vertical-align: -0.15em; margin-right: 4px;"><circle cx="6" cy="19" r="3"/><path d="M9 19h8.5a3.5 3.5 0 0 0 0-7h-11a3.5 3.5 0 0 1 0-7H15"/><circle cx="18" cy="5" r="3"/></svg>"""

    fun coffee(color: String = "#744210", size: Int = 16): String =
        """<svg xmlns="http://www.w3.org/2000/svg" width="$size" height="$size" viewBox="0 0 24 24" fill="none" stroke="$color" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="display: inline; vertical-align: -0.15em; margin-right: 6px;"><path d="M10 2v2"/><path d="M14 2v2"/><path d="M16 8a1 1 0 0 1 1 1v8a4 4 0 0 1-4 4H7a4 4 0 0 1-4-4V9a1 1 0 0 1 1-1h12Z"/><path d="M6 2v2"/><path d="M17 9h1a3 3 0 0 1 0 6h-1"/></svg>"""

    fun lightbulb(color: String = "#22543d", size: Int = 16): String =
        """<svg xmlns="http://www.w3.org/2000/svg" width="$size" height="$size" viewBox="0 0 24 24" fill="none" stroke="$color" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="display: inline; vertical-align: -0.15em; margin-right: 6px;"><path d="M15 14c.2-1 .7-1.7 1.5-2.5 1-.9 1.5-2.2 1.5-3.5A6 6 0 0 0 6 8c0 1 .2 2.2 1.5 3.5.7.7 1.3 1.5 1.5 2.5"/><path d="M9 18h6"/><path d="M10 22h4"/></svg>"""

    fun navigation(color: String = "#ffffff", size: Int = 14): String =
        """<svg xmlns="http://www.w3.org/2000/svg" width="$size" height="$size" viewBox="0 0 24 24" fill="none" stroke="$color" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="display: inline; vertical-align: -0.15em; margin-right: 6px;"><polygon points="3 11 22 2 13 21 11 13 3 11"/></svg>"""

    fun calendar(color: String = "#3182ce", size: Int = 16): String =
        """<svg xmlns="http://www.w3.org/2000/svg" width="$size" height="$size" viewBox="0 0 24 24" fill="none" stroke="$color" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="display: inline; vertical-align: -0.15em; margin-right: 4px;"><rect width="18" height="18" x="3" y="4" rx="2" ry="2"/><line x1="16" x2="16" y1="2" y2="6"/><line x1="8" x2="8" y1="2" y2="6"/><line x1="3" x2="21" y1="10" y2="10"/></svg>"""

    fun compass(color: String = "#3182ce", size: Int = 16): String =
        """<svg xmlns="http://www.w3.org/2000/svg" width="$size" height="$size" viewBox="0 0 24 24" fill="none" stroke="$color" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="display: inline; vertical-align: -0.15em; margin-right: 4px;"><circle cx="12" cy="12" r="10"/><polygon points="16.24 7.76 14.12 14.12 7.76 16.24 9.88 9.88 16.24 7.76"/></svg>"""

    fun camera(color: String = "#2b6cb0", size: Int = 16): String =
        """<svg xmlns="http://www.w3.org/2000/svg" width="$size" height="$size" viewBox="0 0 24 24" fill="none" stroke="$color" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="display: inline; vertical-align: -0.15em; margin-right: 6px;"><path d="M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3l-2.5-3z"/><circle cx="12" cy="13" r="3"/></svg>"""

    fun externalLink(color: String = "#ffffff", size: Int = 12): String =
        """<svg xmlns="http://www.w3.org/2000/svg" width="$size" height="$size" viewBox="0 0 24 24" fill="none" stroke="$color" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="display: inline; vertical-align: -0.15em; margin-left: 4px;"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/><polyline points="15 3 21 3 21 9"/><line x1="10" x2="21" y1="14" y2="3"/></svg>"""

    fun arrowRight(color: String = "#cbd5e0", size: Int = 14): String =
        """<svg xmlns="http://www.w3.org/2000/svg" width="$size" height="$size" viewBox="0 0 24 24" fill="none" stroke="$color" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="display: inline; vertical-align: -0.15em; margin: 0 6px;"><path d="M5 12h14"/><path d="m12 5 7 7-7 7"/></svg>"""
}
