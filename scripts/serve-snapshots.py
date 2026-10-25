#!/usr/bin/env python3
"""
Lightweight snapshot gallery web server for viewing test captures remotely.
Serves images from client-desktop/target/snapshots/ on http://0.0.0.0:8088.
"""

import os
import sys
import glob
import mimetypes
import argparse
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import unquote, urlparse
from datetime import datetime

DEFAULT_PORT = 8088
SNAPSHOT_DIRS = [
    os.path.abspath("workspace/c2/client-desktop/target/snapshots"),
    os.path.abspath("client-desktop/target/snapshots"),
    os.path.abspath("target/snapshots"),
    os.path.expanduser("~/workspace/c2/client-desktop/target/snapshots"),
    os.path.abspath("../client-desktop/target/snapshots")
]

def find_snapshots_dir():
    for d in SNAPSHOT_DIRS:
        if os.path.isdir(d):
            return d
    # Default to creating it
    d = os.path.abspath("client-desktop/target/snapshots")
    os.makedirs(d, exist_ok=True)
    return d

HTML_TEMPLATE = """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>C2 Visual Scene Snapshots</title>
    <style>
        :root {
            --bg: #0f1218;
            --card-bg: #181d26;
            --border: #283142;
            --text: #e2e8f0;
            --text-dim: #94a3b8;
            --accent: #38bdf8;
            --accent-hover: #0ea5e9;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
            background-color: var(--bg);
            color: var(--text);
            padding: 24px;
            min-height: 100vh;
        }
        header {
            display: flex;
            justify-content: space-between;
            align-items: center;
            margin-bottom: 24px;
            padding-bottom: 16px;
            border-bottom: 1px solid var(--border);
            flex-wrap: wrap;
            gap: 12px;
        }
        h1 {
            font-size: 1.5rem;
            color: var(--accent);
            display: flex;
            align-items: center;
            gap: 8px;
        }
        .meta {
            color: var(--text-dim);
            font-size: 0.9rem;
        }
        .btn {
            background: var(--accent);
            color: #0b1120;
            font-weight: 600;
            padding: 8px 16px;
            border-radius: 6px;
            text-decoration: none;
            cursor: pointer;
            border: none;
            transition: background 0.2s;
        }
        .btn:hover { background: var(--accent-hover); }
        .grid {
            display: grid;
            grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
            gap: 20px;
        }
        .card {
            background: var(--card-bg);
            border: 1px solid var(--border);
            border-radius: 8px;
            overflow: hidden;
            display: flex;
            flex-direction: column;
            transition: transform 0.2s, box-shadow 0.2s;
        }
        .card:hover {
            transform: translateY(-2px);
            box-shadow: 0 8px 24px rgba(0,0,0,0.5);
            border-color: var(--accent);
        }
        .img-container {
            width: 100%;
            height: 240px;
            background: #000;
            display: flex;
            align-items: center;
            justify-content: center;
            overflow: hidden;
            cursor: pointer;
        }
        .img-container img {
            max-width: 100%;
            max-height: 100%;
            object-fit: contain;
            image-rendering: auto;
        }
        .card-body {
            padding: 14px;
            display: flex;
            flex-direction: column;
            gap: 6px;
        }
        .filename {
            font-weight: 600;
            font-size: 0.95rem;
            word-break: break-all;
            color: var(--text);
        }
        .details {
            display: flex;
            justify-content: space-between;
            font-size: 0.8rem;
            color: var(--text-dim);
        }
        .card-actions {
            margin-top: 8px;
            display: flex;
            gap: 8px;
        }
        .action-link {
            font-size: 0.8rem;
            color: var(--accent);
            text-decoration: none;
        }
        .action-link:hover { text-decoration: underline; }
        .empty {
            grid-column: 1 / -1;
            padding: 48px;
            text-align: center;
            color: var(--text-dim);
            background: var(--card-bg);
            border-radius: 8px;
            border: 1px dashed var(--border);
        }

        /* Lightbox modal */
        #lightbox {
            display: none;
            position: fixed;
            top: 0; left: 0; right: 0; bottom: 0;
            background: rgba(0,0,0,0.9);
            z-index: 1000;
            justify-content: center;
            align-items: center;
            padding: 20px;
        }
        #lightbox img {
            max-width: 95vw;
            max-height: 95vh;
            object-fit: contain;
            box-shadow: 0 0 30px rgba(0,0,0,0.8);
            border: 1px solid var(--border);
        }
    </style>
</head>
<body>
    <header>
        <div>
            <h1><span>📸</span> C2 Visual Scene Snapshots</h1>
            <div class="meta">Directory: <code>__DIRECTORY__</code> &bull; Total Images: __COUNT__</div>
        </div>
        <div>
            <button class="btn" onclick="location.reload()">↻ Refresh</button>
        </div>
    </header>

    <main class="grid">
        __CARDS__
    </main>

    <div id="lightbox" onclick="this.style.display='none'">
        <img id="lightbox-img" src="" alt="Full view">
    </div>

    <script>
        function openLightbox(src) {
            document.getElementById('lightbox-img').src = src;
            document.getElementById('lightbox').style.display = 'flex';
        }
        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') document.getElementById('lightbox').style.display = 'none';
        });
    </script>
</body>
</html>
"""

class SnapshotHandler(BaseHTTPRequestHandler):
    snapshots_dir = ""

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        parsed = urlparse(self.path)
        path = unquote(parsed.path)

        if path == "/" or path == "/index.html":
            self.serve_gallery()
        elif path.startswith("/snapshots/"):
            filename = os.path.basename(path)
            self.serve_file(filename)
        else:
            self.send_error(404, "Not Found")

    def serve_gallery(self):
        files = []
        for ext in ("*.png", "*.jpg", "*.jpeg", "*.webp", "*.glb"):
            files.extend(glob.glob(os.path.join(self.snapshots_dir, ext)))
        files.sort(key=lambda p: os.path.getmtime(p), reverse=True)

        cards = []
        for file_path in files:
            fname = os.path.basename(file_path)
            sz = os.path.getsize(file_path)
            mtime = datetime.fromtimestamp(os.path.getmtime(file_path)).strftime("%Y-%m-%d %H:%M:%S")
            size_str = f"{sz / 1024:.1f} KB" if sz < 1024 * 1024 else f"{sz / (1024 * 1024):.2f} MB"

            if fname.lower().endswith(".glb"):
                card_html = f"""
                <div class="card">
                    <div class="img-container" style="background:#111622;display:flex;align-items:center;justify-content:center;color:#38bdf8;font-size:2rem;">
                        <span>📦 3D GLB</span>
                    </div>
                    <div class="card-body">
                        <div class="filename">{fname}</div>
                        <div class="details">
                            <span>{size_str}</span>
                            <span>{mtime}</span>
                        </div>
                        <div class="card-actions">
                            <a class="action-link" href="/snapshots/{fname}" download>Download GLB Model</a>
                        </div>
                    </div>
                </div>
                """
            else:
                card_html = f"""
                <div class="card">
                    <div class="img-container" onclick="openLightbox('/snapshots/{fname}')">
                        <img src="/snapshots/{fname}" alt="{fname}" loading="lazy">
                    </div>
                    <div class="card-body">
                        <div class="filename">{fname}</div>
                        <div class="details">
                            <span>{size_str}</span>
                            <span>{mtime}</span>
                        </div>
                        <div class="card-actions">
                            <a class="action-link" href="/snapshots/{fname}" target="_blank">Open Full Image</a>
                            &bull;
                            <a class="action-link" href="/snapshots/{fname}" download>Download</a>
                        </div>
                    </div>
                </div>
                """
            cards.append(card_html)

        if not cards:
            cards_html = '<div class="empty">No snapshots found in target folder. Run tests to capture frames!</div>'
        else:
            cards_html = "\n".join(cards)

        html = HTML_TEMPLATE.replace("__DIRECTORY__", self.snapshots_dir) \
                             .replace("__COUNT__", str(len(files))) \
                             .replace("__CARDS__", cards_html)

        data = html.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-cache, no-store, must-revalidate")
        self.end_headers()
        self.wfile.write(data)

    def serve_file(self, filename):
        safe_path = os.path.abspath(os.path.join(self.snapshots_dir, filename))
        if not safe_path.startswith(self.snapshots_dir) or not os.path.isfile(safe_path):
            self.send_error(404, "File Not Found")
            return

        mime_type, _ = mimetypes.guess_type(safe_path)
        if filename.lower().endswith(".glb"):
            mime_type = "model/gltf-binary"
        elif not mime_type:
            mime_type = "application/octet-stream"

        sz = os.path.getsize(safe_path)
        self.send_response(200)
        self.send_header("Content-Type", mime_type)
        self.send_header("Content-Length", str(sz))
        self.send_header("Cache-Control", "public, max-age=60")
        self.end_headers()

        try:
            with open(safe_path, "rb") as f:
                while chunk := f.read(65536):
                    self.wfile.write(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass

def run_server(port, directory):
    SnapshotHandler.snapshots_dir = os.path.abspath(directory)
    server_address = ("0.0.0.0", port)
    httpd = ThreadingHTTPServer(server_address, SnapshotHandler)
    print(f"[SNAPSHOT-SERVER] Serving '{SnapshotHandler.snapshots_dir}' on http://0.0.0.0:{port} ...", flush=True)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n[SNAPSHOT-SERVER] Shutting down server...", flush=True)
        httpd.server_close()

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Snapshot gallery server.")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="Port to listen on (default: 8088)")
    parser.add_argument("--dir", default=None, help="Directory to serve snapshots from")
    args = parser.parse_args()

    target_dir = args.dir if args.dir else find_snapshots_dir()
    run_server(args.port, target_dir)
