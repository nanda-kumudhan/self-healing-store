#!/usr/bin/env python3
"""Record real process-cluster output as an asciicast and an MP4 terminal replay.

Requires ffmpeg and Pillow. The replay states its speed and environment. It is
never described as footage of Docker or a GUI; the commands run local Java apps.
"""
import argparse
import datetime
import json
import os
from pathlib import Path
import subprocess
import sys
import textwrap
import time

ROOT = Path(__file__).resolve().parents[1]


def render(events, elapsed, target, speed):
    from PIL import Image, ImageDraw, ImageFont
    font_path = "/usr/share/fonts/TTF/DejaVuSansMono.ttf"
    if not Path(font_path).exists():
        font_path = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"
    if not Path(font_path).exists():
        font_path = "/usr/share/fonts/liberation/LiberationMono-Regular.ttf"
    font = ImageFont.truetype(font_path, 18)
    heading = ImageFont.truetype(font_path, 22)
    fps, width, height = 2, 1280, 720
    command = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "rawvideo", "-pixel_format", "rgb24",
               "-video_size", f"{width}x{height}", "-framerate", str(fps), "-i", "-", "-an", "-c:v", "libx264",
               "-preset", "fast", "-crf", "24", "-pix_fmt", "yuv420p", str(target)]
    process = subprocess.Popen(command, stdin=subprocess.PIPE)
    try:
        frames = int((elapsed / speed + 3) * fps) + 1
        for frame in range(frames):
            real_time = min(elapsed, frame * speed / fps)
            canvas = Image.new("RGB", (width, height), "#101820")
            draw = ImageDraw.Draw(canvas)
            draw.text((28, 20), "Self-healing encrypted store | actual process-cluster run", font=heading, fill="#edf2f4")
            draw.text((28, 56), f"Java 21 | mTLS | temporary disks + H2 | {speed:g}x playback | elapsed {real_time:.1f}s", font=font, fill="#a4b8c4")
            lines = []
            for timestamp, message in events:
                if timestamp <= real_time:
                    for line in message.rstrip().splitlines():
                        lines.extend(textwrap.wrap(line, width=106, replace_whitespace=False) or [""])
            for i, line in enumerate(lines[-23:]):
                color = "#8ee3a2" if line.startswith("PASS") else "#ffbf69" if line.startswith("STOP") else "#dce6eb"
                draw.text((28, 106 + i * 24), line, font=font, fill=color)
            draw.text((28, 680), "Process failures are demonstrated here. Docker/Toxiproxy validation is separate.", font=font, fill="#a4b8c4")
            process.stdin.write(canvas.tobytes())
    finally:
        process.stdin.close()
    if process.wait() != 0:
        raise RuntimeError("ffmpeg failed")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--benchmark-mib", type=int, nargs="*", default=[])
    parser.add_argument("--repeats", type=int, default=2)
    parser.add_argument("--speed", type=float, default=8)
    parser.add_argument("--replay", type=Path, help="Render an existing actual recording without rerunning tests")
    args = parser.parse_args()
    if args.speed <= 0:
        parser.error("Playback speed must be positive")
    output = ROOT / "measurements"
    output.mkdir(exist_ok=True)
    recording = args.replay or output / "demo.cast"
    events = []
    if args.replay:
        records = [json.loads(line) for line in recording.read_text().splitlines()]
        events = [(row[0], row[2]) for row in records[1:]]
        elapsed = events[-1][0]
    else:
        started = time.monotonic()
        command = [sys.executable, "-u", str(ROOT / "scripts/e2e.py"), "--measure", "--repeats", str(args.repeats)]
        if args.benchmark_mib:
            command += ["--benchmark-mib", *map(str, args.benchmark_mib)]
        with recording.open("w") as destination:
            destination.write(json.dumps({"version": 2, "width": 110, "height": 28, "timestamp": int(time.time()),
                                           "title": "Actual Java mTLS process-cluster test; H2; no Docker"}) + "\n")
            with subprocess.Popen(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1) as process:
                for line in process.stdout:
                    timestamp = time.monotonic() - started
                    events.append((timestamp, line))
                    destination.write(json.dumps([timestamp, "o", line]) + "\n")
                    destination.flush()
                    print(line, end="", flush=True)
                status = process.wait()
            elapsed = time.monotonic() - started
            line = f"Recorded command exit status: {status}\n"
            destination.write(json.dumps([elapsed, "o", line]) + "\n")
            events.append((elapsed, line))
        if status:
            raise SystemExit(status)
    render(events, elapsed, output / "demo.mp4", args.speed)
    print(f"Actual recording: {recording}\nVideo replay: {output / 'demo.mp4'}")


if __name__ == "__main__":
    main()
