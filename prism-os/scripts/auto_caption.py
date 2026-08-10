"""Auto-caption every image/video in a directory and rename the file to
match, since titles in this project come straight from filenames
(pms/data.py's _title_from_path) rather than a separate metadata file.

Uses BLIP (Salesforce/blip-image-captioning-base by default) for captioning.
BLIP was trained on general-purpose web/COCO-style images -- caption quality
on out-of-domain content (e.g. explicit imagery) will likely be generic or
off-target rather than genuinely descriptive, so treat this as a starting
point to hand-edit, not a finished labeling pass. Always review the preview
before passing --apply.

Renaming files is a real, hard-to-reverse action on your actual content
directory, so this defaults to a dry run: it prints every planned rename
without touching anything. Pass --apply once you've reviewed the preview and
are ready to commit.

Usage:
    python -m scripts.auto_caption --dir data/images
    python -m scripts.auto_caption --dir data/images --apply
    python -m scripts.auto_caption --dir data/videos --model Salesforce/blip-image-captioning-large
"""

import argparse
import re
import sys
from pathlib import Path

import cv2
import numpy as np
import torch
from PIL import Image

_PROJECT_ROOT = Path(__file__).resolve().parent.parent
if str(_PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(_PROJECT_ROOT))

try:
    from pms.data import VIDEO_EXTENSIONS
    from pms.image_io import IMAGE_EXTENSIONS
    from pms.utils import ProgressLine
except ModuleNotFoundError:
    from data import VIDEO_EXTENSIONS
    from image_io import IMAGE_EXTENSIONS
    from utils import ProgressLine


def slugify(caption: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "_", caption.lower()).strip("_")
    return slug or "untitled"


def grab_video_frame(path: Path) -> Image.Image:
    """A single representative (middle) frame at native resolution -- not
    pms.video_io.decode_and_sample_frames, which resizes down to whatever
    tiny training resolution is in play; captioning wants full detail."""
    cap = cv2.VideoCapture(str(path))
    if not cap.isOpened():
        raise IOError(f"Could not open video: {path}")
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    if total > 0:
        cap.set(cv2.CAP_PROP_POS_FRAMES, total // 2)
    ok, frame = cap.read()
    cap.release()
    if not ok:
        raise IOError(f"Could not read a frame from: {path}")
    return Image.fromarray(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))


def load_frame(path: Path) -> Image.Image:
    if path.suffix.lower() in IMAGE_EXTENSIONS:
        return Image.open(path).convert("RGB")
    return grab_video_frame(path)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dir", required=True, help="directory of images/videos to caption and rename")
    parser.add_argument("--apply", action="store_true",
                         help="actually rename files -- omit for a dry-run preview (default)")
    parser.add_argument("--model", default="Salesforce/blip-image-captioning-base")
    parser.add_argument("--max-new-tokens", type=int, default=30)
    parser.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    args = parser.parse_args()

    data_dir = Path(args.dir)
    if not data_dir.is_dir():
        parser.error(f"no such directory: {data_dir}")

    extensions = IMAGE_EXTENSIONS | VIDEO_EXTENSIONS
    paths = sorted(p for p in data_dir.iterdir() if p.suffix.lower() in extensions)
    if not paths:
        parser.error(f"no image/video files found under: {data_dir}")

    from transformers import BlipForConditionalGeneration, BlipProcessor

    print(f"Loading {args.model}...")
    processor = BlipProcessor.from_pretrained(args.model)
    dtype = torch.float16 if args.device == "cuda" else torch.float32
    model = BlipForConditionalGeneration.from_pretrained(args.model).to(args.device, dtype=dtype).eval()

    print("NOTE: this model was trained on general-purpose captioning data, not on content like "
          "yours -- expect generic/off-target captions on anything out of that domain. Review the "
          "preview below carefully before using --apply.\n")

    print(f"Captioning {len(paths)} file(s) under {data_dir}...")
    progress = ProgressLine(len(paths), prefix="captioning ")
    planned: list[tuple[Path, Path]] = []
    used_stems = {p.stem.lower() for p in data_dir.iterdir() if p not in paths}
    for i, path in enumerate(paths):
        frame = load_frame(path)
        inputs = processor(images=frame, return_tensors="pt").to(args.device, dtype)
        with torch.no_grad():
            out_ids = model.generate(**inputs, max_new_tokens=args.max_new_tokens)
        caption = processor.decode(out_ids[0], skip_special_tokens=True)

        slug = slugify(caption)
        candidate = slug
        n = 2
        while candidate in used_stems:
            candidate = f"{slug}-{n}"
            n += 1
        used_stems.add(candidate)

        new_path = path.with_name(f"{candidate}{path.suffix.lower()}")
        planned.append((path, new_path))
        progress.update(i + 1)
    progress.done()

    print()
    for old, new in planned:
        marker = "  (unchanged)" if old == new else ""
        print(f"  {old.name}\n    -> {new.name}{marker}")

    if not args.apply:
        print(f"\nDry run: {len(planned)} file(s) would be renamed. Re-run with --apply to do it.")
        return

    print()
    renamed, skipped = 0, 0
    for old, new in planned:
        if old == new:
            continue
        if new.exists():
            print(f"[skip] {new.name} already exists, not overwriting")
            skipped += 1
            continue
        old.rename(new)
        renamed += 1
    print(f"Renamed {renamed} file(s), skipped {skipped}.")


if __name__ == "__main__":
    main()
