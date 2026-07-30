#!/usr/bin/env python3
"""
PathPress Location Pair Matrix Automated Test Runner.

Executes test matrices of location pairs against the built standalone JAR
(`build/libs/pathpress-0.1.0-standalone.jar`) using OSM PBF state spatial data.

Usage:
    python3 scripts/run_matrix_test.py --state california
    python3 scripts/run_matrix_test.py --state texas
    python3 scripts/run_matrix_test.py --state all
"""

import argparse
import os
import re
import sys
import time
import subprocess
from dataclasses import dataclass
from typing import List, Optional

# Local imports
from matrix_cases import MatrixTestCase, STATE_MATRICES

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_JAR_PATH = os.path.join(REPO_ROOT, "build", "libs", "pathpress-0.1.0-standalone.jar")
DEFAULT_OUTPUT_BASE = os.path.join(REPO_ROOT, "scratch", "matrix_output")


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


def check_prerequisites(state: str, jar_path: str, custom_pbf: Optional[str] = None) -> str:
    """Verify java, PBF file, and compile standalone shadow JAR if missing."""
    print("================================================================================")
    print(f"       PATHPRESS MATRIX TEST RUNNER - SETUP [{state.upper()}]                   ")
    print("================================================================================")

    # 1. Check java executable
    res = subprocess.run(["java", "-version"], capture_output=True, text=True)
    if res.returncode != 0:
        print("[ERROR] 'java' command not found or failed. Please install Java 21+.")
        sys.exit(1)
    print("[✓] Java runtime detected.")

    # 2. Resolve & verify OSM PBF file
    if custom_pbf:
        pbf_path = os.path.abspath(custom_pbf)
    else:
        pbf_filename = f"{state}-latest.osm.pbf"
        # Check data/ subdirectory first, then repo root fallback
        pbf_path = os.path.join(REPO_ROOT, "data", pbf_filename)
        if not os.path.exists(pbf_path) and os.path.exists(os.path.join(REPO_ROOT, pbf_filename)):
            pbf_path = os.path.join(REPO_ROOT, pbf_filename)

    if not os.path.exists(pbf_path):
        print(f"[!] OSM PBF file missing at: {pbf_path}")
        print(f"    Attempting to download via ./scripts/download_state.sh {state} ...")
        downloader = os.path.join(REPO_ROOT, "scripts", "download_state.sh")
        dl_res = subprocess.run([downloader, state], cwd=REPO_ROOT, capture_output=True, text=True)
        if dl_res.returncode != 0 or not os.path.exists(pbf_path):
            print(f"[ERROR] Failed to download OSM PBF file for state '{state}':")
            print(dl_res.stderr or dl_res.stdout)
            sys.exit(1)
        print(f"[✓] {state.capitalize()} OSM PBF file downloaded successfully.")

    pbf_size_mb = os.path.getsize(pbf_path) / (1024 * 1024)
    print(f"[✓] OSM PBF file verified ({pbf_size_mb:.1f} MB) -> {pbf_path}")

    # 3. Build standalone shadow JAR if missing
    if not os.path.exists(jar_path):
        print(f"[!] Standalone JAR not found at: {jar_path}")
        print("    Building shadow JAR via ./gradlew shadowJar ...")
        gradlew = os.path.join(REPO_ROOT, "gradlew")
        build_res = subprocess.run([gradlew, "shadowJar"], cwd=REPO_ROOT, capture_output=True, text=True)
        if build_res.returncode != 0:
            print("[ERROR] Failed to build shadow JAR:")
            print(build_res.stderr)
            sys.exit(1)
        print("[✓] Standalone JAR compiled successfully.")
    else:
        print(f"[✓] Standalone JAR found at: {jar_path}")

    print()
    return pbf_path


def run_test_case(
    tc: MatrixTestCase,
    state: str,
    jar_path: str,
    pbf_path: str,
    output_dir: str,
    graph_dir: str,
) -> TestResult:
    """Execute a single test case using java -jar pathpress-standalone.jar."""
    os.makedirs(output_dir, exist_ok=True)
    pdf_file = os.path.join(output_dir, f"{state}_case_{tc.id}.pdf")
    if os.path.exists(pdf_file):
        os.remove(pdf_file)

    cmd = [
        "java",
        "-jar",
        jar_path,
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
        pbf_path,
        "--graph",
        graph_dir,
    ]

    start_time = time.time()
    proc = subprocess.run(cmd, cwd=REPO_ROOT, capture_output=True, text=True)
    elapsed = time.time() - start_time

    combined_output = (proc.stdout or "") + "\n" + (proc.stderr or "")

    warnings = []
    for line in combined_output.splitlines():
        if any(term in line.lower() for term in ["snapped", "snapresult", "warn", "warning"]):
            clean_line = re.sub(r"^.*?(?:WARN|INFO)\s+[\w\.]+\s+-\s+", "", line).strip()
            clean_line = re.sub(r"^\[.*?\]\s*", "", clean_line).strip()
            if clean_line and clean_line not in warnings:
                warnings.append(clean_line)

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
    else:
        pdf_size = os.path.getsize(pdf_file)
        if pdf_size < 1000:
            errors.append(f"PDF output file too small ({pdf_size} bytes)")

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


def print_summary_report(state: str, results: List[TestResult]) -> bool:
    """Print clean ASCII summary table of test results for a state."""
    title = f"{state.upper()} LOCATION MATRIX TEST SUITE RESULTS"
    print("=" * 125)
    print(f"{title:^125}")
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
    print(
        f"[{state.capitalize()}] Total Tests: {len(results)} | Passed: {passed_count} | Failed: {len(results) - passed_count} | Total Time: {total_time:.1f}s"
    )
    print("=" * 125)
    print()

    return passed_count == len(results)


def run_state_matrix(
    state: str,
    jar_path: str,
    custom_pbf: Optional[str] = None,
    output_base: str = DEFAULT_OUTPUT_BASE,
) -> bool:
    """Run matrix test suite for a given state."""
    test_cases = STATE_MATRICES.get(state.lower())
    if not test_cases:
        print(f"[ERROR] No matrix test cases defined for state '{state}'.")
        print(f"        Available states: {', '.join(STATE_MATRICES.keys())}")
        return False

    pbf_path = check_prerequisites(state, jar_path, custom_pbf)
    output_dir = os.path.join(output_base, state)
    graph_dir = os.path.join(REPO_ROOT, f".graphhopper_{state}")

    print(f"Running {len(test_cases)} test cases for {state.capitalize()}...\n")
    results = []
    for tc in test_cases:
        print(f"[{tc.id:02d}/{len(test_cases):02d}] Testing [{tc.category}] {tc.start} ➔ {tc.end} ({tc.days}d, {tc.units})... ", end="", flush=True)
        res = run_test_case(tc, state, jar_path, pbf_path, output_dir, graph_dir)
        status_label = "✓ PASS" if res.passed else "✗ FAIL"
        print(f"{status_label} ({res.elapsed_sec:.2f}s, {res.total_distance})")
        results.append(res)

    print()
    return print_summary_report(state, results)


def main():
    parser = argparse.ArgumentParser(description="PathPress Location Pair Matrix Automated Test Runner")
    parser.add_argument(
        "--state",
        type=str,
        default="california",
        help=f"State matrix to test ({', '.join(STATE_MATRICES.keys())}, or 'all'). Default: california",
    )
    parser.add_argument("--jar", type=str, default=DEFAULT_JAR_PATH, help="Path to standalone shadow JAR")
    parser.add_argument("--pbf", type=str, default=None, help="Explicit path to OSM PBF data file")
    parser.add_argument("--output-dir", type=str, default=DEFAULT_OUTPUT_BASE, help="Output directory for generated PDFs")

    args = parser.parse_args()

    target_state = args.state.lower()
    if target_state == "all":
        states_to_run = list(STATE_MATRICES.keys())
    elif target_state in STATE_MATRICES:
        states_to_run = [target_state]
    else:
        print(f"[ERROR] Unknown state '{args.state}'.")
        print(f"        Valid options are: {', '.join(STATE_MATRICES.keys())}, or 'all'.")
        sys.exit(1)

    all_states_passed = True
    for state in states_to_run:
        success = run_state_matrix(state, args.jar, args.pbf, args.output_dir)
        if not success:
            all_states_passed = False

    sys.exit(0 if all_states_passed else 1)


if __name__ == "__main__":
    main()
