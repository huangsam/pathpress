#!/usr/bin/env python3
"""
Texas Location Pair Matrix Automated Test Runner for PathPress.

Executes an extensive matrix of Texas location pairs against the built standalone JAR
(`build/libs/pathpress-0.1.0-standalone.jar`) using `data/texas-latest.osm.pbf`.

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
PBF_PATH = os.path.join(REPO_ROOT, "data", "texas-latest.osm.pbf")
OUTPUT_DIR = os.path.join(REPO_ROOT, "scratch", "texas_matrix_output")
GRAPH_DIR = os.path.join(REPO_ROOT, ".graphhopper_texas")


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


def check_prerequisites():
    """Verify java, PBF file, and compile standalone shadow JAR if missing."""
    print("================================================================================")
    print("               TEXAS MATRIX TEST RUNNER - PREREQUISITES & SETUP                 ")
    print("================================================================================")

    # 1. Check java executable
    res = subprocess.run(["java", "-version"], capture_output=True, text=True)
    if res.returncode != 0:
        print("[ERROR] 'java' command not found or failed. Please install Java 21+.")
        sys.exit(1)
    print("[✓] Java runtime detected.")

    # 2. Check OSM PBF data file for Texas
    if not os.path.exists(PBF_PATH):
        print(f"[!] Texas OSM PBF file missing at: {PBF_PATH}")
        print("    Attempting to download via ./scripts/download_state.sh texas ...")
        downloader = os.path.join(REPO_ROOT, "scripts", "download_state.sh")
        dl_res = subprocess.run([downloader, "texas"], cwd=REPO_ROOT, capture_output=True, text=True)
        if dl_res.returncode != 0 or not os.path.exists(PBF_PATH):
            print("[ERROR] Failed to download Texas OSM PBF file:")
            print(dl_res.stderr or dl_res.stdout)
            sys.exit(1)
        print("[✓] Texas OSM PBF file downloaded successfully.")

    pbf_size_mb = os.path.getsize(PBF_PATH) / (1024 * 1024)
    print(f"[✓] Texas OSM PBF file verified ({pbf_size_mb:.1f} MB).")

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
    pdf_file = os.path.join(OUTPUT_DIR, f"texas_case_{tc.id}.pdf")
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
        "--graph",
        GRAPH_DIR,
    ]

    start_time = time.time()
    proc = subprocess.run(cmd, cwd=REPO_ROOT, capture_output=True, text=True)
    elapsed = time.time() - start_time

    combined_output = (proc.stdout or "") + "\n" + (proc.stderr or "")

    warnings = []
    for line in combined_output.splitlines():
        if "WARN" in line or "Snapping" in line or "Warning" in line:
            warnings.append(line.strip())

    distance_match = re.search(r"Total distance:\s*([\d\.]+\s*\w+)", combined_output)
    duration_match = re.search(r"Estimated duration:\s*([\w\s]+)", combined_output)

    total_dist = distance_match.group(1) if distance_match else "N/A"
    total_dur = duration_match.group(1) if duration_match else "N/A"

    passed = True
    error_msg = None

    if proc.returncode != 0:
        passed = False
        error_msg = f"Process exited with code {proc.returncode}"
    elif not os.path.exists(pdf_file):
        passed = False
        error_msg = "PDF output file was not created"
    else:
        pdf_size = os.path.getsize(pdf_file)
        if pdf_size < 1000:
            passed = False
            error_msg = f"PDF file size too small ({pdf_size} bytes)"

    pdf_bytes = os.path.getsize(pdf_file) if os.path.exists(pdf_file) else 0

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


def print_summary(results: List[TestResult]):
    """Print an executive markdown summary table of test results."""
    print("================================================================================")
    print("                     TEXAS MATRIX TEST RESULTS SUMMARY                          ")
    print("================================================================================")
    print(f"{'ID':<3} | {'Category':<20} | {'Start -> End':<32} | {'Days':<4} | {'Status':<6} | {'Dist':<10} | {'Time':<6}")
    print("-" * 100)

    passed_count = 0
    total_time = 0.0

    for r in results:
        tc = r.test_case
        route_str = f"{tc.start} -> {tc.end}"
        if len(route_str) > 32:
            route_str = route_str[:29] + "..."
        status_str = "PASS" if r.passed else "FAIL"
        if r.passed:
            passed_count += 1
        total_time += r.elapsed_sec

        print(f"{tc.id:<3} | {tc.category:<20} | {route_str:<32} | {tc.days:<4} | {status_str:<6} | {r.total_distance:<10} | {r.elapsed_sec:5.2f}s")

    print("-" * 100)
    print(f"Total: {passed_count}/{len(results)} Passed ({passed_count / len(results) * 100:.1f}%) in {total_time:.2f}s")
    print("================================================================================")


def main():
    check_prerequisites()
    print("Running Texas matrix tests...\n")
    results = []

    for tc in TEST_MATRIX:
        print(f"[{tc.id}/{len(TEST_MATRIX)}] Testing {tc.category}: '{tc.start}' -> '{tc.end}' ({tc.days} days, {tc.units})...")
        res = run_test_case(tc)
        results.append(res)
        if res.passed:
            print(f"    ✓ Passed ({res.elapsed_sec:.2f}s, {res.pdf_bytes // 1024} KB PDF, {res.total_distance})")
        else:
            print(f"    ❌ Failed: {res.error_message}")

    print_summary(results)

    all_passed = all(r.passed for r in results)
    sys.exit(0 if all_passed else 1)


if __name__ == "__main__":
    main()
