#!/usr/bin/env python3
"""Serve a WallHub Web preview with SPA fallback and precompressed assets."""

from __future__ import annotations

import argparse
from functools import partial
import http.server
import os
from pathlib import Path
import re
from urllib.parse import urlsplit

HASHED_ASSET = re.compile(r"^[0-9a-f]{20,}\.(?:wasm|js|ttf)$")


class PreviewHandler(http.server.SimpleHTTPRequestHandler):
    """Serve generated files without disguising missing build assets as HTML."""

    def end_headers(self) -> None:
        request_name = Path(urlsplit(self.path).path).name
        if request_name in {"", "index.html"}:
            self.send_header("Cache-Control", "no-store")
        elif HASHED_ASSET.match(request_name):
            self.send_header("Cache-Control", "public, max-age=31536000, immutable")
        else:
            self.send_header("Cache-Control", "no-cache")
        super().end_headers()

    def send_head(self):  # type: ignore[no-untyped-def]
        translated = Path(self.translate_path(self.path))
        if translated.is_dir():
            translated /= "index.html"
        if not translated.is_file():
            request_path = urlsplit(self.path).path
            if Path(request_path).suffix:
                self.send_error(404, "File not found")
                return None
            translated = Path(self.directory) / "index.html"

        accepts_gzip = "gzip" in self.headers.get("Accept-Encoding", "").lower()
        gzip_path = translated.with_name(translated.name + ".gz")
        served_path = gzip_path if accepts_gzip and gzip_path.is_file() else translated
        try:
            file = served_path.open("rb")
        except OSError:
            self.send_error(404, "File not found")
            return None

        stat = os.fstat(file.fileno())
        self.send_response(200)
        self.send_header("Content-Type", self.guess_type(str(translated)))
        self.send_header("Content-Length", str(stat.st_size))
        self.send_header("Last-Modified", self.date_time_string(stat.st_mtime))
        if served_path == gzip_path:
            self.send_header("Content-Encoding", "gzip")
            self.send_header("Vary", "Accept-Encoding")
        self.end_headers()
        return file


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--directory", required=True, type=Path)
    parser.add_argument("--bind", default="0.0.0.0")
    parser.add_argument("--port", default=4180, type=int)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    directory = args.directory.resolve(strict=True)
    if not (directory / "index.html").is_file():
        raise SystemExit(f"Missing preview entry point: {directory / 'index.html'}")
    handler = partial(PreviewHandler, directory=str(directory))
    server = http.server.ThreadingHTTPServer((args.bind, args.port), handler)
    print(f"Serving WallHub Web preview from {directory} on {args.bind}:{args.port}")
    server.serve_forever()


if __name__ == "__main__":
    main()
