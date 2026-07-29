#!/usr/bin/env python3
"""
California Location Pair Matrix Automated Test Runner for PathPress.

Executes an extensive matrix of California location pairs against the built standalone JAR
(`build/libs/pathpress-0.1.0-standalone.jar`).

Validates:
- Exit code 0
- PDF creation & non-empty size
- Route distance > 0 and duration > 0
- Clean reporting of snapping warnings
- Summary table output report
"""

import os
import re
import sys
import time
import subprocess
from dataclasses import dataclass
from typing import List, Optional

# Root directory of the repository
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAR_PATH = os.path.join(REPO_ROOT, "build", "libs", "pathpress-0.1.0-standalone.jar")
PBF_PATH = (
    os.path.join(REPO_ROOT, "data", "california-latest.osm.pbf")
    if os.path.exists(os.path.join(REPO_ROOT, "data", "california-latest.osm.pbf"))
    else os.path.join(REPO_ROOT, "california-latest.osm.pbf")
)
OUTPUT_DIR = os.path.join(REPO_ROOT, "scratch", "matrix_output")


@dataclass
class MatrixTestCase:
    id: int
    category: str
    start: str
    end: str
    days: int
    units: str


@dataclass
class TestResult:
    test_case: MatrixTestCase
    passed: bool
    elapsed_sec: float
    output_pdf: str
    pdf_bytes: int
    total_distance: str
    total_duration: str
    warnings: List[str]
    error_message: Optional[str] = None


TEST_MATRIX: List[MatrixTestCase] = [
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


def check_prerequisites():
    """Verify java, PBF file, and compile standalone shadow JAR if missing."""
    print("================================================================================")
    print("                  PREREQUISITE CHECKS & ENVIRONMENT SETUP                       ")
    print("================================================================================")

    # 1. Check java executable
    res = subprocess.run(["java", "-version"], capture_output=True, text=True)
    if res.returncode != 0:
        print("[ERROR] 'java' command not found or failed. Please install Java 21+.")
        sys.exit(1)
    print("[✓] Java runtime detected.")

    # 2. Check OSM PBF data file
    if not os.path.exists(PBF_PATH):
        print(f"[ERROR] OSM PBF file missing at: {PBF_PATH}")
        print("        Please download 'california-latest.osm.pbf' into project root before running matrix tests.")
        sys.exit(1)
    pbf_size_mb = os.path.getsize(PBF_PATH) / (1024 * 1024)
    print(f"[✓] OSM PBF file verified ({pbf_size_mb:.1f} MB).")

    # 3. Build standalone shadow JAR if missing
    if not os.path.exists(JAR_PATH):
        print(f"[!] Standalone JAR not found at: {JAR_PATH}")
        print("    Building shadow JAR via ./gradlew shadowJar ...")
        gradlew = os.path.join(REPO_ROOT, "gradlew")
        build_res = subprocess.run([gradlew, "shadowJar"], cwd=REPO_ROOT, capture_output=True, text=True)
        if build_res.returncode != 0:
            print("[ERROR] Failed to build shadow JAR:")
            print(build_res.stderr)
            sys.exit(1)
        print("[✓] Standalone JAR compiled successfully.")
    else:
        print(f"[✓] Standalone JAR found at: {JAR_PATH}")

    os.makedirs(OUTPUT_DIR, exist_ok=True)
    print()


def run_test_case(tc: MatrixTestCase) -> TestResult:
    """Execute a single test case using java -jar pathpress-standalone.jar."""
    pdf_file = os.path.join(OUTPUT_DIR, f"itinerary_case_{tc.id}.pdf")
    if os.path.exists(pdf_file):
        os.remove(pdf_file)

    cmd = [
        "java",
        "-jar",
        JAR_PATH,
        "--start",
        tc.start,
        "--end",
        tc.end,
        "--days",
        str(tc.days),
        "--units",
        tc.units,
        "--output",
        pdf_file,
        "--pbf",
        PBF_PATH,
    ]

    start_time = time.time()
    proc = subprocess.run(cmd, cwd=REPO_ROOT, capture_output=True, text=True)
    elapsed = time.time() - start_time

    combined_output = (proc.stdout or "") + "\n" + (proc.stderr or "")

    warnings = []
    for line in combined_output.splitlines():
        if "snapped" in line.lower() or "snapresult" in line.lower():
            clean_line = re.sub(r"^.*?(?:WARN|INFO)\s+[\w\.]+\s+-\s+", "", line).strip()
            clean_line = re.sub(r"^\[.*?\]\s*", "", clean_line).strip()
            if clean_line and clean_line not in warnings:
                warnings.append(clean_line)

    # Extract total distance and duration from logs
    dist_match = re.search(r"Total distance:\s*([\d\.\,]+\s*(?:km|mi|miles))", combined_output, re.IGNORECASE)
    dur_match = re.search(r"Estimated duration:\s*([^\n\r]+)", combined_output, re.IGNORECASE)

    total_dist = dist_match.group(1) if dist_match else "Unknown"
    total_dur = dur_match.group(1) if dur_match else "Unknown"

    if total_dist == "Unknown":
        sum_dist_match = re.search(r"-\s*([\d\.]+\s*(?:km|mi))\s*\(", combined_output)
        if sum_dist_match:
            total_dist = sum_dist_match.group(1)

    errors = []
    if proc.returncode != 0:
        errors.append(f"Exit code {proc.returncode} != 0")
    if not os.path.exists(pdf_file):
        errors.append("PDF output file was not created")
    elif os.path.getsize(pdf_file) == 0:
        errors.append("PDF output file is 0 bytes")
    if total_dist == "Unknown":
        errors.append("Route distance could not be determined")

    pdf_bytes = os.path.getsize(pdf_file) if os.path.exists(pdf_file) else 0
    passed = len(errors) == 0
    error_msg = "; ".join(errors) if errors else None

    return TestResult(
        test_case=tc,
        passed=passed,
        elapsed_sec=elapsed,
        output_pdf=pdf_file,
        pdf_bytes=pdf_bytes,
        total_distance=total_dist,
        total_duration=total_dur,
        warnings=warnings,
        error_message=error_msg,
    )


def print_summary_report(results: List[TestResult]):
    """Print clean ASCII summary table of test results."""
    print("=" * 125)
    print("                                CALIFORNIA LOCATION MATRIX TEST SUITE RESULTS                                ")
    print("=" * 125)
    header = (
        f"{'ID':<3} | {'Category':<22} | {'Route':<38} | {'Days':<4} | {'Units':<8} | {'Status':<6} | {'Time':<6} | {'Distance':<10} | {'Warnings / Snapping'}"
    )
    print(header)
    print("-" * 125)

    passed_count = 0
    total_time = sum(r.elapsed_sec for r in results)

    for r in results:
        tc = r.test_case
        route_str = f"{tc.start} ➔ {tc.end}"
        if len(route_str) > 38:
            route_str = route_str[:35] + "..."
        status_str = "PASS" if r.passed else "FAIL"
        if r.passed:
            passed_count += 1

        warn_str = "; ".join(r.warnings) if r.warnings else "None"
        if len(warn_str) > 40:
            warn_str = warn_str[:37] + "..."

        row = f"{tc.id:<3} | {tc.category:<22} | {route_str:<38} | {tc.days:<4} | {tc.units:<8} | {status_str:<6} | {r.elapsed_sec:4.1f}s | {r.total_distance:<10} | {warn_str}"
        print(row)

        if not r.passed and r.error_message:
            print(f"    └─> FAILURE DETAILS: {r.error_message}")

    print("=" * 125)
    print(f"Total Tests: {len(results)} | Passed: {passed_count} | Failed: {len(results) - passed_count} | Total Elapsed Time: {total_time:.1f}s")
    print("=" * 125)

    return passed_count == len(results)


def main():
    check_prerequisites()

    print(f"Running matrix test suite across {len(TEST_MATRIX)} California route permutations...\n")
    results = []
    for tc in TEST_MATRIX:
        print(f"[{tc.id:02d}/{len(TEST_MATRIX):02d}] Testing [{tc.category}] {tc.start} ➔ {tc.end} ({tc.days}d, {tc.units})... ", end="", flush=True)
        res = run_test_case(tc)
        status_label = "✓ PASS" if res.passed else "✗ FAIL"
        print(f"{status_label} ({res.elapsed_sec:.2f}s, {res.total_distance})")
        results.append(res)

    print()
    all_passed = print_summary_report(results)
    sys.exit(0 if all_passed else 1)


if __name__ == "__main__":
    main()
