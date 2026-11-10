#!/usr/bin/env python3
"""Export yolo26n-pose to LiteRT (.tflite) at 320 and 416 and write verified golden outputs.

Run by .github/workflows/export-model.yml (manual) on ubuntu x86_64. Normal app builds never run
Python: the committed model/ files are the source of truth, and the Android build fails if a
model's sha256 does not match model_info.json (ARCHITECTURE §8).

Outputs, under model/:
  yolo26n-pose_320.tflite, yolo26n-pose_416.tflite   float32, end-to-end head (nms=False)
  model_info.json              versions, export settings, I/O tensors, sha256 of every file
  golden/<size>_raw_output.f32 the raw (1, 300, 57) float32 output on the fixture, little-endian;
                               the JVM decode test (M1) feeds it to the decoder
  golden/<size>_golden.json    the same detections twice: straight from the raw rows (model
                               pixels) and from ultralytics predict() (original-image pixels)
  fixture/people.jpg           public-domain NASA photo used for the goldens (downloaded once)

Before writing anything the script runs the model itself with the tflite interpreter, decodes the
raw rows with its own helpers and compares them with predict(). That check proves the column
order and the pixel units; if it fails the export fails (fail closed).

`--self-test` checks the pure helpers with numpy alone (no ultralytics, torch or OpenCV).
"""
from __future__ import annotations

import argparse
import contextlib
import hashlib
import importlib.metadata
import json
import os
import shutil
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path

import numpy as np

SIZES = (320, 416)  # model input sizes the app ships (ARCHITECTURE §8)
MODEL = "yolo26n-pose"  # ultralytics weights name
NUM_KEYPOINTS = 17  # COCO keypoints
ROW = 6 + 3 * NUM_KEYPOINTS  # x1,y1,x2,y2,score,cls + 17 x (x,y,v) = 57
MAX_DET = 300  # rows of the end-to-end head (ultralytics default max_det)
CONF = 0.25  # ultralytics predict() default confidence threshold
PAD_VALUE = 114  # LetterBox padding value used by ultralytics
TOLERANCE_PX = 0.05  # max |ours - predict()| in image pixels (float32 rounding only)
ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "model"
FIXTURE = OUT / "fixture" / "people.jpg"
FIXTURE_URL = "https://images-assets.nasa.gov/image/8903695/8903695~medium.jpg"  # NASA image library
FIXTURE_SOURCE = "NASA Image and Video Library, NASA ID 8903695: official Apollo 11 crew portrait (1969)"
FIXTURE_LICENSE = "Public domain (NASA media is not copyrighted)"
ROW_LAYOUT = "x1,y1,x2,y2,score,cls,17x(x,y,v)"
COORDINATES = "pixels of the letterboxed model input (0..imgsz)"
JPEG_MAGIC = b"\xff\xd8\xff"
X_COLS = [0, 2] + [6 + 3 * k for k in range(NUM_KEYPOINTS)]  # box x1,x2 and keypoint x
Y_COLS = [1, 3] + [7 + 3 * k for k in range(NUM_KEYPOINTS)]  # box y1,y2 and keypoint y


# --- pure helpers (numpy only) -------------------------------------------------------------------
def letterbox_params(h0: int, w0: int, size: int) -> dict:
    """Scale, padding and gains ultralytics uses to map an h0 x w0 image into a size x size input."""
    r = min(size / h0, size / w0)
    new_w, new_h = max(round(w0 * r), 1), max(round(h0 * r), 1)
    return {
        "new_w": new_w,
        "new_h": new_h,
        "pad_x": round((size - new_w) / 2 - 0.1),
        "pad_y": round((size - new_h) / 2 - 0.1),
        "gain_x": new_w / w0,
        "gain_y": new_h / h0,
    }


def kept_rows(raw: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Indices and rows with score > CONF (strict, like predict()), in row order."""
    rows = np.asarray(raw).reshape(-1, ROW)
    idx = np.nonzero(rows[:, 4] > CONF)[0]
    return idx, rows[idx]


def rows_to_image_space(rows: np.ndarray, lb: dict, h0: int, w0: int) -> np.ndarray:
    """Copy of the rows with x/y mapped from model pixels to image pixels and clipped to the image."""
    out = np.array(rows, dtype=np.float32, copy=True).reshape(-1, ROW)
    out[:, X_COLS] = np.clip((out[:, X_COLS] - lb["pad_x"]) / lb["gain_x"], 0, w0)
    out[:, Y_COLS] = np.clip((out[:, Y_COLS] - lb["pad_y"]) / lb["gain_y"], 0, h0)
    return out


def sha256(path: Path) -> str:
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


# --- self-test -----------------------------------------------------------------------------------
def _check(cond: bool, msg: str) -> None:
    if not cond:
        raise SystemExit(f"self-test FAILED: {msg}")


def self_test() -> None:
    lb = letterbox_params(987, 1280, 320)
    _check((lb["new_w"], lb["new_h"], lb["pad_x"], lb["pad_y"]) == (320, 247, 0, 36), f"320 landscape: {lb}")
    _check(lb["gain_x"] == 0.25 and lb["gain_y"] == 247 / 987, f"320 gains: {lb}")
    lb416 = letterbox_params(987, 1280, 416)
    _check((lb416["new_w"], lb416["new_h"], lb416["pad_x"], lb416["pad_y"]) == (416, 321, 0, 47), f"416: {lb416}")
    lbp = letterbox_params(1280, 720, 320)
    _check((lbp["new_w"], lbp["new_h"], lbp["pad_x"], lbp["pad_y"]) == (180, 320, 70, 0), f"portrait: {lbp}")

    h0, w0 = 987, 1280
    rng = np.random.default_rng(0)
    img_rows = np.zeros((3, ROW), dtype=np.float32)
    for i in range(3):
        img_rows[i, 0:4] = [100 + 50 * i, 80 + 40 * i, 500 + 60 * i, 900 - 30 * i]
        img_rows[i, 4:6] = [0.9 - 0.1 * i, 0]
        kp = img_rows[i, 6:].reshape(NUM_KEYPOINTS, 3)
        kp[:, 0] = rng.uniform(101, 499, NUM_KEYPOINTS)
        kp[:, 1] = rng.uniform(81, 899, NUM_KEYPOINTS)
        kp[:, 2] = rng.uniform(0, 1, NUM_KEYPOINTS)
    mdl_rows = img_rows.copy()
    mdl_rows[:, X_COLS] = img_rows[:, X_COLS] * lb["gain_x"] + lb["pad_x"]
    mdl_rows[:, Y_COLS] = img_rows[:, Y_COLS] * lb["gain_y"] + lb["pad_y"]
    back = rows_to_image_space(mdl_rows, lb, h0, w0)
    _check(float(np.abs(back - img_rows).max()) < 1e-3, "round trip image -> model -> image")
    _check(not np.shares_memory(back, mdl_rows), "rows_to_image_space must return a copy")

    outside = np.zeros((1, ROW), dtype=np.float32)
    outside[0, X_COLS] = [-50.0, 9999.0] + [-1.0] * NUM_KEYPOINTS
    outside[0, Y_COLS] = [-50.0, 9999.0] + [9999.0] * NUM_KEYPOINTS
    outside[0, 4:6] = [0.7, 3]
    clipped = rows_to_image_space(outside, lb, h0, w0)
    _check(clipped[0, X_COLS].min() >= 0 and clipped[0, X_COLS].max() <= w0, "x not clipped to [0, w0]")
    _check(clipped[0, Y_COLS].min() >= 0 and clipped[0, Y_COLS].max() <= h0, "y not clipped to [0, h0]")
    _check(clipped[0, 4] == np.float32(0.7) and clipped[0, 5] == 3, "score/cls must be untouched")

    raw = np.zeros((1, 300, ROW), dtype=np.float32)
    raw[0, :4, 4] = [0.9, 0.25, 0.2501, 0.1]
    idx, rows = kept_rows(raw)
    _check(idx.tolist() == [0, 2], f"kept_rows indices {idx.tolist()} (0.25 itself must not be kept)")
    _check(rows.shape == (2, ROW), f"kept_rows shape {rows.shape}")
    print("self-test OK")


# --- export flow ---------------------------------------------------------------------------------
def ensure_fixture() -> None:
    """Download the public-domain fixture once; done first so a bad URL fails before the slow export."""
    if FIXTURE.exists():
        return
    req = urllib.request.Request(FIXTURE_URL, headers={"User-Agent": "pose-tracker-export/1.0"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        data = resp.read()
    if not data.startswith(JPEG_MAGIC):
        raise SystemExit(f"fixture download is not a JPEG (first bytes {data[:8].hex()}): {FIXTURE_URL}")
    FIXTURE.parent.mkdir(parents=True, exist_ok=True)
    FIXTURE.write_bytes(data)


def read_fixture():
    import cv2

    bgr = cv2.imread(str(FIXTURE))
    if bgr is None:
        raise SystemExit(f"cv2.imread could not decode {FIXTURE}")
    return bgr


def tensor_info(details: list) -> list:
    return [
        {"name": d["name"], "shape": [int(x) for x in d["shape"]], "dtype": np.dtype(d["dtype"]).name}
        for d in details
    ]


def check_io(size: int, inputs: list, outputs: list, metadata: dict) -> None:
    """Fail closed unless the model has the one input, one output and end-to-end head we decode."""
    ok = (
        len(inputs) == 1
        and inputs[0]["shape"] == [1, 3, size, size]
        and inputs[0]["dtype"] == "float32"
        and len(outputs) == 1
        and outputs[0]["shape"] == [1, MAX_DET, ROW]
        and outputs[0]["dtype"] == "float32"
        and metadata.get("end2end") is True
    )
    if not ok:
        print(f"size {size}: inputs={inputs} outputs={outputs} metadata end2end={metadata.get('end2end')}")
        raise SystemExit(f"size {size}: unexpected model I/O (want one 1x3x{size}x{size} float32 input, "
                         f"one 1x{MAX_DET}x{ROW} float32 output, end2end=true)")


def run_model(model_path: Path, bgr, size: int) -> tuple[np.ndarray, list, list]:
    """Run the tflite model on the letterboxed image the way predict() prepares it."""
    from ai_edge_litert.interpreter import Interpreter
    from ultralytics.data.augment import LetterBox

    interp = Interpreter(model_path=str(model_path))
    interp.allocate_tensors()
    in_det, out_det = interp.get_input_details(), interp.get_output_details()
    meta = json.loads(zipfile.ZipFile(model_path).read("metadata.json"))
    check_io(size, tensor_info(in_det), tensor_info(out_det), meta)

    boxed = LetterBox(new_shape=(size, size), auto=False, stride=32)(image=bgr)
    chw = boxed[..., ::-1].transpose(2, 0, 1)  # BGR -> RGB, HWC -> CHW
    x = np.ascontiguousarray(chw[None]).astype(np.float32) / 255.0
    interp.set_tensor(in_det[0]["index"], x)
    interp.invoke()
    raw = interp.get_tensor(out_det[0]["index"]).copy()
    return raw, tensor_info(in_det), tensor_info(out_det)


def expected_from_predict(model_path: Path, bgr, size: int) -> np.ndarray:
    """predict() detections as (N, 57) rows in original-image pixels."""
    from ultralytics import YOLO

    result = YOLO(str(model_path), task="pose").predict(bgr, imgsz=size, conf=CONF, verbose=False)[0]
    boxes = result.boxes.data.cpu().numpy() if result.boxes is not None else np.zeros((0, 6))
    kpts = result.keypoints.data.cpu().numpy() if result.keypoints is not None else np.zeros((0, NUM_KEYPOINTS, 3))
    return np.concatenate([boxes.reshape(-1, 6), kpts.reshape(len(boxes), 3 * NUM_KEYPOINTS)], axis=1)


def self_check(size: int, got: np.ndarray, expected: np.ndarray) -> float:
    """Our decode of the raw rows must equal predict(); this proves column order and pixel units."""
    diff = float(np.abs(got - expected).max()) if got.shape == expected.shape and got.size else float("inf")
    if got.shape != expected.shape or len(got) < 2 or diff > TOLERANCE_PX:
        print(f"size {size}: decoded shape {got.shape}, predict() shape {expected.shape}, max diff {diff}")
        raise SystemExit(f"size {size}: self-check failed (need same shape, >= 2 people, diff <= {TOLERANCE_PX} px)")
    return diff


def golden_json(size: int, lb: dict, h0: int, w0: int, idx, rows, expected, diff: float) -> dict:
    detections = []
    for i, row in enumerate(rows):
        detections.append({
            "row": int(idx[i]),
            "score": float(row[4]),
            "cls": int(row[5]),
            "box_mdl": [float(v) for v in row[0:4]],
            "keypoints_mdl": row[6:].reshape(NUM_KEYPOINTS, 3).astype(float).tolist(),
            "box_img": [float(v) for v in expected[i, 0:4]],
            "keypoints_img": expected[i, 6:].reshape(NUM_KEYPOINTS, 3).astype(float).tolist(),
        })
    return {
        "imgsz": size,
        "raw_output": {"file": f"{size}_raw_output.f32", "shape": [1, MAX_DET, ROW],
                       "dtype": "float32", "byte_order": "little"},
        "row_layout": ROW_LAYOUT,
        "coordinates": COORDINATES,
        "conf_threshold": CONF,
        "fixture": {"width": w0, "height": h0},
        "letterbox": {k: (float(v) if k.startswith("gain") else int(v)) for k, v in lb.items()},
        "self_check_max_abs_diff_px": diff,
        "detections": detections,
    }


def export_size(size: int, bgr) -> dict:
    """Export one size, verify it, write its model and goldens, return its model_info entry."""
    from ultralytics import YOLO

    h0, w0 = bgr.shape[:2]
    exported = Path(YOLO(f"{MODEL}.pt").export(format="litert", nms=False, imgsz=size)).resolve()
    dst = OUT / f"{MODEL}_{size}.tflite"
    shutil.copyfile(exported, dst)

    raw, inputs, outputs = run_model(dst, bgr, size)
    expected = expected_from_predict(dst, bgr, size)
    lb = letterbox_params(h0, w0, size)
    idx, rows = kept_rows(raw)
    got = rows_to_image_space(rows, lb, h0, w0)
    diff = self_check(size, got, expected)

    raw_path = OUT / "golden" / f"{size}_raw_output.f32"
    raw_path.write_bytes(raw.astype("<f4").tobytes())
    golden_path = OUT / "golden" / f"{size}_golden.json"
    golden = golden_json(size, lb, h0, w0, idx, rows, expected, diff)
    golden_path.write_text(json.dumps(golden, indent=2) + "\n")
    return {
        "file": dst.name,
        "sha256": sha256(dst),
        "bytes": dst.stat().st_size,
        "input": inputs,
        "output": outputs,
        "golden": f"golden/{golden_path.name}",
        "raw_output": f"golden/{raw_path.name}",
        "raw_output_sha256": sha256(raw_path),
        "detections": len(rows),
    }


def package_versions() -> dict:
    names = ("ultralytics", "torch", "litert-torch", "ai-edge-litert")
    return {n: importlib.metadata.version(n) for n in names}


def model_info(sizes: dict, h0: int, w0: int) -> dict:
    return {
        "model": MODEL,
        "versions": package_versions(),
        "exported_from": {"ref": os.environ.get("GITHUB_REF_NAME"), "sha": os.environ.get("GITHUB_SHA")},
        "export": {"format": "litert", "nms": False, "end2end": True, "precision": "float32"},
        "preprocess": {"layout": "NCHW", "color": "RGB", "range": "0..1",
                       "letterbox_pad_value": PAD_VALUE, "resize": "bilinear"},
        "output": {"rows": MAX_DET, "row_layout": ROW_LAYOUT, "coordinates": COORDINATES},
        "fixture": {"file": "fixture/people.jpg", "source": FIXTURE_SOURCE, "url": FIXTURE_URL,
                    "license": FIXTURE_LICENSE, "sha256": sha256(FIXTURE), "width": w0, "height": h0},
        "sizes": sizes,
    }


def export_all() -> None:
    ensure_fixture()
    bgr = read_fixture()
    h0, w0 = bgr.shape[:2]
    (OUT / "golden").mkdir(parents=True, exist_ok=True)
    sizes = {}
    # ultralytics downloads the .pt and writes the .tflite into the cwd; keep that out of the repo.
    with tempfile.TemporaryDirectory() as tmp, contextlib.chdir(tmp):
        for size in SIZES:
            sizes[str(size)] = export_size(size, bgr)
    text = json.dumps(model_info(sizes, h0, w0), indent=2) + "\n"
    (OUT / "model_info.json").write_text(text)
    print(text)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--self-test", action="store_true", help="check the pure helpers (numpy only) and exit")
    args = ap.parse_args()
    if args.self_test:
        self_test()
        return
    export_all()


if __name__ == "__main__":
    sys.exit(main())
