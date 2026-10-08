#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Tiny LAN file server: GET serves serve/, uploads (browser form at /, or `curl -T file http://host:8000/name`) land in serve/uploads/.
No auth, so: LAN only, uploads confined to one directory, 100 MB cap, names sanitised, nothing overwritten (a counter is appended).
Usage: scripts/serve.py [port=8000]"""
import cgi, html, os, re, sys
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "serve")
UP = os.path.join(ROOT, "uploads")
MAX = 100 * 1024 * 1024
os.makedirs(UP, exist_ok=True)

def safe_name(n):
    n = re.sub(r"[^A-Za-z0-9._-]", "_", os.path.basename(n or "upload.bin")).lstrip(".") or "upload.bin"
    base, ext = os.path.splitext(n); out, i = n, 1
    while os.path.exists(os.path.join(UP, out)):
        out = f"{base}-{i}{ext}"; i += 1
    return out

class H(SimpleHTTPRequestHandler):
    def __init__(self, *a, **k): super().__init__(*a, directory=ROOT, **k)

    def list_directory(self, path):  # add the upload form above the normal listing
        return super().list_directory(path)

    def do_GET(self):
        if self.path in ("/", "/index.html"):
            files = "".join(f'<li><a href="{html.escape(n)}{"/" if os.path.isdir(os.path.join(ROOT, n)) else ""}">{html.escape(n)}</a>' for n in sorted(os.listdir(ROOT)) if n != "uploads")
            ups = "".join(f'<li><a href="uploads/{html.escape(n)}">{html.escape(n)}</a>' for n in sorted(os.listdir(UP)))
            body = f"""<!doctype html><meta name=viewport content="width=device-width"><title>hu15</title>
<h3>Files</h3><ul>{files}</ul><h3>Upload</h3>
<form method=post enctype=multipart/form-data action=/upload><input type=file name=f><input type=submit value=Upload></form>
<h3>Uploaded</h3><ul>{ups}</ul>""".encode()
            self.send_response(200); self.send_header("Content-Type", "text/html"); self.send_header("Content-Length", str(len(body))); self.end_headers()
            self.wfile.write(body); return
        super().do_GET()

    def _save(self, stream, name, length):
        if length > MAX: return self._reply(413, "too large")
        name = safe_name(name); left = length
        with open(os.path.join(UP, name), "wb") as f:
            while left > 0:
                b = stream.read(min(65536, left))
                if not b: break
                f.write(b); left -= len(b)
        print(f"uploaded {name} ({length} B) from {self.client_address[0]}", flush=True)
        self._reply(200, f"saved as uploads/{name}")

    def do_POST(self):
        if self.path == "/note":   # plain urlencoded text from serve/honda/unit-info.html (old browsers, no file picker needed)
            from urllib.parse import parse_qs
            length = int(self.headers.get("Content-Length", 0))
            if length > 65536: return self._reply(413, "too large")
            text = parse_qs(self.rfile.read(length).decode("utf-8", "replace")).get("text", [""])[0]
            name = safe_name("unit-info.txt")
            open(os.path.join(UP, name), "w", encoding="utf-8").write(text)
            print(f"note {name} ({len(text)} chars) from {self.client_address[0]}", flush=True)
            return self._reply(200, f"Thanks. Saved as uploads/{name}")
        if self.path != "/upload": return self._reply(404, "not found")
        length = int(self.headers.get("Content-Length", 0))
        if length > MAX: return self._reply(413, "too large")
        form = cgi.FieldStorage(fp=self.rfile, headers=self.headers, environ={"REQUEST_METHOD": "POST", "CONTENT_TYPE": self.headers["Content-Type"]})
        item = form["f"] if "f" in form else None
        if item is None or not item.filename: return self._reply(400, "no file")
        data = item.file.read()
        import io; self._save(io.BytesIO(data), item.filename, len(data))

    def do_PUT(self):
        name = self.path.split("?")[0].strip("/")
        if not name or "/" in name: return self._reply(400, "PUT /filename")
        self._save(self.rfile, name, int(self.headers.get("Content-Length", 0)))

    def _reply(self, code, msg):
        b = (msg + '\n<a href="/">back</a>').encode()
        self.send_response(code); self.send_header("Content-Type", "text/html"); self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)

ThreadingHTTPServer(("0.0.0.0", int(sys.argv[1]) if len(sys.argv) > 1 else 8000), H).serve_forever()
