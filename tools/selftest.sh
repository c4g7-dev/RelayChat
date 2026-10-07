#!/usr/bin/env bash
# Runs the dev self-test client on a virtual display (no window on your desktop) and prints the
# PASS/FAIL lines. Screenshots land in <run dir>/screenshots/. Extra arguments go to Gradle, e.g.
#   tools/selftest.sh -Pminecraft_version=26.3 -Pfabric_api_version=0.162.0+26.3 -Pselftest_dir=run-selftest-26.3
set -euo pipefail
cd "$(dirname "$0")/.."
export __GLX_VENDOR_LIBRARY_NAME=mesa LIBGL_ALWAYS_SOFTWARE=1
# Keep the game on the virtual display: SDL (26.3+) would otherwise pick the Wayland session.
unset WAYLAND_DISPLAY
export SDL_VIDEO_DRIVER=x11
log=run-selftest/selftest.log
mkdir -p run-selftest
xvfb-run -a -s "-screen 0 1920x1080x24" ./gradlew runSelftest --console=plain "$@" > "$log" 2>&1 || true
grep -E "RELAY-SELFTEST" "$log" || { echo "no self-test output, see $log"; exit 1; }
! grep -q "RELAY-SELFTEST FAIL" "$log"
