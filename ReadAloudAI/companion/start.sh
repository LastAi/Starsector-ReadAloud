#!/bin/sh
# Read Aloud companion app (Linux, macOS). Run: sh start.sh
# The first run downloads uv and Python into runtime/ (about 50 MB); nothing is
# installed system-wide. Arguments are passed to the app (see app/server.py --help).
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
RT="$HERE/runtime"
UV="$RT/uv/uv"
UV_VERSION="0.13.7"
mkdir -p "$RT/uv"

if [ ! -x "$UV" ]; then
  case "$(uname -s)-$(uname -m)" in
    Linux-x86_64) target="x86_64-unknown-linux-gnu" ;;
    Linux-aarch64|Linux-arm64) target="aarch64-unknown-linux-gnu" ;;
    Darwin-arm64) target="aarch64-apple-darwin" ;;
    Darwin-x86_64) target="x86_64-apple-darwin" ;;
    *) echo "Unsupported system $(uname -s) $(uname -m); install uv yourself and put it at $UV" >&2; exit 1 ;;
  esac
  file="uv-$target.tar.gz"
  url="https://github.com/astral-sh/uv/releases/download/$UV_VERSION/$file"
  tmp="$RT/uv/download"
  rm -rf "$tmp"; mkdir -p "$tmp"
  echo "Downloading uv $UV_VERSION ($file)..."
  if command -v curl >/dev/null 2>&1; then get() { curl -fsSL "$1" -o "$2"; }
  elif command -v wget >/dev/null 2>&1; then get() { wget -q "$1" -O "$2"; }
  else echo "Need curl or wget to download uv." >&2; exit 1; fi
  get "$url" "$tmp/$file"
  get "$url.sha256" "$tmp/$file.sha256"
  expected="$(cut -d' ' -f1 < "$tmp/$file.sha256")"
  if command -v sha256sum >/dev/null 2>&1; then actual="$(sha256sum "$tmp/$file" | cut -d' ' -f1)"
  else actual="$(shasum -a 256 "$tmp/$file" | cut -d' ' -f1)"; fi
  if [ "$expected" != "$actual" ]; then echo "uv download checksum mismatch" >&2; rm -rf "$tmp"; exit 1; fi
  tar -xzf "$tmp/$file" -C "$tmp"
  cp "$(find "$tmp" -type f -name uv | head -n 1)" "$UV"
  chmod +x "$UV"
  rm -rf "$tmp"
fi

# Keep everything uv does inside runtime/.
export UV_CACHE_DIR="$RT/uv-cache"
export UV_PYTHON_INSTALL_DIR="$RT/python"
export UV_PYTHON_CACHE_DIR="$RT/uv-cache/python"
export UV_PYTHON_BIN_DIR="$RT/python/bin"
export UV_TOOL_DIR="$RT/uv-tools"
export UV_MANAGED_PYTHON=1
export UV_NO_CONFIG=1
export READALOUD_UV="$UV"

if [ "${1:-}" = "--background" ]; then
  nohup "$UV" run --no-project --python 3.12 "$HERE/app/server.py" "$@" > "$RT/console.log" 2>&1 &
  exit 0
fi
exec "$UV" run --no-project --python 3.12 "$HERE/app/server.py" "$@"
