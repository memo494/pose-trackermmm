#!/usr/bin/env python3
"""Export yolo26n-pose to LiteRT (.tflite) at 320 and 416, and generate golden outputs.

Run by .github/workflows/export-model.yml (workflow_dispatch) on ubuntu x86_64. Normal app builds
never run Python: the committed model/*.tflite + model/model_info.json + golden fixtures are the
source of truth, and the Android build fails if a model's sha256 does not match (ARCHITECTURE §8).

Outputs, under model/:
  yolo26n-pose_320.tflite, yolo26n-pose_416.tflite   FP32 (GPU computes in FP16), nms=False
  model_info.json       I/O shapes & dtypes, ultralytics version, sha256 per file, fixture info
  golden/<size>.npz     raw predict() output on the fixture, for the detector golden decode test
  fixture/people.jpg    the exact image used for the goldens (ultralytics ASSETS, AGPL-3.0)
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from pathlib import Path

import numpy as np
import ultralytics
from ai_edge_litert.interpreter import Interpreter
from ultralytics import YOLO
from ultralytics.utils import ASSETS

SIZES = (320, 416)
MODEL = "yolo26n-pose"
ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "model"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "--fixture",
        default=str(ASSETS / "bus.jpg"),
        help="image with 2+ people used to generate golden outputs",
    )
    args = ap.parse_args()

    (OUT / "golden").mkdir(parents=True, exist_ok=True)
    (OUT / "fixture").mkdir(parents=True, exist_ok=True)

    fixture_dst = OUT / "fixture" / "people.jpg"
    shutil.copyfile(args.fixture, fixture_dst)

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

    for size in SIZES:
        exported = Path(YOLO(f"{MODEL}.pt").export(format="litert", nms=False, imgsz=size))
        dst = OUT / f"{MODEL}_{size}.tflite"
        shutil.copyfile(exported, dst)

        result = YOLO(str(dst)).predict(str(fixture_dst), imgsz=size, verbose=False)[0]
        boxes = result.boxes.data.cpu().numpy() if result.boxes is not None else np.empty((0,))
        kpts = result.keypoints.data.cpu().numpy() if result.keypoints is not None else np.empty((0,))
        np.savez(OUT / "golden" / f"{size}.npz", boxes=boxes, keypoints=kpts)

        interp = Interpreter(model_path=str(dst))
        interp.allocate_tensors()
        info["sizes"][str(size)] = {
            "file": dst.name,
            "sha256": sha256(dst),
            "input": [
                {"shape": [int(x) for x in d["shape"]], "dtype": str(d["dtype"])}
                for d in interp.get_input_details()
            ],
            "output": [
                {"shape": [int(x) for x in d["shape"]], "dtype": str(d["dtype"])}
                for d in interp.get_output_details()
            ],
            "golden": f"golden/{size}.npz",
        }

    (OUT / "model_info.json").write_text(json.dumps(info, indent=2) + "\n")
    print("Wrote", OUT / "model_info.json")


if __name__ == "__main__":
    main()
