#!/usr/bin/env python3
"""
PathPress LLM Waypoint Reliability Benchmark.

Runs N iterations of an itinerary scenario (e.g. Example 2: SJ -> SD coastal) across specified Ollama models,
measuring waypoint accuracy, validator pass/retry rates, and latency.

Automatically runs `ollama stop <model>` after each model batch to reclaim system memory.

Usage:
    python3 scripts/benchmark_models.py --runs 5
    python3 scripts/benchmark_models.py --models "qwen3.6:35b-mlx,gemma4:26b-mlx,qwen3.5:9b-mlx" --runs 5
"""

import argparse
import os
import re
import subprocess
import sys
import time
from dataclasses import dataclass, field

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAR_PATH = os.path.join(REPO_ROOT, "build", "libs", "pathpress-0.1.0-standalone.jar")

DEFAULT_MODELS = [
    "qwen3.6:35b-mlx",
    "gemma4:26b-mlx",
    "gemma4:12b-mlx",
    "qwen3.5:9b-mlx",
]

DEFAULT_PROMPT = "toddler friendly, coastal highway route, prefer scenic beach town or historic village for overnight stay"


@dataclass
class RunMetrics:
    run_index: int
    elapsed_sec: float
    passed_first_try: bool
    required_retry: bool
    triggered_fallback: bool
    waypoints: list[str] = field(default_factory=list)
    raw_output: str = ""


@dataclass
class ModelSummary:
    model_name: str
    total_runs: int
    first_try_passes: int
    retry_passes: int
    fallbacks: int
    avg_latency_sec: float
    waypoint_counts: dict[str, int] = field(default_factory=dict)


def stop_ollama_model(model_name: str):
    """Unload the specified model from Ollama VRAM/RAM."""
    print(f"  ➜ Unloading {model_name} from Ollama VRAM (`ollama stop {model_name}`)...")
    try:
        subprocess.run(["ollama", "stop", model_name], capture_output=True, timeout=10)
    except Exception as e:
        print(f"    Warning: Failed to stop {model_name}: {e}")


def run_single_iteration(
    model: str,
    run_idx: int,
    total_runs: int,
    start: str,
    end: str,
    days: int,
    prompt: str,
    pbf: str,
) -> RunMetrics:
    """Executes a single PathPress CLI run and parses output logs."""
    cmd = [
        "java",
        "-jar",
        JAR_PATH,
        "--start",
        start,
        "--end",
        end,
        "--days",
        str(days),
        "--pbf",
        pbf,
        "--llm-provider",
        "ollama",
        "--llm-model",
        model,
        "--prompt",
        prompt,
        "--output",
        "build/benchmark_tmp.pdf",
    ]

    print(f"    [Run {run_idx}/{total_runs}] Running PathPress...", end="", flush=True)
    t0 = time.time()
    proc = subprocess.run(cmd, capture_output=True, text=True, cwd=REPO_ROOT)
    elapsed = time.time() - t0

    output = proc.stdout + "\n" + proc.stderr

    # Parse waypoint names from routing log or geocoding log
    routing_wps = re.findall(r"Routing via \d+ intermediate waypoints: (.+)", output)
    if routing_wps:
        unique_wps = [w.strip() for w in routing_wps[0].split(",") if w.strip()]
    else:
        waypoints = re.findall(r"Geocoding intermediate waypoint '([^']+)'", output)
        unique_wps = list(dict.fromkeys(waypoints))

    required_retry = "Retrying trip planning with LLM" in output
    passed_retry = "Retry attempt produced valid waypoints!" in output
    triggered_fallback = "Injecting default CA coastal anchor" in output or "Clearing invalid waypoints" in output

    passed_first_try = not required_retry and not triggered_fallback

    status_str = "FIRST-TRY VALID"
    if required_retry and passed_retry:
        status_str = "RETRY PASSED"
    elif triggered_fallback:
        status_str = "FALLBACK TRIGGERED"

    wp_str = ", ".join(unique_wps) if unique_wps else "(None)"
    print(f" {elapsed:.1f}s -> {status_str} | Waypoints: {wp_str}")

    return RunMetrics(
        run_index=run_idx,
        elapsed_sec=elapsed,
        passed_first_try=passed_first_try,
        required_retry=required_retry and passed_retry,
        triggered_fallback=triggered_fallback,
        waypoints=unique_wps,
        raw_output=output,
    )


def benchmark_model(
    model: str,
    runs: int,
    start: str,
    end: str,
    days: int,
    prompt: str,
    pbf: str,
    stop_after: bool,
) -> ModelSummary:
    """Runs N benchmark iterations for a given Ollama model."""
    print("\n================================================================================")
    print(f" BENCHMARKING MODEL: {model} ({runs} iterations)")
    print("================================================================================")

    results: list[RunMetrics] = []
    waypoint_counts: dict[str, int] = {}

    for i in range(1, runs + 1):
        res = run_single_iteration(model, i, runs, start, end, days, prompt, pbf)
        results.append(res)
        for wp in res.waypoints:
            waypoint_counts[wp] = waypoint_counts.get(wp, 0) + 1

    first_try_passes = sum(1 for r in results if r.passed_first_try)
    retry_passes = sum(1 for r in results if r.required_retry)
    fallbacks = sum(1 for r in results if r.triggered_fallback)
    avg_latency = sum(r.elapsed_sec for r in results) / max(1, len(results))

    if stop_after:
        stop_ollama_model(model)

    return ModelSummary(
        model_name=model,
        total_runs=runs,
        first_try_passes=first_try_passes,
        retry_passes=retry_passes,
        fallbacks=fallbacks,
        avg_latency_sec=avg_latency,
        waypoint_counts=waypoint_counts,
    )


def print_summary_table(summaries: list[ModelSummary]):
    """Prints a clear summary comparison table across all models."""
    print("\n" + "=" * 90)
    print(" BENCHMARK SUMMARY & RELIABILITY COMPARISON REPORT")
    print("=" * 90)

    header = f"{'MODEL':<20} | {'1ST TRY PASS':<12} | {'RETRY PASS':<10} | {'FALLBACK':<10} | {'AVG LATENCY':<12} | {'TOP WAYPOINTS'}"
    print(header)
    print("-" * 90)

    for s in summaries:
        first_pass_pct = f"{s.first_try_passes}/{s.total_runs} ({int(s.first_try_passes / s.total_runs * 100)}%)"
        retry_pct = f"{s.retry_passes}/{s.total_runs}"
        fallback_pct = f"{s.fallbacks}/{s.total_runs}"
        latency_str = f"{s.avg_latency_sec:.2f}s"

        top_wps = sorted(s.waypoint_counts.items(), key=lambda x: x[1], reverse=True)[:3]
        top_wp_str = ", ".join(f"{name} ({cnt})" for name, cnt in top_wps) if top_wps else "None"

        print(f"{s.model_name:<20} | {first_pass_pct:<12} | {retry_pct:<10} | {fallback_pct:<10} | {latency_str:<12} | {top_wp_str}")

    print("=" * 90 + "\n")


def main():
    parser = argparse.ArgumentParser(description="PathPress Model Benchmark Script")
    parser.add_argument(
        "--models",
        type=str,
        default=",".join(DEFAULT_MODELS),
        help="Comma-separated list of Ollama model names to benchmark",
    )
    parser.add_argument("--runs", type=int, default=5, help="Number of test iterations per model (default: 5)")
    parser.add_argument("--start", type=str, default="San Jose, CA", help="Start location")
    parser.add_argument("--end", type=str, default="San Diego, CA", help="End location")
    parser.add_argument("--days", type=int, default=2, help="Trip days")
    parser.add_argument("--prompt", type=str, default=DEFAULT_PROMPT, help="User prompt")
    parser.add_argument("--pbf", type=str, default="data/california-latest.osm.pbf", help="PBF file path")
    parser.add_argument("--no-stop", action="store_true", help="Do not unload models from Ollama after testing")

    args = parser.parse_args()

    if not os.path.exists(JAR_PATH):
        print(f"Error: Standalone JAR not found at {JAR_PATH}. Run `./gradlew build` first.")
        sys.exit(1)

    models_to_test = [m.strip() for m in args.models.split(",") if m.strip()]
    stop_after = not args.no_stop

    print("Starting PathPress LLM Benchmark...")
    print(f"Models: {models_to_test}")
    print(f"Iterations per model: {args.runs}")
    print(f"Scenario: {args.start} -> {args.end} ({args.days} days)")
    print(f'Prompt: "{args.prompt}"')

    summaries = []
    for model in models_to_test:
        summary = benchmark_model(
            model=model,
            runs=args.runs,
            start=args.start,
            end=args.end,
            days=args.days,
            prompt=args.prompt,
            pbf=args.pbf,
            stop_after=stop_after,
        )
        summaries.append(summary)

    print_summary_table(summaries)


if __name__ == "__main__":
    main()
