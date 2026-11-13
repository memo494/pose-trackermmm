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
exactly one float32 input [1,3,S,S] and one float32 output [1,300,57] and predict() on the Ultralytics
sample image finds at least two people with a score of 0.5 or more.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
from importlib import metadata
from pathlib import Path

# ultralytics, torch, numpy and ai_edge_litert are imported inside the functions that need them, so
# `--help` and the argument checks work (and fail early) without the export toolchain installed.

DEFAULT_SIZES = "320,416"
SIZE_STEP = 32  # YOLO strides: the input side must be a multiple of this
MIN_PEOPLE = 2
MIN_SCORE = 0.5
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


def parse_sizes(text: str) -> tuple[int, ...]:
    """'320,416' -> (320, 416); positive multiples of 32, no duplicates, else ArgumentTypeError."""
    sizes = []
    for part in text.split(","):
        try:
            size = int(part.strip())
        except ValueError:
            raise argparse.ArgumentTypeError(f"not an integer: {part!r} (in --sizes {text!r})") from None
        if size <= 0 or size % SIZE_STEP != 0:
            raise argparse.ArgumentTypeError(f"size {size} must be a positive multiple of {SIZE_STEP}")
        if size in sizes:
            raise argparse.ArgumentTypeError(f"duplicate size {size}")
        sizes.append(size)
    return tuple(sizes)


def export_with_goldens(sizes: tuple[int, ...], fixture: str | None) -> None:
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

    for size in sizes:
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


def check_io(inputs: list[dict], outputs: list[dict], size: int) -> None:
    """Exit unless there is exactly one [1,3,S,S] float32 input and one [1,300,57] float32 output."""
    want_in = {"shape": [1, 3, size, size], "dtype": "float32"}
    want_out = {"shape": [1, OUT_ROWS, OUT_COLS], "dtype": "float32"}
    for kind, got, want in (("input", inputs, want_in), ("output", outputs, want_out)):
        if len(got) != 1:
            raise SystemExit(f"size {size}: expected exactly one {kind} tensor, found {len(got)}: {got}")
        if got[0]["shape"] != want["shape"] or got[0]["dtype"] != want["dtype"]:
            raise SystemExit(
                f"size {size}: {kind} is {got[0]['shape']} {got[0]['dtype']}, "
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


def export_download(sizes: tuple[int, ...], out_dir: Path) -> None:
    """--out path: export, check and write DIR/<model>_<size>.tflite + .json; never touches model/."""
    try:
        from ai_edge_litert.interpreter import Interpreter
    except ImportError as e:
        raise SystemExit("ai-edge-litert is not installed; install tools/requirements.lock") from e
    from ultralytics import YOLO
    from ultralytics.utils import ASSETS

    out_dir.mkdir(parents=True, exist_ok=True)
    for size in sizes:
        final_tflite = out_dir / f"{MODEL}_{size}.tflite"
        final_json = out_dir / f"{MODEL}_{size}.json"
        # Nothing of an earlier run may survive for a size that fails now.
        final_tflite.unlink(missing_ok=True)
        final_json.unlink(missing_ok=True)
        # Everything is built in a work dir and renamed at the end, so a failure leaves nothing.
        work = out_dir / f".work_{size}"
        shutil.rmtree(work, ignore_errors=True)
        work.mkdir()
        try:
            exported = Path(YOLO(f"{MODEL}.pt").export(format="litert", nms=False, imgsz=size))
            tflite = work / final_tflite.name
            shutil.copyfile(exported, tflite)

            interp = Interpreter(model_path=str(tflite))
            interp.allocate_tensors()
            inputs = [plain_tensor(d) for d in interp.get_input_details()]
            outputs = [plain_tensor(d) for d in interp.get_output_details()]
            check_io(inputs, outputs, size)

            result = YOLO(str(tflite)).predict(str(ASSETS / "bus.jpg"), imgsz=size, verbose=False)[0]
            scores = [] if result.boxes is None else [float(c) for c in result.boxes.conf.cpu().numpy()]
            people = sorted((s for s in scores if s >= MIN_SCORE), reverse=True)
            if len(people) < MIN_PEOPLE:
                raise SystemExit(
                    f"size {size}: predict() on bus.jpg found {len(people)} people with score >= "
                    f"{MIN_SCORE}, expected at least {MIN_PEOPLE} (all scores: "
                    f"{[round(s, 3) for s in scores]})"
                )

            meta = {
                "model": MODEL,
                "size": size,
                "file": final_tflite.name,
                "sha256": sha256(tflite),
                "bytes": tflite.stat().st_size,
                "input": inputs,
                "output": outputs,
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
        help=f"comma-separated input sizes, positive multiples of {SIZE_STEP}, no duplicates "
        f"(default {DEFAULT_SIZES})",
    )
    ap.add_argument(
        "--out",
        type=Path,
        default=None,
        help="download-only export into this directory; never writes under model/",
    )
    args = ap.parse_args()

    if args.out is not None:
        export_download(args.sizes, args.out)
    else:
        export_with_goldens(args.sizes, args.fixture)


if __name__ == "__main__":
    main()
