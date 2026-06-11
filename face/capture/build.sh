#!/usr/bin/env bash
# Compile WindowCapture.swift → binary at face/capture/WindowCapture
# Requires Xcode (Command Line Tools).  Run once; re-run after editing the Swift source.

set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="$DIR/WindowCapture"

echo "Building WindowCapture…"
# NOTE: -O (whole-module optimisation) causes startCapture() to hang inside
# dispatchMain() due to an async continuation being optimised away.  Use -Onone
# so the cooperative thread pool correctly services the SCStream callbacks.
swiftc -Onone \
  -o "$OUT" \
  "$DIR/WindowCapture.swift" \
  -framework Foundation \
  -framework ScreenCaptureKit \
  -framework CoreGraphics \
  -framework CoreVideo \
  -framework CoreImage \
  -framework AppKit \
  2>&1

# NOTE: strip is intentionally disabled. strip can alter Objective-C runtime
# metadata in ways that break @objc Swift classes used as SCStreamOutput
# delegates — symptoms include startCapture() hanging indefinitely.
# strip "$OUT" 2>/dev/null || true
echo "Built: $OUT ($(du -sh "$OUT" | cut -f1))"
