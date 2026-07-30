#!/usr/bin/env python3
"""
PathPress Location Pair Matrix Definitions.

Defines the MatrixTestCase data structure and route matrices for tested states
(e.g., California, Texas).
"""

from dataclasses import dataclass


@dataclass
class MatrixTestCase:
    id: int
    category: str
    start: str
    end: str
    days: int
    units: str


CALIFORNIA_MATRIX: list[MatrixTestCase] = [
    # 1. Major Metropolitan Routes
    MatrixTestCase(1, "Major Metropolitan", "San Francisco", "Los Angeles", 2, "metric"),
    MatrixTestCase(2, "Major Metropolitan", "Sacramento", "San Diego", 3, "imperial"),
    # 2. Coastal & Highway 1 Drives
    MatrixTestCase(3, "Coastal & Highway 1", "Monterey", "Morro Bay", 1, "metric"),
    MatrixTestCase(4, "Coastal & Highway 1", "Santa Cruz, CA", "Big Sur, CA", 1, "imperial"),
    # 3. Mountain / National Park Centroids
    MatrixTestCase(5, "Mountain / Nat'l Parks", "Yosemite National Park, CA", "Lake Tahoe, CA", 2, "metric"),
    MatrixTestCase(6, "Mountain / Nat'l Parks", "Lake Tahoe", "Mount Shasta", 2, "imperial"),
    MatrixTestCase(7, "Mountain / Nat'l Parks", "Mount Shasta", "Mammoth Lakes", 3, "metric"),
    # 4. Desert / Remote Locations
    MatrixTestCase(8, "Desert / Remote", "Death Valley", "Joshua Tree", 2, "metric"),
    MatrixTestCase(9, "Desert / Remote", "Palm Springs", "Borrego Springs", 1, "imperial"),
    # 5. Raw Lat/Lng Coordinates
    MatrixTestCase(10, "Raw Coordinates", "37.7749,-122.4194", "34.0537,-118.2427", 1, "metric"),
    MatrixTestCase(11, "Raw Coordinates", "32.7157,-117.1611", "38.5816,-121.4944", 3, "imperial"),
    # 6. Multi-day Variations
    MatrixTestCase(12, "Multi-day (5-day)", "San Francisco", "San Diego", 5, "metric"),
]

TEXAS_MATRIX: list[MatrixTestCase] = [
    # 1. Major Metropolitan Corridors
    MatrixTestCase(1, "Major Metro", "Austin, TX", "San Antonio, TX", 1, "metric"),
    MatrixTestCase(2, "Major Metro", "Houston, TX", "Dallas, TX", 1, "imperial"),
    MatrixTestCase(3, "Major Metro", "Dallas, TX", "Austin, TX", 1, "metric"),
    # 2. Multi-day State Traversals
    MatrixTestCase(4, "Multi-day Traversal", "Houston, TX", "El Paso, TX", 3, "imperial"),
    MatrixTestCase(5, "Multi-day Traversal", "San Antonio, TX", "Amarillo, TX", 2, "metric"),
    MatrixTestCase(6, "Multi-day Traversal", "Dallas, TX", "Corpus Christi, TX", 2, "imperial"),
    # 3. Hill Country & Coastal Scenic Drives
    MatrixTestCase(7, "Hill Country / Scenic", "Austin, TX", "Fredericksburg, TX", 1, "imperial"),
    MatrixTestCase(8, "Coastal Drive", "Corpus Christi, TX", "Galveston, TX", 1, "metric"),
    # 4. Raw Coordinates
    MatrixTestCase(9, "Raw Coordinates", "30.2711,-97.7437", "29.4246,-98.4951", 1, "metric"),
]

STATE_MATRICES: dict[str, list[MatrixTestCase]] = {
    "california": CALIFORNIA_MATRIX,
    "texas": TEXAS_MATRIX,
}
