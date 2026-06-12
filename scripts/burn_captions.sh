#!/usr/bin/env bash
# Burn the Omo demo caption overlays straight into a video — no editor needed.
# Usage:  ./scripts/burn_captions.sh "/path/to/your-export.mp4" [outfile.mp4]
# Output: <name>-captioned.mp4 next to the input (or the 2nd arg).
#
# Picks the 1080p or 4k overlay set to match your video, scales to fit, and
# shows each caption at its voiceover-aligned timecode.
set -euo pipefail

IN="${1:?usage: burn_captions.sh <video> [out]}"
[ -f "$IN" ] || { echo "no such file: $IN" >&2; exit 1; }
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# video dimensions
W=$(ffprobe -v error -select_streams v:0 -show_entries stream=width  -of csv=p=0 "$IN")
H=$(ffprobe -v error -select_streams v:0 -show_entries stream=height -of csv=p=0 "$IN")
SET="1080p"; [ "${H:-0}" -ge 2000 ] && SET="4k"
DIR="$ROOT/docs/demo-overlays/$SET"
echo "video ${W}x${H} → using $SET overlays"

# overlay files (sorted) + matching start/end times (seconds), aligned to the VO
FILES=("$DIR"/[0-9][0-9]_*.png)
STARTS=(0.3 10.2 13.6 19.0 21.4 27.0 32.2 35.2 39.2 41.8 46.0 52.0 54.9 60.4 64.0 69.0)
ENDS=(  7.2 13.4 18.8 21.3 26.8 32.0 35.1 39.1 41.7 45.9 51.8 54.8 60.3 63.9 68.9 74.0)

n=${#FILES[@]}
inputs=(); filt=""; prev="0:v"
for ((i=0; i<n; i++)); do
  inputs+=(-i "${FILES[$i]}")
  idx=$((i+1))
  filt+="[${idx}:v]scale=${W}:${H}[o${idx}];"
  out="t${idx}"; [ $i -eq $((n-1)) ] && out="vout"
  filt+="[${prev}][o${idx}]overlay=0:0:enable='between(t,${STARTS[$i]},${ENDS[$i]})'[${out}];"
  prev="$out"
done
filt="${filt%;}"

base="$(basename "${IN%.*}")"
OUT="${2:-$(dirname "$IN")/${base}-captioned.mp4}"

echo "burning ${n} captions → $OUT"
ffmpeg -y -hide_banner -loglevel error -stats \
  -i "$IN" "${inputs[@]}" \
  -filter_complex "$filt" \
  -map "[vout]" -map 0:a? \
  -c:v libx264 -crf 18 -preset medium -pix_fmt yuv420p \
  -c:a aac -b:a 192k \
  "$OUT"
echo "✓ done → $OUT"
