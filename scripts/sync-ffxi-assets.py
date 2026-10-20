#!/usr/bin/env python3
"""
Utility script to fetch or synchronize retail Final Fantasy XI ROM assets from
the public ROM endpoint into the local Wine / Lutris game directory:
$HOME/Games/final-fantasy-xi-online/drive_c/Program Files (x86)/PlayOnline/SquareEnix/FINAL FANTASY XI
"""

import os
import sys
import argparse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

DEFAULT_REMOTE = "https://ximv.dev/"
DEFAULT_LOCAL = os.path.expanduser(
    "~/Games/final-fantasy-xi-online/drive_c/Program Files (x86)/PlayOnline/SquareEnix/FINAL FANTASY XI"
)

# Core PC race base and default equipment DATs
RACE_FILES = [
    # Hume M
    "ROM/27/82.DAT", "ROM/27/87.DAT", "ROM/28/7.DAT", "ROM/28/52.DAT", "ROM/28/84.DAT", "ROM/28/116.DAT",
    # Hume F
    "ROM/32/58.DAT", "ROM/32/63.DAT", "ROM/32/111.DAT", "ROM/33/28.DAT", "ROM/33/60.DAT", "ROM/33/92.DAT",
    # Elvaan M
    "ROM/37/31.DAT", "ROM/37/36.DAT", "ROM/37/83.DAT", "ROM/37/127.DAT", "ROM/38/30.DAT", "ROM/38/61.DAT",
    # Elvaan F
    "ROM/42/4.DAT", "ROM/42/9.DAT", "ROM/42/56.DAT", "ROM/42/100.DAT", "ROM/43/3.DAT", "ROM/43/34.DAT",
    # TaruTaru M & F
    "ROM/46/93.DAT", "ROM/46/98.DAT", "ROM/47/17.DAT", "ROM/47/61.DAT", "ROM/47/92.DAT", "ROM/47/123.DAT",
    # Mithra
    "ROM/51/89.DAT", "ROM/51/94.DAT", "ROM/52/13.DAT", "ROM/52/57.DAT", "ROM/52/88.DAT", "ROM/52/119.DAT",
    # Galka
    "ROM/56/59.DAT", "ROM/56/64.DAT", "ROM/56/111.DAT", "ROM/57/27.DAT", "ROM/57/58.DAT", "ROM/57/89.DAT",
]

# Core starter zones & town NPCs
CORE_WORLD_FILES = [
    "FTABLE.DAT",
    "VTABLE.DAT",
    "ROM/1/31.DAT",   # South San d'Oria (Zone 230)
    "ROM/1/30.DAT",   # West Ronfaure (Zone 100)
    "ROM/3/116.DAT",  # Town Guard NPC model
]

def download_file(remote_url, dest_path):
    if os.path.exists(dest_path) and os.path.getsize(dest_path) > 0:
        return dest_path, False, os.path.getsize(dest_path)
    
    os.makedirs(os.path.dirname(dest_path), exist_ok=True)
    req = urllib.request.Request(remote_url, headers={"User-Agent": "C2-Asset-Sync/1.0"})
    with urllib.request.urlopen(req, timeout=20) as resp, open(dest_path, "wb") as f:
        data = resp.read()
        f.write(data)
    return dest_path, True, len(data)

def main():
    parser = argparse.ArgumentParser(description="Synchronize FFXI ROM assets into expected installation folder.")
    parser.add_argument("--dest", default=DEFAULT_LOCAL, help="Target FFXI directory (default: Wine/Lutris install dir)")
    parser.add_argument("--remote", default=DEFAULT_REMOTE, help="Base HTTP URL for remote ROM assets")
    parser.add_argument("--workers", type=int, default=4, help="Concurrent download workers")
    args = parser.parse_args()

    dest_dir = os.path.abspath(os.path.expanduser(args.dest))
    remote_base = args.remote.rstrip("/") + "/"
    print(f"=== FFXI Asset Synchronizer ===")
    print(f"Destination: {dest_dir}")
    print(f"Remote Base: {remote_base}")

    all_files = RACE_FILES + CORE_WORLD_FILES
    print(f"Total target assets to check/sync: {len(all_files)}")

    downloaded = 0
    cached = 0
    total_bytes = 0

    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = {}
        for rel in all_files:
            url = remote_base + rel
            out = os.path.join(dest_dir, rel)
            futures[pool.submit(download_file, url, out)] = rel

        for f in as_completed(futures):
            rel = futures[f]
            try:
                dest, was_dl, sz = f.result()
                total_bytes += sz
                if was_dl:
                    downloaded += 1
                    print(f"  [DOWNLOADED] {rel} ({sz:,} bytes)")
                else:
                    cached += 1
            except Exception as e:
                print(f"  [ERROR] {rel}: {e}", file=sys.stderr)

    print(f"\nSync complete: {downloaded} downloaded, {cached} already up-to-date. Total: {total_bytes / (1024 * 1024):.2f} MB")

if __name__ == "__main__":
    main()
