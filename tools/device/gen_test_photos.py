#!/usr/bin/env python3
"""Generate the P3.8 hardware check's test photos (packet P3.8, docs/DEVICE.md).

Small JPEGs with EXIF (date taken, camera) so the photo screensaver's checks
have real inputs: one landscape photo matching a 16:10 panel, two whose
shapes differ enough to show smart fill's blurred bars, and one that has no
EXIF at all. A large index number is drawn on each photo so the verifier can
see the slide change from across the room.

Run through the repo's uv environment with the two pure-python image
libraries pulled in ephemerally (they are not project dependencies):

    uv run --with 'pillow>=10' --with piexif \\
        python tools/device/gen_test_photos.py --out build/test-photos

Output goes to build/ and is never committed (AGENTS.md). The check script
pushes whatever directory it is given via TABULA_PHOTOS.

Exit code 0 on success; non-zero when a photo cannot be written.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

# (name, size, rgb, exif DateTimeOriginal, Make, Model or None)
Photo = tuple[str, tuple[int, int], tuple[int, int, int], str, str, str | None]

PHOTOS: list[Photo] = [
    (
        "p38-1-landscape.jpg",
        (1920, 1200),
        (38, 70, 112),
        "2026:03:14 09:26:00",
        "Canon",
        "Canon EOS R6",
    ),
    ("p38-2-four-thirds.jpg", (1600, 1200), (96, 64, 128), "2026:06:02 17:45:00", "Nikon", "D750"),
    ("p38-3-portrait.jpg", (1080, 1920), (112, 84, 40), "2026:09:21 08:03:00", "Sony", "A7"),
    ("p38-4-no-exif.jpg", (640, 480), (60, 110, 70), "", "", None),
]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--out",
        default="build/test-photos",
        help="output directory (default: build/test-photos)",
    )
    args = parser.parse_args()

    try:
        import piexif
        from PIL import Image, ImageDraw, ImageFont
    except ImportError:
        print(
            "needs pillow and piexif; run through uv:\n"
            "  uv run --with 'pillow>=10' --with piexif python tools/device/gen_test_photos.py",
            file=sys.stderr,
        )
        return 2

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    for name, size, rgb, date, make, model in PHOTOS:
        image = Image.new("RGB", size, rgb)
        draw = ImageDraw.Draw(image)
        index = name.split("-")[1]
        font = ImageFont.load_default(size=int(size[1] * 0.55))
        draw.text((size[0] / 2, size[1] / 2), index, fill=(245, 245, 245), font=font, anchor="mm")
        exif_bytes = b""
        if model is not None:
            # EXIF's date format is "yyyy:MM:dd HH:mm:ss" (the device parses
            # DateTimeOriginal, F-SSV-12's date taken)
            exif = piexif.dump(
                {
                    "Exif": {
                        piexif.ExifIFD.DateTimeOriginal: date,
                    },
                    "0th": {
                        piexif.ImageIFD.Make: make,
                        piexif.ImageIFD.Model: model,
                        piexif.ImageIFD.DateTime: date,
                    },
                }
            )
            exif_bytes = exif
        image.save(out / name, "jpeg", quality=85, exif=exif_bytes)
        print(f"wrote {out / name} ({size[0]}x{size[1]}, exif={'yes' if model else 'no'})")
    print(f"{len(PHOTOS)} photos in {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
