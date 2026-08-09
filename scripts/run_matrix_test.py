#!/usr/bin/env python3
"""
PathPress Location Pair Matrix Automated Test Runner.

Executes test matrices of location pairs against the built standalone JAR
(`build/libs/pathpress-0.5.0-standalone.jar`) using OSM PBF state spatial data.

Usage:
    python3 scripts/run_matrix_test.py --state california
    python3 scripts/run_matrix_test.py --state texas
    python3 scripts/run_matrix_test.py --state all
"""

import argparse
import os
import re
import subprocess
import sys
import time
from dataclasses import dataclass

from matrix_cases import (
    REGION_STATES,
    REGIONAL_MATRICES,
    STATE_MATRICES,
    USA_NATIONWIDE_MATRIX,
    MatrixTestCase,
)

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_JAR_PATH = os.path.join(REPO_ROOT, "build", "libs", "pathpress-0.5.0-standalone.jar")
DEFAULT_OUTPUT_BASE = os.path.join(REPO_ROOT, "build", "matrix-output")


class Colors:
    """ANSI color code definitions with auto-disable support."""

    enabled = True

    BOLD = "\033[1m"
    DIM = "\033[2m"
    GREEN = "\033[32m"
    RED = "\033[31m"
    YELLOW = "\033[33m"
    CYAN = "\033[36m"
    BLUE = "\033[34m"
    MAGENTA = "\033[35m"
    RESET = "\033[0m"

    @classmethod
    def disable(cls):
        cls.BOLD = ""
        cls.DIM = ""
        cls.GREEN = ""
        cls.RED = ""
        cls.YELLOW = ""
        cls.CYAN = ""
        cls.BLUE = ""
        cls.MAGENTA = ""
        cls.RESET = ""
        cls.enabled = False


def visible_len(s: str) -> int:
    """Calculate the visible character length of a string, ignoring ANSI color codes."""
    return len(re.sub(r"\033\[[0-9;]*m", "", s))


def pad_colored(s: str, width: int, align: str = "left") -> str:
    """Pad a string containing ANSI escape codes to a target visible width."""
    vlen = visible_len(s)
    padding = max(0, width - vlen)
    if align == "right":
        return " " * padding + s
    elif align == "center":
        left_pad = padding // 2
        right_pad = padding - left_pad
        return " " * left_pad + s + " " * right_pad
    else:  # left
        return s + " " * padding


@dataclass
class TestResult:
    test_case: MatrixTestCase
    passed: bool
    elapsed_sec: float
    output_pdf: str
    pdf_bytes: int
    total_distance: str
    total_duration: str
    warnings: list[str]
    leg_summaries: list[str] | None = None
    error_message: str | None = None


def check_prerequisites(target: str, jar_path: str, custom_pbf: str | None = None, is_region: bool = False) -> str:
    """Verify java, PBF file, and compile standalone shadow JAR if missing."""
    border = f"{Colors.CYAN}{Colors.BOLD}{'═' * 80}{Colors.RESET}"
    header_label = f"REGION: {target.upper()}" if is_region else target.upper()
    print(border)
    print(f"{Colors.CYAN}{Colors.BOLD} PATHPRESS MATRIX TEST RUNNER - SETUP [{header_label}]{Colors.RESET}")
    print(border)

    # 1. Check java executable
    res = subprocess.run(["java", "-version"], capture_output=True, text=True)
    if res.returncode != 0:
        print(f"{Colors.RED}[ERROR] 'java' command not found or failed. Please install Java 21+.{Colors.RESET}")
        sys.exit(1)
    print(f"{Colors.GREEN}[✓]{Colors.RESET} Java runtime detected.")

    # 2. Resolve & verify OSM PBF file
    if custom_pbf:
        pbf_path = os.path.abspath(custom_pbf)
    else:
        if target in ("us", "usa", "nationwide", "united-states"):
            pbf_filename = "us-latest.osm.pbf"
        else:
            pbf_filename = f"{target}-latest.osm.pbf"
        pbf_path = os.path.join(REPO_ROOT, "data", pbf_filename)
        if not os.path.exists(pbf_path) and os.path.exists(os.path.join(REPO_ROOT, pbf_filename)):
            pbf_path = os.path.join(REPO_ROOT, pbf_filename)

        if not os.path.exists(pbf_path):
            print(f"{Colors.YELLOW}[!]{Colors.RESET} OSM PBF file missing at: {pbf_path}")
            print(f"    Attempting to download via python3 scripts/download_pbf.py {target} ...")
            downloader = os.path.join(REPO_ROOT, "scripts", "download_pbf.py")
            dl_res = subprocess.run([sys.executable, downloader, target], cwd=REPO_ROOT, capture_output=True, text=True)
            if dl_res.returncode != 0 or not os.path.exists(pbf_path):
                print(f"{Colors.RED}[ERROR] Failed to download OSM PBF file for '{target}':{Colors.RESET}")
                print(dl_res.stderr or dl_res.stdout)
                sys.exit(1)
            print(f"{Colors.GREEN}[✓]{Colors.RESET} {target.capitalize()} OSM PBF file downloaded successfully.")

    pbf_size_mb = os.path.getsize(pbf_path) / (1024 * 1024)
    print(f"{Colors.GREEN}[✓]{Colors.RESET} OSM PBF file verified ({pbf_size_mb:.1f} MB) -> {Colors.DIM}{pbf_path}{Colors.RESET}")

    # 3. Build standalone shadow JAR if missing
    if not os.path.exists(jar_path):
        print(f"{Colors.YELLOW}[!]{Colors.RESET} Standalone JAR not found at: {jar_path}")
        print("    Building shadow JAR via ./gradlew shadowJar ...")
        gradlew = os.path.join(REPO_ROOT, "gradlew")
        build_res = subprocess.run([gradlew, "shadowJar"], cwd=REPO_ROOT, capture_output=True, text=True)
        if build_res.returncode != 0:
            print(f"{Colors.RED}[ERROR] Failed to build shadow JAR:{Colors.RESET}")
            print(build_res.stderr)
            sys.exit(1)
        print(f"{Colors.GREEN}[✓]{Colors.RESET} Standalone JAR compiled successfully.")
    else:
        print(f"{Colors.GREEN}[✓]{Colors.RESET} Standalone JAR found at: {Colors.DIM}{jar_path}{Colors.RESET}")

    print()
    return pbf_path


def run_test_case(
    tc: MatrixTestCase,
    target: str,
    jar_path: str,
    pbf_path: str,
    output_dir: str,
    graph_dir: str,
) -> TestResult:
    """Execute a single test case using java -jar pathpress-standalone.jar."""
    os.makedirs(output_dir, exist_ok=True)
    pdf_file = os.path.join(output_dir, f"{target}_case_{tc.id}.pdf")
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

    # Parse multi-day leg summary if present
    leg_summaries = []
    in_daily_summary = False
    for line in combined_output.splitlines():
        if "Daily Summary:" in line:
            in_daily_summary = True
            continue
        if in_daily_summary:
            stripped = line.strip()
            if stripped.startswith("Day "):
                leg_summaries.append(stripped)
            elif not stripped or stripped.startswith("["):
                in_daily_summary = False

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
        leg_summaries=leg_summaries if leg_summaries else None,
        error_message=error_msg,
    )


def print_summary_report(target: str, results: list[TestResult], output_dir: str, is_region: bool = False) -> bool:
    """Print polished Unicode box-drawing summary table of test results."""
    # Define column widths
    w_id = 4
    w_cat = 22
    w_route = 38
    w_days = 5
    w_units = 8
    w_status = 8
    w_time = 7
    w_dist = 11
    w_warn = 36

    # Box-drawing characters
    c_tl, c_tr, c_bl, c_br = "┌", "┐", "└", "┘"
    c_h, c_v = "─", "│"
    c_tj, c_bj, c_lj, c_rj, c_x = "┬", "┴", "├", "┤", "┼"

    top_border = (
        Colors.DIM
        + c_tl
        + c_h * w_id
        + c_tj
        + c_h * w_cat
        + c_tj
        + c_h * w_route
        + c_tj
        + c_h * w_days
        + c_tj
        + c_h * w_units
        + c_tj
        + c_h * w_status
        + c_tj
        + c_h * w_time
        + c_tj
        + c_h * w_dist
        + c_tj
        + c_h * w_warn
        + c_tr
        + Colors.RESET
    )

    mid_border = (
        Colors.DIM
        + c_lj
        + c_h * w_id
        + c_x
        + c_h * w_cat
        + c_x
        + c_h * w_route
        + c_x
        + c_h * w_days
        + c_x
        + c_h * w_units
        + c_x
        + c_h * w_status
        + c_x
        + c_h * w_time
        + c_x
        + c_h * w_dist
        + c_x
        + c_h * w_warn
        + c_rj
        + Colors.RESET
    )

    bot_border = (
        Colors.DIM
        + c_bl
        + c_h * w_id
        + c_bj
        + c_h * w_cat
        + c_bj
        + c_h * w_route
        + c_bj
        + c_h * w_days
        + c_bj
        + c_h * w_units
        + c_bj
        + c_h * w_status
        + c_bj
        + c_h * w_time
        + c_bj
        + c_h * w_dist
        + c_bj
        + c_h * w_warn
        + c_br
        + Colors.RESET
    )

    total_table_width = visible_len(top_border)

    # Header title banner
    title_kind = "REGION " + target.upper() if is_region else target.upper()
    title_text = f" {title_kind} LOCATION MATRIX TEST SUITE RESULTS "
    side_dash_count = max(0, total_table_width - visible_len(title_text)) // 2
    right_dash_count = total_table_width - visible_len(title_text) - side_dash_count
    banner_line = f"{'═' * side_dash_count}{title_text}{'═' * right_dash_count}"

    print()
    print(f"{Colors.CYAN}{Colors.BOLD}{banner_line}{Colors.RESET}")
    print(top_border)

    # Column Headers
    hdr_id = pad_colored(f"{Colors.BOLD}ID{Colors.RESET}", w_id, "center")
    hdr_cat = pad_colored(f"{Colors.BOLD}Category{Colors.RESET}", w_cat)
    hdr_route = pad_colored(f"{Colors.BOLD}Route Permutation{Colors.RESET}", w_route)
    hdr_days = pad_colored(f"{Colors.BOLD}Days{Colors.RESET}", w_days, "center")
    hdr_units = pad_colored(f"{Colors.BOLD}Units{Colors.RESET}", w_units, "center")
    hdr_status = pad_colored(f"{Colors.BOLD}Status{Colors.RESET}", w_status, "center")
    hdr_time = pad_colored(f"{Colors.BOLD}Time{Colors.RESET}", w_time, "right")
    hdr_dist = pad_colored(f"{Colors.BOLD}Distance{Colors.RESET}", w_dist, "right")
    hdr_warn = pad_colored(f"{Colors.BOLD}Warnings / Snapping{Colors.RESET}", w_warn)

    v_bar = f"{Colors.DIM}{c_v}{Colors.RESET}"
    header_row = f"{v_bar}{hdr_id}{v_bar}{hdr_cat}{v_bar}{hdr_route}{v_bar}{hdr_days}{v_bar}{hdr_units}{v_bar}{hdr_status}{v_bar}{hdr_time}{v_bar}{hdr_dist}{v_bar}{hdr_warn}{v_bar}"
    print(header_row)
    print(mid_border)

    passed_count = 0
    total_time = sum(r.elapsed_sec for r in results)

    for r in results:
        tc = r.test_case
        route_str = f"{tc.start} ➔ {tc.end}"
        if len(route_str) > w_route:
            route_str = route_str[: w_route - 3] + "..."

        if r.passed:
            passed_count += 1
            status_fmt = f"{Colors.GREEN}{Colors.BOLD}PASS{Colors.RESET}"
        else:
            status_fmt = f"{Colors.RED}{Colors.BOLD}FAIL{Colors.RESET}"

        warn_text = "; ".join(r.warnings) if r.warnings else "None"
        if len(warn_text) > w_warn:
            warn_text = warn_text[: w_warn - 3] + "..."

        if warn_text != "None":
            warn_fmt = f"{Colors.YELLOW}{warn_text}{Colors.RESET}"
        else:
            warn_fmt = f"{Colors.DIM}None{Colors.RESET}"

        cell_id = pad_colored(f"{Colors.DIM}{tc.id:02d}{Colors.RESET}", w_id, "center")
        cell_cat = pad_colored(tc.category, w_cat)
        cell_route = pad_colored(route_str, w_route)
        cell_days = pad_colored(str(tc.days), w_days, "center")
        cell_units = pad_colored(tc.units, w_units, "center")
        cell_status = pad_colored(status_fmt, w_status, "center")
        cell_time = pad_colored(f"{r.elapsed_sec:5.1f}s", w_time, "right")
        cell_dist = pad_colored(r.total_distance, w_dist, "right")
        cell_warn = pad_colored(warn_fmt, w_warn)

        row_str = f"{v_bar}{cell_id}{v_bar}{cell_cat}{v_bar}{cell_route}{v_bar}{cell_days}{v_bar}{cell_units}{v_bar}{cell_status}{v_bar}{cell_time}{v_bar}{cell_dist}{v_bar}{cell_warn}{v_bar}"
        print(row_str)

        if r.leg_summaries:
            for leg in r.leg_summaries:
                print(f"{Colors.DIM}    ├─ {leg}{Colors.RESET}")

        if not r.passed and r.error_message:
            fail_msg = f"{Colors.RED}    └─> FAILURE DETAILS: {r.error_message}{Colors.RESET}"
            print(fail_msg)

    print(bot_border)

    # Executive Summary Card (fully enclosed box matching exact total_table_width)
    total_tests = len(results)
    failed_count = total_tests - passed_count
    pass_rate = (passed_count / total_tests) * 100.0 if total_tests > 0 else 0.0
    avg_time = total_time / total_tests if total_tests > 0 else 0.0

    pass_color = Colors.GREEN if failed_count == 0 else Colors.RED
    status_summary = f"{pass_color}{Colors.BOLD}{passed_count}/{total_tests} Passed ({pass_rate:.1f}%){Colors.RESET}"

    card_header = f" EXECUTIVE SUMMARY [{title_kind}] "
    right_dash_summary = max(0, total_table_width - 2 - visible_len(card_header) - 1)
    summary_top = f"┌─{card_header}{'─' * right_dash_summary}┐"
    summary_bot = f"└{'─' * (total_table_width - 2)}┘"

    def make_summary_row(content: str) -> str:
        inner_width = total_table_width - 2
        padded_content = pad_colored(content, inner_width)
        return f"{Colors.CYAN}│{Colors.RESET}{padded_content}{Colors.CYAN}│{Colors.RESET}"

    print(f"\n{Colors.CYAN}{Colors.BOLD}{summary_top}{Colors.RESET}")
    print(make_summary_row(f"  • Overall Status : {status_summary}"))
    print(make_summary_row(f"  • Execution Time : {Colors.BOLD}{total_time:.2f}s{Colors.RESET} total ({avg_time:.2f}s avg/test)"))
    print(make_summary_row(f"  • PDF Outputs    : {Colors.DIM}{output_dir}{Colors.RESET}"))
    print(f"{Colors.CYAN}{Colors.BOLD}{summary_bot}{Colors.RESET}\n")

    return passed_count == total_tests


def run_state_matrix(
    state: str,
    jar_path: str,
    custom_pbf: str | None = None,
    output_base: str = DEFAULT_OUTPUT_BASE,
) -> bool:
    """Run matrix test suite for a given state."""
    test_cases = STATE_MATRICES.get(state.lower())
    if not test_cases:
        print(f"{Colors.RED}[ERROR] No matrix test cases defined for state '{state}'.{Colors.RESET}")
        print(f"        Available states: {', '.join(STATE_MATRICES.keys())}")
        return False

    pbf_path = check_prerequisites(state, jar_path, custom_pbf, is_region=False)
    output_dir = os.path.join(output_base, state)
    graph_dir = os.path.join(REPO_ROOT, ".graphhopper", state)

    print(f"Running {len(test_cases)} test cases for {Colors.BOLD}{state.capitalize()}{Colors.RESET}...\n")
    results = []
    for tc in test_cases:
        print(
            f"[{tc.id:02d}/{len(test_cases):02d}] Testing [{tc.category}] {tc.start} ➔ {tc.end} ({tc.days}d, {tc.units})... ",
            end="",
            flush=True,
        )
        res = run_test_case(tc, state, jar_path, pbf_path, output_dir, graph_dir)
        status_label = f"{Colors.GREEN}✓ PASS{Colors.RESET}" if res.passed else f"{Colors.RED}✗ FAIL{Colors.RESET}"
        print(f"{status_label} ({res.elapsed_sec:.2f}s, {res.total_distance})")
        results.append(res)

    return print_summary_report(state, results, output_dir, is_region=False)


def run_region_matrix(
    region: str,
    jar_path: str,
    custom_pbf: str | None = None,
    output_base: str = DEFAULT_OUTPUT_BASE,
) -> bool:
    """Run multi-state corridor matrix test suite for a given region."""
    test_cases = REGIONAL_MATRICES.get(region.lower())
    if not test_cases:
        print(f"{Colors.RED}[ERROR] No regional matrix test cases defined for region '{region}'.{Colors.RESET}")
        canonical_regions = [r for r in REGIONAL_MATRICES.keys() if "_" not in r]
        print(f"        Available regions: {', '.join(canonical_regions)}")
        return False

    pbf_path = check_prerequisites(region, jar_path, custom_pbf, is_region=True)
    output_dir = os.path.join(output_base, region)
    graph_dir = os.path.join(REPO_ROOT, ".graphhopper", region)

    print(f"Running {len(test_cases)} multi-state corridor test cases for region {Colors.BOLD}{region.upper()}{Colors.RESET}...\n")
    results = []
    for tc in test_cases:
        print(
            f"[{tc.id:02d}/{len(test_cases):02d}] Testing [{tc.category}] {tc.start} ➔ {tc.end} ({tc.days}d, {tc.units})... ",
            end="",
            flush=True,
        )
        res = run_test_case(tc, region, jar_path, pbf_path, output_dir, graph_dir)
        status_label = f"{Colors.GREEN}✓ PASS{Colors.RESET}" if res.passed else f"{Colors.RED}✗ FAIL{Colors.RESET}"
        print(f"{status_label} ({res.elapsed_sec:.2f}s, {res.total_distance})")
        results.append(res)

    return print_summary_report(region, results, output_dir, is_region=True)


def run_nationwide_matrix(
    jar_path: str,
    custom_pbf: str | None = None,
    output_base: str = DEFAULT_OUTPUT_BASE,
) -> bool:
    """Run nationwide Coast-to-Coast corridor matrix test suite."""
    test_cases = USA_NATIONWIDE_MATRIX
    pbf_path = check_prerequisites("us", jar_path, custom_pbf, is_region=True)
    output_dir = os.path.join(output_base, "nationwide")
    graph_dir = os.path.join(REPO_ROOT, ".graphhopper", "us")

    print(f"Running {len(test_cases)} nationwide Coast-to-Coast test cases for {Colors.BOLD}USA NATIONWIDE{Colors.RESET}...\n")
    results = []
    for tc in test_cases:
        print(
            f"[{tc.id:02d}/{len(test_cases):02d}] Testing [{tc.category}] {tc.start} ➔ {tc.end} ({tc.days}d, {tc.units})... ",
            end="",
            flush=True,
        )
        res = run_test_case(tc, "us", jar_path, pbf_path, output_dir, graph_dir)
        status_label = f"{Colors.GREEN}✓ PASS{Colors.RESET}" if res.passed else f"{Colors.RED}✗ FAIL{Colors.RESET}"
        print(f"{status_label} ({res.elapsed_sec:.2f}s, {res.total_distance})")
        results.append(res)

    return print_summary_report("NATIONWIDE USA", results, output_dir, is_region=True)


def run_batch_region_states(
    region: str,
    jar_path: str,
    custom_pbf: str | None = None,
    output_base: str = DEFAULT_OUTPUT_BASE,
) -> bool:
    """Run state matrices for all individual states in a region sequentially."""
    states = REGION_STATES.get(region.lower())
    if not states:
        print(f"{Colors.RED}[ERROR] No state mapping defined for region '{region}'.{Colors.RESET}")
        canonical_regions = [r for r in REGION_STATES.keys() if "_" not in r]
        print(f"        Available regions: {', '.join(canonical_regions)}")
        return False

    # Deduplicate states while preserving order
    seen = set()
    unique_states = [s for s in states if not (s in seen or seen.add(s))]

    print(f"\n{Colors.CYAN}{Colors.BOLD}=== BATCH EXECUTION: States in {region.upper()} ({len(unique_states)} states) ==={Colors.RESET}\n")
    all_passed = True
    for s in unique_states:
        success = run_state_matrix(s, jar_path, custom_pbf, output_base)
        if not success:
            all_passed = False
    return all_passed


def main():
    parser = argparse.ArgumentParser(description="PathPress Location Pair Matrix Automated Test Runner")
    parser.add_argument(
        "--state",
        type=str,
        default=None,
        help=f"State matrix to test ({', '.join([s for s in STATE_MATRICES.keys() if '_' not in s])}, or 'all').",
    )
    parser.add_argument(
        "--region",
        type=str,
        default=None,
        help="Regional corridor matrix to test (us-northeast, us-pacific, us-west, us-midwest, us-south, or 'all').",
    )
    parser.add_argument(
        "--nationwide",
        "--country",
        action="store_true",
        help="Run nationwide Coast-to-Coast test matrix against US nationwide PBF (data/us-latest.osm.pbf).",
    )
    parser.add_argument(
        "--batch-states",
        action="store_true",
        help="When provided with --region, sequentially execute state matrices for all individual states in that region.",
    )
    parser.add_argument("--jar", type=str, default=DEFAULT_JAR_PATH, help="Path to standalone shadow JAR")
    parser.add_argument("--pbf", type=str, default=None, help="Explicit path to OSM PBF data file")
    parser.add_argument("--output-dir", type=str, default=DEFAULT_OUTPUT_BASE, help="Output directory for generated PDFs")
    parser.add_argument("--no-color", action="store_true", help="Disable ANSI colors in terminal output")

    args = parser.parse_args()

    if args.no_color or not sys.stdout.isatty():
        Colors.disable()

    if args.nationwide:
        success = run_nationwide_matrix(args.jar, args.pbf, args.output_dir)
        sys.exit(0 if success else 1)

    if args.region:
        target_region = args.region.lower().replace("_", "-").replace(" ", "-")
        if target_region in ("usa", "us", "nationwide", "united-states"):
            success = run_nationwide_matrix(args.jar, args.pbf, args.output_dir)
            sys.exit(0 if success else 1)
        elif target_region == "all":
            canonical_regions = ["us-northeast", "us-pacific", "us-west", "us-midwest", "us-south"]
            all_passed = True
            for r in canonical_regions:
                if args.batch_states:
                    success = run_batch_region_states(r, args.jar, args.pbf, args.output_dir)
                else:
                    success = run_region_matrix(r, args.jar, args.pbf, args.output_dir)
                if not success:
                    all_passed = False
            sys.exit(0 if all_passed else 1)
        elif target_region in REGIONAL_MATRICES:
            if args.batch_states:
                success = run_batch_region_states(target_region, args.jar, args.pbf, args.output_dir)
            else:
                success = run_region_matrix(target_region, args.jar, args.pbf, args.output_dir)
            sys.exit(0 if success else 1)
        else:
            print(f"{Colors.RED}[ERROR] Unknown region '{args.region}'.{Colors.RESET}")
            canonical_regions = ["us-northeast", "us-pacific", "us-west", "us-midwest", "us-south"]
            print(f"        Valid options are: {', '.join(canonical_regions)}, or 'all'.")
            sys.exit(1)
    else:
        target_state = (args.state or "california").lower().replace("_", "-").replace(" ", "-")
        if target_state == "all":
            seen_matrices = set()
            states_to_run = []
            for s, matrix in STATE_MATRICES.items():
                matrix_id = id(matrix)
                if matrix_id not in seen_matrices:
                    seen_matrices.add(matrix_id)
                    states_to_run.append(s)
        elif target_state in STATE_MATRICES:
            states_to_run = [target_state]
        else:
            print(f"{Colors.RED}[ERROR] Unknown state '{args.state}'.{Colors.RESET}")
            canonical_states = [s for s in STATE_MATRICES.keys() if "_" not in s]
            print(f"        Valid options are: {', '.join(canonical_states)}, or 'all'.")
            sys.exit(1)

        all_states_passed = True
        for state in states_to_run:
            success = run_state_matrix(state, args.jar, args.pbf, args.output_dir)
            if not success:
                all_states_passed = False

        sys.exit(0 if all_states_passed else 1)


if __name__ == "__main__":
    main()
