#!/usr/bin/env bash
# Runs the companion app tests with any Python 3.10+ (standard library only).
# Uses runtime/uv's Python if the app has been set up, else python3 / python on PATH.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Scratch files: the workspace's .cache if there is one, else .build next to the mods (gitignored).
if [ -d "$HERE/../../../.cache" ]; then SCRATCH="$HERE/../../../.cache"; else SCRATCH="$HERE/../../.build"; fi
export READALOUD_TEST_RUNTIME="${READALOUD_TEST_RUNTIME:-$SCRATCH/readaloud-companion-test}"
mkdir -p "$READALOUD_TEST_RUNTIME"
PY=""
for c in python3 python; do
  if command -v "$c" >/dev/null 2>&1 && "$c" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)' 2>/dev/null; then PY="$c"; break; fi
done
if [ -z "$PY" ] && [ -x "$HERE/runtime/uv/uv" -o -x "$HERE/runtime/uv/uv.exe" ]; then
  UV="$HERE/runtime/uv/uv"; [ -x "$UV" ] || UV="$UV.exe"
  export UV_PYTHON_INSTALL_DIR="$HERE/runtime/python" UV_CACHE_DIR="$HERE/runtime/uv-cache" UV_MANAGED_PYTHON=1 UV_NO_CONFIG=1
  exec "$UV" run --no-project --python 3.12 -m unittest discover -s "$HERE/tests" -v
fi
[ -n "$PY" ] || { echo "Need Python 3.10+ (or run start.sh/start.cmd once to get one)" >&2; exit 1; }
cd "$HERE"
"$PY" -m unittest discover -s tests -v
