#!/usr/bin/env python3
"""Export yolo26n-pose to LiteRT (.tflite) at chosen sizes (default 320 and 416), plus golden outputs.

Run by .github/workflows/export-model.yml (workflow_dispatch) on ubuntu x86_64. Normal app builds
never run Python: the committed model/*.tflite + model/model_info.json + golden fixtures are the
source of truth, and the Android build fails if a model's sha256 does not match (ARCHITECTURE §8).

Outputs, under model/:
  yolo26n-pose_320.tflite, yolo26n-pose_416.tflite   FP32 (GPU computes in FP16), nms=False
  model_info.json       I/O names, shapes, dtypes & quantization, ultralytics version, sha256 per file, fixture info
  golden/<size>.npz     raw predict() output on the fixture, for the detector golden decode test
  fixture/people.jpg    the exact image used for the goldens (ultralytics ASSETS, AGPL-3.0)

With --out DIR the script is a download-only export (M0c D5): it never writes under model/ and no
golden or fixture files are made. For each size it writes DIR/yolo26n-pose_<size>.tflite and
DIR/yolo26n-pose_<size>.json, and exits non-zero (leaving nothing for that size) unless the model has
exactly one float32 input [1,3,H,W] and one float32 output [1,300,57] and predict() on an Ultralytics
sample image finds at least the minimum number of people (PEOPLE_IMAGE_MIN) with a score of 0.5 or more.

Sizes are written WIDTHxHEIGHT wherever a person reads them: --sizes 832x384 gives
yolo26n-pose_832x384.tflite with input [1,3,384,832] (Ultralytics takes imgsz=(H, W)). A square size
stays one number (640 -> yolo26n-pose_640.tflite); 640x640 is normalised to 640. WxH needs --out.
`--self-test` checks the parsing and naming without the export toolchain.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
from importlib import metadata
from pathlib import Path
from typing import NamedTuple

# ultralytics, torch, numpy and ai_edge_litert are imported inside the functions that need them, so
# `--help` and the argument checks work (and fail early) without the export toolchain installed.

DEFAULT_SIZES = "320,416"
SIZE_STEP = 32  # YOLO strides: the input side must be a multiple of this
MIN_SCORE = 0.5
# Download path people check: the Ultralytics sample image used for a size and the least number of people
# with score >= MIN_SCORE that predict() must find in it. bus.jpg (portrait) keeps the rule it always had;
# zidane.jpg (1280x720, two people) is the landscape image for W > H.
PEOPLE_IMAGE_MIN = {"bus.jpg": 2, "zidane.jpg": 2}
PORTRAIT_IMAGE = "bus.jpg"
LANDSCAPE_IMAGE = "zidane.jpg"
OUT_ROWS = 300
OUT_COLS = 57
PACKAGES = ("ultralytics", "torch", "litert-torch", "ai-edge-litert")
MODEL = "yolo26n-pose"
ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "model"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def tensor_info(d: dict) -> dict:
    """Shape, dtype, name and (when quantized) scale/zero_point of one interpreter tensor."""
    entry = {"name": d["name"], "shape": [int(x) for x in d["shape"]], "dtype": str(d["dtype"])}
    scale, zero_point = d.get("quantization", (0.0, 0))
    if scale != 0:  # (0.0, 0) means "not quantized" in the LiteRT/TFLite interpreter
        entry["quantization"] = {"scale": float(scale), "zero_point": int(zero_point)}
    return entry


class Size(NamedTuple):
    """One model input size; a person reads it WIDTHxHEIGHT, the tensor is [1, 3, H, W]."""

    width: int
    height: int

    @property
    def square(self) -> bool:
        return self.width == self.height

    @property
    def label(self) -> str:
        return str(self.width) if self.square else f"{self.width}x{self.height}"

    @property
    def file_stem(self) -> str:
        return f"{MODEL}_{self.label}"

    @property
    def imgsz(self) -> tuple[int, int]:
        """Ultralytics order: (height, width)."""
        return (self.height, self.width)

    @property
    def input_shape(self) -> list[int]:
        return [1, 3, self.height, self.width]

    @property
    def people_image(self) -> str:
        return LANDSCAPE_IMAGE if self.width > self.height else PORTRAIT_IMAGE


_SIZE_ENTRY = re.compile(r"([0-9]+)(?:x([0-9]+))?")


def parse_size(part: str, text: str) -> Size:
    """'640' -> 640x640, '832x384' -> 832x384; ASCII digits only, each side a positive multiple of 32."""
    m = _SIZE_ENTRY.fullmatch(part)
    if m is None:
        raise argparse.ArgumentTypeError(f"not a size: {part!r} (use S or WIDTHxHEIGHT, in --sizes {text!r})")
    width = int(m.group(1))
    height = width if m.group(2) is None else int(m.group(2))
    for side in (width, height):
        if side <= 0 or side % SIZE_STEP != 0:
            raise argparse.ArgumentTypeError(f"size {part} needs sides that are positive multiples of {SIZE_STEP}")
    return Size(width, height)


def parse_sizes(text: str) -> tuple[Size, ...]:
    """'640,832x384' -> (640x640, 832x384); no spaces, no empty entries, no duplicates (after normalising)."""
    sizes: list[Size] = []
    for part in text.split(","):
        size = parse_size(part, text)
        if size in sizes:
            raise argparse.ArgumentTypeError(f"duplicate size {size.label}")
        sizes.append(size)
    return tuple(sizes)


def check_sizes_for_mode(sizes: tuple[Size, ...], out_dir: Path | None) -> None:
    """A rectangular size is valid only with --out (the download path); the 320/416 path stays square."""
    if out_dir is None:
        rect = [s.label for s in sizes if not s.square]
        if rect:
            raise argparse.ArgumentTypeError(
                f"WIDTHxHEIGHT sizes ({', '.join(rect)}) need --out (download-only export)"
            )


def export_with_goldens(sizes: tuple[Size, ...], fixture: str | None) -> None:
    """Default path: write model/*.tflite, golden/<size>.npz, fixture and model_info.json."""
    import numpy as np
    import ultralytics
    from ultralytics import YOLO
    from ultralytics.utils import ASSETS

    if fixture is None:
        fixture = str(ASSETS / "bus.jpg")

    (OUT / "golden").mkdir(parents=True, exist_ok=True)
    (OUT / "fixture").mkdir(parents=True, exist_ok=True)

    fixture_dst = OUT / "fixture" / "people.jpg"
    shutil.copyfile(fixture, fixture_dst)

    info: dict = {
        "model": MODEL,
        "ultralytics_version": ultralytics.__version__,
        "export": {"format": "litert", "nms": False, "dtype": "float32"},
        "fixture": {
            "file": "fixture/people.jpg",
            "source": "ultralytics ASSETS (AGPL-3.0)",
            "sha256": sha256(fixture_dst),
        },
        "sizes": {},
    }

    for entry in sizes:
        size = entry.width  # check_sizes_for_mode() guarantees a square size on this path
        exported = Path(YOLO(f"{MODEL}.pt").export(format="litert", nms=False, imgsz=size))

        dst = OUT / f"{MODEL}_{size}.tflite"
        shutil.copyfile(exported, dst)

        result = YOLO(str(dst)).predict(str(fixture_dst), imgsz=size, verbose=False)[0]
        boxes = result.boxes.data.cpu().numpy() if result.boxes is not None else np.empty((0,))
        kpts = result.keypoints.data.cpu().numpy() if result.keypoints is not None else np.empty((0,))
        np.savez(OUT / "golden" / f"{size}.npz", boxes=boxes, keypoints=kpts)

        # Imported here, not at the top: ultralytics may auto-install its LiteRT toolchain
        # (including ai-edge-litert) during the export/predict above.
        try:
            from ai_edge_litert.interpreter import Interpreter
        except ImportError as e:
            raise SystemExit(
                "ai-edge-litert is not installed; it is needed to read the .tflite tensor "
                "details (add it to tools/requirements.txt)"
            ) from e

        interp = Interpreter(model_path=str(dst))
        interp.allocate_tensors()
        info["sizes"][str(size)] = {
            "file": dst.name,
            "sha256": sha256(dst),
            "input": [tensor_info(d) for d in interp.get_input_details()],
            "output": [tensor_info(d) for d in interp.get_output_details()],
            "golden": f"golden/{size}.npz",
        }

    (OUT / "model_info.json").write_text(json.dumps(info, indent=2) + "\n")
    print("Wrote", OUT / "model_info.json")


def plain_tensor(d: dict) -> dict:
    """Name, shape and plain dtype name (e.g. 'float32') of one interpreter tensor."""
    import numpy as np

    return {"name": d["name"], "shape": [int(x) for x in d["shape"]], "dtype": np.dtype(d["dtype"]).name}


def check_io(inputs: list[dict], outputs: list[dict], size: Size) -> None:
    """Exit unless there is exactly one [1,3,H,W] float32 input and one [1,300,57] float32 output."""
    want_in = {"shape": size.input_shape, "dtype": "float32"}
    want_out = {"shape": [1, OUT_ROWS, OUT_COLS], "dtype": "float32"}
    for kind, got, want in (("input", inputs, want_in), ("output", outputs, want_out)):
        if len(got) != 1:
            raise SystemExit(f"size {size.label}: expected exactly one {kind} tensor, found {len(got)}: {got}")
        if got[0]["shape"] != want["shape"] or got[0]["dtype"] != want["dtype"]:
            raise SystemExit(
                f"size {size.label}: {kind} is {got[0]['shape']} {got[0]['dtype']}, "
                f"expected {want['shape']} {want['dtype']}"
            )


def package_versions() -> dict:
    out = {}
    for name in PACKAGES:
        try:
            out[name] = metadata.version(name)
        except metadata.PackageNotFoundError:
            out[name] = None
    return out


def export_download(sizes: tuple[Size, ...], out_dir: Path) -> None:
    """--out path: export, check and write DIR/<model>_<size>.tflite + .json; never touches model/."""
    try:
        from ai_edge_litert.interpreter import Interpreter
    except ImportError as e:
        raise SystemExit("ai-edge-litert is not installed; install tools/requirements.lock") from e
    from ultralytics import YOLO
    from ultralytics.utils import ASSETS

    out_dir.mkdir(parents=True, exist_ok=True)
    for size in sizes:
        final_tflite = out_dir / f"{size.file_stem}.tflite"
        final_json = out_dir / f"{size.file_stem}.json"
        # Nothing of an earlier run may survive for a size that fails now.
        final_tflite.unlink(missing_ok=True)
        final_json.unlink(missing_ok=True)
        # Everything is built in a work dir and renamed at the end, so a failure leaves nothing.
        work = out_dir / f".work_{size.label}"
        shutil.rmtree(work, ignore_errors=True)
        work.mkdir()
        # A square size passes the single number, as always; a rectangular one passes (H, W).
        imgsz = size.width if size.square else size.imgsz
        image = size.people_image
        min_people = PEOPLE_IMAGE_MIN[image]
        try:
            exported = Path(YOLO(f"{MODEL}.pt").export(format="litert", nms=False, imgsz=imgsz))
            tflite = work / final_tflite.name
            shutil.copyfile(exported, tflite)

            interp = Interpreter(model_path=str(tflite))
            interp.allocate_tensors()
            inputs = [plain_tensor(d) for d in interp.get_input_details()]
            outputs = [plain_tensor(d) for d in interp.get_output_details()]
            check_io(inputs, outputs, size)

            result = YOLO(str(tflite)).predict(str(ASSETS / image), imgsz=imgsz, verbose=False)[0]
            scores = [] if result.boxes is None else [float(c) for c in result.boxes.conf.cpu().numpy()]
            people = sorted((s for s in scores if s >= MIN_SCORE), reverse=True)
            if len(people) < min_people:
                raise SystemExit(
                    f"size {size.label}: predict() on {image} found {len(people)} people with score >= "
                    f"{MIN_SCORE}, expected at least {min_people} (all scores: "
                    f"{[round(s, 3) for s in scores]})"
                )

            meta = {"model": MODEL}
            if size.square:
                meta["size"] = size.width  # square files keep their old key; a rectangular size has none
            meta |= {
                "width": size.width,
                "height": size.height,
                "file": final_tflite.name,
                "sha256": sha256(tflite),
                "bytes": tflite.stat().st_size,
                "input": inputs,
                "output": outputs,
                "people_image": image,
                "people_found": len(people),
                "scores": [round(s, 3) for s in people],
                "versions": package_versions(),
            }
            (work / final_json.name).write_text(json.dumps(meta, indent=2) + "\n")
            os.replace(tflite, final_tflite)
            os.replace(work / final_json.name, final_json)
        except BaseException:
            final_tflite.unlink(missing_ok=True)
            final_json.unlink(missing_ok=True)
            raise
        finally:
            shutil.rmtree(work, ignore_errors=True)
        print(
            f"{final_tflite.name}: {meta['bytes']} bytes sha256={meta['sha256']} "
            f"in={inputs[0]['shape']} out={outputs[0]['shape']} people={meta['people_found']} "
            f"scores={meta['scores']}"
        )


def self_test() -> None:
    """One line per check, exit 1 on the first failure. Needs none of the heavy imports."""

    def ok(name: str, cond: bool) -> None:
        if not cond:
            print(f"FAIL {name}")
            raise SystemExit(1)
        print(f"ok   {name}")

    def parsed(text: str) -> tuple:
        return tuple((s.width, s.height) for s in parse_sizes(text))

    def rejected(text: str) -> bool:
        try:
            parse_sizes(text)
        except argparse.ArgumentTypeError:
            return True
        return False

    ok("parse square list", parsed("320,416") == ((320, 320), (416, 416)))
    ok("parse WxH", parsed("832x384") == ((832, 384),))
    ok("parse portrait WxH", parsed("384x832") == ((384, 832),))
    ok("parse mixed list", parsed("640,640x288,960x448") == ((640, 640), (640, 288), (960, 448)))
    ok("parse the workflow input", parsed("640x288,832x384,960x448") == ((640, 288), (832, 384), (960, 448)))
    ok("640x640 is normalised to 640", tuple(x.label for x in parse_sizes("640x640")) == ("640",))
    ok("640x640 and 640 are duplicates", rejected("640x640,640") and rejected("640,640x640"))
    for bad in ("", "0", "-32", "100", "640x", "x640", "640x288x3", " 640", "640 ", "640, 832", "640 ,832",
                "640,,832", "640,", ",640", "0x640", "640x0", "-32x640", "640x-32", "640x100", "100x640",
                "640x 288", "640 x288", "640X288", "6.4e2", "abc", "٦٤٠"):
        ok(f"reject {bad!r}", rejected(bad))
    ok("reject duplicate square", rejected("640,640"))
    ok("reject duplicate WxH", rejected("832x384,832x384"))
    ok("same sides in other order are not duplicates", len(parse_sizes("832x384,384x832")) == 2)

    sq, rect = parse_sizes("640")[0], parse_sizes("832x384")[0]
    ok("square label is one number", sq.label == "640")
    ok("rect label is WxH", rect.label == "832x384")
    ok("square file names unchanged", sq.file_stem == "yolo26n-pose_640")
    ok("rect file names", rect.file_stem == "yolo26n-pose_832x384")
    ok("square imgsz is (H, W)", sq.imgsz == (640, 640))
    ok("rect imgsz is (H, W)", rect.imgsz == (384, 832))
    ok("portrait imgsz is (H, W)", parse_sizes("384x832")[0].imgsz == (832, 384))
    ok("input shape is [1,3,H,W]", rect.input_shape == [1, 3, 384, 832])
    ok("landscape people image", rect.people_image == "zidane.jpg")
    ok("portrait people image", parse_sizes("384x832")[0].people_image == "bus.jpg")
    ok("square people image", sq.people_image == "bus.jpg")
    ok("people table covers every image", set(PEOPLE_IMAGE_MIN) == {"bus.jpg", "zidane.jpg"})
    ok("people rule stays at 2 for bus.jpg", PEOPLE_IMAGE_MIN["bus.jpg"] == 2)

    try:
        check_sizes_for_mode(parse_sizes("832x384"), None)
        refused = False
    except argparse.ArgumentTypeError:
        refused = True
    ok("WxH needs --out", refused)
    try:
        check_sizes_for_mode(parse_sizes("320,416"), None)
        check_sizes_for_mode(parse_sizes("640x640,832x384"), Path("d"))
        allowed = True
    except argparse.ArgumentTypeError:
        allowed = False
    ok("squares need no --out, WxH is fine with --out", allowed)
    ok("default sizes are the square 320,416", parsed(DEFAULT_SIZES) == ((320, 320), (416, 416)))
    print("self-test passed")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument(
        "--fixture",
        default=None,
        help="image with 2+ people used to generate golden outputs (default: ultralytics bus.jpg); "
        "ignored with --out",
    )
    ap.add_argument(
        "--sizes",
        type=parse_sizes,
        default=parse_sizes(DEFAULT_SIZES),
        help=f"comma-separated input sizes, no spaces: S (square, e.g. 640) or WIDTHxHEIGHT (e.g. 832x384, "
        f"only with --out; 640x640 means 640); each side a positive multiple of {SIZE_STEP}, no duplicates "
        f"(default {DEFAULT_SIZES})",
    )
    ap.add_argument(
        "--out",
        type=Path,
        default=None,
        help="download-only export into this directory; never writes under model/",
    )
    ap.add_argument(
        "--self-test",
        action="store_true",
        help="run the parsing/naming checks (no heavy imports) and exit",
    )
    args = ap.parse_args()

    if args.self_test:
        self_test()
        return
    try:
        check_sizes_for_mode(args.sizes, args.out)
    except argparse.ArgumentTypeError as e:
        ap.error(str(e))

    if args.out is not None:
        export_download(args.sizes, args.out)
    else:
        export_with_goldens(args.sizes, args.fixture)


if __name__ == "__main__":
    main()
