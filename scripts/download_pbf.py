#!/usr/bin/env python3
"""
PathPress OSM PBF Spatial Data Downloader.

Fetches free OpenStreetMap spatial PBF files across North American geographic tiers:
- US Individual States (50 states + DC)
- US Regions (us-northeast, us-midwest, us-south, us-west, us-pacific)
- Nationwide USA (us, usa, united-states)
- Canadian Provinces & Trans-Canada (ontario, quebec, british-columbia, etc.)
- North America Continental (north-america, canada, mexico)

Usage:
    python3 scripts/download_pbf.py texas
    python3 scripts/download_pbf.py us-northeast
    python3 scripts/download_pbf.py usa
    python3 scripts/download_pbf.py "British Columbia"
    python3 scripts/download_pbf.py --all
    python3 scripts/download_pbf.py --list
"""

import argparse
import os
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA_DIR = os.path.join(REPO_ROOT, "data")

ALL_US_STATES: list[str] = [
    "alabama",
    "alaska",
    "arizona",
    "arkansas",
    "california",
    "colorado",
    "connecticut",
    "delaware",
    "district-of-columbia",
    "florida",
    "georgia",
    "hawaii",
    "idaho",
    "illinois",
    "indiana",
    "iowa",
    "kansas",
    "kentucky",
    "louisiana",
    "maine",
    "maryland",
    "massachusetts",
    "michigan",
    "minnesota",
    "mississippi",
    "missouri",
    "montana",
    "nebraska",
    "nevada",
    "new-hampshire",
    "new-jersey",
    "new-mexico",
    "new-york",
    "north-carolina",
    "north-dakota",
    "ohio",
    "oklahoma",
    "oregon",
    "pennsylvania",
    "rhode-island",
    "south-carolina",
    "south-dakota",
    "tennessee",
    "texas",
    "utah",
    "vermont",
    "virginia",
    "washington",
    "west-virginia",
    "wisconsin",
    "wyoming",
]

US_REGIONS: dict[str, str] = {
    "us-northeast": "https://download.geofabrik.de/north-america/us-northeast-latest.osm.pbf",
    "us-midwest": "https://download.geofabrik.de/north-america/us-midwest-latest.osm.pbf",
    "us-south": "https://download.geofabrik.de/north-america/us-south-latest.osm.pbf",
    "us-west": "https://download.geofabrik.de/north-america/us-west-latest.osm.pbf",
    "us-pacific": "https://download.geofabrik.de/north-america/us-pacific-latest.osm.pbf",
}

CANADIAN_PROVINCES: list[str] = [
    "alberta",
    "british-columbia",
    "manitoba",
    "new-brunswick",
    "newfoundland-and-labrador",
    "northwest-territories",
    "nova-scotia",
    "nunavut",
    "ontario",
    "prince-edward-island",
    "quebec",
    "saskatchewan",
    "yukon",
]

NORTH_AMERICA_TIERS: dict[str, str] = {
    "us": "https://download.geofabrik.de/north-america/us-latest.osm.pbf",
    "usa": "https://download.geofabrik.de/north-america/us-latest.osm.pbf",
    "united-states": "https://download.geofabrik.de/north-america/us-latest.osm.pbf",
    "north-america": "https://download.geofabrik.de/north-america-latest.osm.pbf",
    "canada": "https://download.geofabrik.de/north-america/canada-latest.osm.pbf",
    "mexico": "https://download.geofabrik.de/north-america/mexico-latest.osm.pbf",
}

STATE_ALIASES: dict[str, str] = {
    "dc": "district-of-columbia",
    "d.c.": "district-of-columbia",
    "washington dc": "district-of-columbia",
    "washington d.c.": "district-of-columbia",
    "district of columbia": "district-of-columbia",
    "bc": "british-columbia",
    "pei": "prince-edward-island",
}


def normalize_slug(name: str) -> str:
    """Normalize input target name or alias into a valid slug."""
    clean = name.strip().lower().replace("_", "-").replace(" ", "-")
    clean = STATE_ALIASES.get(name.strip().lower(), clean)
    clean = STATE_ALIASES.get(clean, clean)
    return clean


def resolve_download_url(slug: str) -> tuple[str, str]:
    """
    Resolve the Geofabrik download URL and canonical destination filename for any slug.
    Returns (url, filename).
    """
    if slug in NORTH_AMERICA_TIERS:
        url = NORTH_AMERICA_TIERS[slug]
        filename = url.split("/")[-1]
        return url, filename

    if slug in US_REGIONS:
        url = US_REGIONS[slug]
        filename = f"{slug}-latest.osm.pbf"
        return url, filename

    if slug in CANADIAN_PROVINCES:
        url = f"https://download.geofabrik.de/north-america/canada/{slug}-latest.osm.pbf"
        filename = f"{slug}-latest.osm.pbf"
        return url, filename

    # Default: US State extract
    url = f"https://download.geofabrik.de/north-america/us/{slug}-latest.osm.pbf"
    filename = f"{slug}-latest.osm.pbf"
    return url, filename


def format_size(bytes_num: int) -> str:
    """Format bytes into human-readable string."""
    if bytes_num >= 1024 * 1024 * 1024:
        return f"{bytes_num / (1024 * 1024 * 1024):.2f} GB"
    elif bytes_num >= 1024 * 1024:
        return f"{bytes_num / (1024 * 1024):.1f} MB"
    elif bytes_num >= 1024:
        return f"{bytes_num / 1024:.1f} KB"
    return f"{bytes_num} B"


def download_single_pbf(
    target_input: str,
    force: bool = False,
    verbose: bool = True,
) -> str:
    """
    Download OSM PBF file for a given state, region, or corridor.
    Returns the absolute path to the downloaded PBF file.
    """
    slug = normalize_slug(target_input)
    os.makedirs(DATA_DIR, exist_ok=True)
    url, filename = resolve_download_url(slug)
    dest_path = os.path.join(DATA_DIR, filename)

    if not force and os.path.exists(dest_path) and os.path.getsize(dest_path) > 1024 * 100:
        if verbose:
            size_str = format_size(os.path.getsize(dest_path))
            print(f"✓ {filename} already exists in data/ directory ({size_str}).")
        return dest_path

    if verbose:
        print("=" * 70)
        print("PathPress OSM Spatial PBF Downloader")
        print(f"Target: {target_input} -> {slug}")
        print(f"URL:    {url}")
        print(f"Dest:   {dest_path}")
        print("=" * 70)
        print(f"Downloading {filename} from Geofabrik...")

    temp_path = dest_path + ".tmp"
    start_time = time.time()
    try:
        req = urllib.request.Request(
            url,
            headers={"User-Agent": "PathPress-OSM-Downloader/1.0"},
        )
        with urllib.request.urlopen(req, timeout=180) as response, open(temp_path, "wb") as out_file:
            total_bytes = int(response.headers.get("Content-Length", 0))
            downloaded = 0
            chunk_size = 1024 * 1024  # 1 MB chunk

            while True:
                chunk = response.read(chunk_size)
                if not chunk:
                    break
                out_file.write(chunk)
                downloaded += len(chunk)
                if verbose and total_bytes > 0:
                    percent = (downloaded / total_bytes) * 100.0
                    mb_down = downloaded / (1024 * 1024)
                    mb_tot = total_bytes / (1024 * 1024)
                    print(
                        f"\rDownloading: {percent:5.1f}% [{mb_down:7.1f} / {mb_tot:7.1f} MB]",
                        end="",
                        flush=True,
                    )

        if verbose and total_bytes > 0:
            print()

        os.replace(temp_path, dest_path)
        elapsed = time.time() - start_time
        size_str = format_size(os.path.getsize(dest_path))

        if verbose:
            print(f"✓ Download complete: {dest_path} ({size_str} in {elapsed:.1f}s)")
        return dest_path
    except urllib.error.HTTPError as e:
        if os.path.exists(temp_path):
            os.remove(temp_path)
        raise RuntimeError(f"HTTP {e.code} error fetching {url}. Target '{slug}' may be misspelled or unavailable.") from e
    except Exception as e:
        if os.path.exists(temp_path):
            os.remove(temp_path)
        raise RuntimeError(f"Failed to download {filename}: {e}") from e


def download_all_states(max_workers: int = 4, force: bool = False) -> None:
    """Download all 50 US states + DC in parallel."""
    print(f"Starting bulk download for {len(ALL_US_STATES)} US states into {DATA_DIR}...")
    start_all = time.time()
    results = []

    def _worker(slug: str):
        t0 = time.time()
        try:
            pbf_path = download_single_pbf(slug, force=force, verbose=False)
            sz = format_size(os.path.getsize(pbf_path))
            return slug, True, sz, time.time() - t0
        except Exception as err:
            return slug, False, str(err), time.time() - t0

    with ThreadPoolExecutor(max_workers=max_workers) as executor:
        futures = {executor.submit(_worker, slug): slug for slug in ALL_US_STATES}
        for future in as_completed(futures):
            res = future.result()
            results.append(res)
            slug, success, msg, el = res
            status = "✓" if success else "✗"
            time_str = f" in {el:.1f}s" if el > 0.1 else ""
            print(f"[{len(results):02d}/{len(ALL_US_STATES):02d}] {status} {slug.ljust(24)} : {msg}{time_str}")

    total_time = time.time() - start_all
    success_count = sum(1 for r in results if r[1])
    print(f"\nFinished: {success_count}/{len(ALL_US_STATES)} states ready in {total_time:.1f}s.")


def list_pbf_status() -> None:
    """Display tabular status of all state, regional, and continental spatial files in data/."""
    print("=" * 65)
    print(f"{'CATEGORY / TARGET':<32} | {'STATUS':<10} | {'FILE SIZE':<15}")
    print("=" * 65)

    def print_section(title: str, items: list[str]):
        print(f"\n-- {title} --")
        present = 0
        total_sz = 0
        for slug in items:
            _, filename = resolve_download_url(slug)
            pbf_path = os.path.join(DATA_DIR, filename)
            if os.path.exists(pbf_path) and os.path.getsize(pbf_path) > 1024 * 100:
                sz = os.path.getsize(pbf_path)
                total_sz += sz
                present += 1
                print(f"{slug:<32} | {'READY':<10} | {format_size(sz):<15}")
            else:
                print(f"{slug:<32} | {'MISSING':<10} | {'--':<15}")
        return present, total_sz

    us_present, us_bytes = print_section("US States (50 + DC)", ALL_US_STATES)
    reg_present, reg_bytes = print_section("US Regions", list(US_REGIONS.keys()))
    ca_present, ca_bytes = print_section("Canadian Provinces", CANADIAN_PROVINCES)
    nat_present, nat_bytes = print_section("Nationwide & North America", list(NORTH_AMERICA_TIERS.keys()))

    grand_total_files = us_present + reg_present + ca_present + nat_present
    grand_total_bytes = us_bytes + reg_bytes + ca_bytes + nat_bytes

    print("\n" + "=" * 65)
    print(f"Summary: {grand_total_files} extracts present in data/ ({format_size(grand_total_bytes)} total).")


def main():
    parser = argparse.ArgumentParser(
        description="PathPress OSM PBF Spatial Data Downloader",
        epilog="Examples:\n  python3 scripts/download_pbf.py texas\n  python3 scripts/download_pbf.py us-northeast\n  python3 scripts/download_pbf.py usa\n  python3 scripts/download_pbf.py 'British Columbia'\n  python3 scripts/download_pbf.py --all\n  python3 scripts/download_pbf.py --list",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "target",
        nargs="?",
        default=None,
        help="State, region, or corridor to download (e.g. texas, us-northeast, usa, 'British Columbia')",
    )
    parser.add_argument("--all", action="store_true", help="Download all 50 US States + DC in parallel")
    parser.add_argument("--list", "--status", action="store_true", help="List download status across all tiers")
    parser.add_argument("--force", action="store_true", help="Force re-download even if file already exists")
    parser.add_argument("--workers", type=int, default=4, help="Parallel worker threads for --all (default: 4)")

    args = parser.parse_args()

    if args.list:
        list_pbf_status()
        sys.exit(0)

    if args.all:
        download_all_states(max_workers=args.workers, force=args.force)
        sys.exit(0)

    if args.target:
        try:
            download_single_pbf(args.target, force=args.force, verbose=True)
            sys.exit(0)
        except Exception as e:
            print(f"\n[ERROR] {e}", file=sys.stderr)
            sys.exit(1)

    parser.print_help()
    sys.exit(1)


if __name__ == "__main__":
    main()
