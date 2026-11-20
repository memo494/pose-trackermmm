#!/usr/bin/env python3
"""Offline detection sweep of the published yolo26n-pose models (no phone needed).

    python tools/model_sweep.py --models DIR --out DIR [--image bus.jpg]
    python tools/model_sweep.py --self-test

DIR holds yolo26n-pose_<size>.tflite where <size> is S (square, 640) or WxH (WIDTHxHEIGHT, 832x384),
the files export_model.py --out publishes. Input [1,3,H,W] float32 RGB 0..1, output [1,300,57]: per row
x1,y1,x2,y2,score,class then 17 x (x,y,conf), in pixels of the letterboxed model input (a model whose
rows are normalised 0..1 is detected on the plain image and handled; the convention is recorded).

Scene: bus.jpg (Ultralytics asset; default taken from the installed ultralytics). The largest square model
runs on the plain image; its rows with score >= 0.5 are the reference people (>= 3 needed). For each person
height ratio p the scene is scaled so that the tallest reference person is p * canvas height, pasted
centred on a black canvas (landscape 1356x610 and portrait 610x1356, the app's capture buffers on the
owner's phone) and clipped. Expected people = reference boxes that lie fully inside the canvas.
Every model sees every canvas letterboxed like Ultralytics LetterBox (gain = min(Wm/Wc, Hm/Hc), INTER_LINEAR,
centred, pad 114); rows >= 0.15 are mapped back to canvas pixels and greedily matched (by score, IoU >= 0.3,
one row per box) to the expected boxes.

Before the scaled cases every model runs on the plain image; one that finds fewer than half the reference
people there is marked "broken on the plain image" (printed, not to be ranked). A model with no row >= 0.15
there is marked broken too (coordinates unknown, its scaled cases are skipped, it is still listed). Two reference boxes with
IoU >= 0.3 stop the run (the reference is not clean). The reference favours its own model's misses. Every
interpreter uses SWEEP_THREADS threads.

Outputs in --out: sweep.json (all numbers) and sweep.md (tables). CPU time is of the machine that runs
this (the CI runner): relative cost only, not the phone GPU.

The pure helpers import only numpy; --self-test checks them with fixed numbers. cv2, ai_edge_litert and
ultralytics are imported inside the functions that need them.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import statistics
import subprocess
import sys
import time
from importlib import metadata
from pathlib import Path
from typing import Callable

import numpy as np

MODEL = "yolo26n-pose"
OUT_ROWS = 300
OUT_COLS = 57
REF_SCORE = 0.5  # reference people and the "found050" threshold
ROW_SCORE = 0.15  # rows below this are dropped before matching
FOUND_SCORE = 0.25  # "found025" / "extra025" threshold
MATCH_IOU = 0.3
MIN_REF_PEOPLE = 3
MIN_EXPECTED = 2
REF_MIN_HEIGHT_FRACTION = 0.3  # sanity: the tallest reference person fills at least this much of the image
NORMALISED_MAX = 1.5  # all box coordinates at or below this: rows are 0..1, not pixels
PAD_VALUE = 114
P_RATIOS = (0.08, 0.12, 0.16, 0.20, 0.25, 0.30, 0.40, 0.50)
CANVASES = (("landscape", 1356, 610), ("portrait", 610, 1356))  # (name, width, height)
TIMING_RUNS = 5
SWEEP_THREADS = 4  # interpreter threads for every model, so CPU times are comparable between runs
PACKAGES = ("numpy", "opencv-python", "opencv-python-headless", "ai-edge-litert", "ultralytics")
CPU_NOTE = "CPU of the CI runner, relative cost only; not the phone GPU"
BROKEN_LABEL = "broken on the plain image"
NO_ROW_COORDINATES = "unknown (no row on the plain image)"
REFERENCE_NOTE = ("The reference favours its own model: a person that model missed is not in the reference, so "
                  "it is not expected from anyone.")
IMAGE_NOTE = "This is a clean test image (bus.jpg on a black canvas), not the owner's video."

_MODEL_FILE = re.compile(rf"{re.escape(MODEL)}_([0-9]+)(?:x([0-9]+))?\.tflite")

Box = tuple[float, float, float, float]


# ---------------------------------------------------------------- pure helpers (numpy only)


def parse_model_name(name: str) -> tuple[int, int] | None:
    """'yolo26n-pose_640.tflite' -> (640, 640); '..._832x384.tflite' -> (832, 384) as (W, H); else None."""
    m = _MODEL_FILE.fullmatch(name)
    if m is None:
        return None
    width = int(m.group(1))
    height = width if m.group(2) is None else int(m.group(2))
    if width <= 0 or height <= 0:
        return None
    return (width, height)


def size_label(width: int, height: int) -> str:
    return str(width) if width == height else f"{width}x{height}"


def letterbox_params(wc: int, hc: int, wm: int, hm: int) -> tuple[float, int, int, int, int]:
    """Ultralytics LetterBox (center=True, scaleup=True): (gain, new_w, new_h, left, top).

    gain = min(Wm/Wc, Hm/Hc); the resized image is (round(Wc*gain), round(Hc*gain)); the padding is split
    half/half with the same round(d -/+ 0.1) rule Ultralytics uses.
    """
    gain = min(wm / wc, hm / hc)
    new_w = int(round(wc * gain))
    new_h = int(round(hc * gain))
    dw = (wm - new_w) / 2
    dh = (hm - new_h) / 2
    left = int(round(dw - 0.1))
    top = int(round(dh - 0.1))
    return gain, new_w, new_h, left, top


def placement(sw: int, sh: int, scale: float, cw: int, ch: int) -> tuple[int, int, int, int]:
    """Scene (sw x sh) scaled by `scale`, centred on a cw x ch canvas: (new_w, new_h, offset_x, offset_y).

    The offset may be negative (the scene is clipped by the canvas).
    """
    new_w = max(1, int(round(sw * scale)))
    new_h = max(1, int(round(sh * scale)))
    return new_w, new_h, (cw - new_w) // 2, (ch - new_h) // 2


def scene_scale(h_ref: float, p: float, ch: int) -> float:
    """Scale at which the tallest reference person (h_ref image px) is p * canvas height."""
    return p * ch / h_ref


def place_box(box: Box, sw: int, sh: int, new_w: int, new_h: int, ox: int, oy: int) -> Box:
    """Scene-pixel box -> canvas-pixel box for the placement from placement()."""
    fx, fy = new_w / sw, new_h / sh
    return (box[0] * fx + ox, box[1] * fy + oy, box[2] * fx + ox, box[3] * fy + oy)


def inside(box: Box, cw: int, ch: int) -> bool:
    return box[0] >= 0 and box[1] >= 0 and box[2] <= cw and box[3] <= ch


def clamp_box(box: Box, w: int, h: int) -> Box:
    return (min(max(box[0], 0.0), w), min(max(box[1], 0.0), h), min(max(box[2], 0.0), w), min(max(box[3], 0.0), h))


def looks_normalised(boxes: np.ndarray) -> bool:
    """True if there are rows and every box coordinate is <= NORMALISED_MAX (0..1 rows, not pixels)."""
    return boxes.shape[0] > 0 and float(boxes[:, :4].max()) <= NORMALISED_MAX


def rows_to_canvas(
    rows: np.ndarray, gain: float, left: int, top: int, wm: int, hm: int, normalised: bool
) -> np.ndarray:
    """(N, >=5) model rows -> (N, 5) x1,y1,x2,y2,score with boxes in canvas pixels."""
    out = np.empty((rows.shape[0], 5), dtype=np.float64)
    out[:, :5] = rows[:, :5]
    if normalised:
        out[:, [0, 2]] *= wm
        out[:, [1, 3]] *= hm
    out[:, [0, 2]] = (out[:, [0, 2]] - left) / gain
    out[:, [1, 3]] = (out[:, [1, 3]] - top) / gain
    return out


def iou(a: Box, b: Box) -> float:
    iw = min(a[2], b[2]) - max(a[0], b[0])
    ih = min(a[3], b[3]) - max(a[1], b[1])
    if iw <= 0 or ih <= 0:
        return 0.0
    inter = iw * ih
    union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / union if union > 0 else 0.0


def greedy_match(rows: np.ndarray, expected: list[Box], min_iou: float = MATCH_IOU):
    """Rows by descending score; each takes the free expected box with the best IoU >= min_iou.

    Returns (matched, unmatched): matched = [(row_index, expected_index, score)], unmatched = [score].
    """
    order = sorted(range(rows.shape[0]), key=lambda i: -float(rows[i, 4]))
    taken: set[int] = set()
    matched: list[tuple[int, int, float]] = []
    unmatched: list[float] = []
    for i in order:
        row_box = (float(rows[i, 0]), float(rows[i, 1]), float(rows[i, 2]), float(rows[i, 3]))
        best, best_iou = -1, min_iou
        for j, e in enumerate(expected):
            if j in taken:
                continue
            v = iou(row_box, e)
            if v >= best_iou:
                best, best_iou = j, v
        if best >= 0:
            taken.add(best)
            matched.append((i, best, float(rows[i, 4])))
        else:
            unmatched.append(float(rows[i, 4]))
    return matched, unmatched


def split_expected_ignore(placed: list[tuple[Box, float]], cw: int, ch: int) -> tuple[list[Box], list[Box]]:
    """Placed reference rows (canvas-pixel box, score) -> (expected, ignore).

    Expected = score >= REF_SCORE and fully inside the canvas. Ignore = every other row that still
    overlaps the canvas (below REF_SCORE, or clipped by the edge), clamped to the canvas.
    """
    expected: list[Box] = []
    ignore: list[Box] = []
    for box, score in placed:
        if score >= REF_SCORE and inside(box, cw, ch):
            expected.append(box)
            continue
        c = clamp_box(box, cw, ch)
        if c[2] > c[0] and c[3] > c[1]:
            ignore.append(c)
    return expected, ignore


def coordinate_convention(boxes: np.ndarray) -> str:
    """'pixels of the model input', 'normalised 0..1', or NO_ROW_COORDINATES when there is no row to tell."""
    if boxes.shape[0] == 0:
        return NO_ROW_COORDINATES
    return "normalised 0..1" if looks_normalised(boxes) else "pixels of the model input"


def duplicate_reference_pairs(boxes: list[Box]) -> list[tuple[int, int, float]]:
    """Pairs (i, j, IoU) of reference boxes that overlap at IoU >= MATCH_IOU: one person counted twice."""
    return [(i, j, v) for i in range(len(boxes)) for j in range(i + 1, len(boxes))
            if (v := iou(boxes[i], boxes[j])) >= MATCH_IOU]


def plain_scene_broken(found: int, ref_people: int) -> bool:
    """A model that finds fewer than half of the reference people on the plain image is broken."""
    return found * 2 < ref_people


def case_stats(rows: np.ndarray, expected: list[Box], ignore: list[Box] | None = None) -> dict:
    """Numbers of one model on one case from canvas-pixel rows (score >= ROW_SCORE).

    A row that matches no expected box but matches an ignore box (IoU >= MATCH_IOU, one row per box) is
    a real person the case does not count: neither found nor extra.
    """
    matched, unmatched = greedy_match(rows, expected)
    scores = [s for _, _, s in matched]
    if ignore:
        taken = {i for i, _, _ in matched}
        rest = rows[[i for i in range(rows.shape[0]) if i not in taken]]
        _, unmatched = greedy_match(rest, ignore)
    return {
        "expected": len(expected),
        "found025": sum(1 for s in scores if s >= FOUND_SCORE),
        "found050": sum(1 for s in scores if s >= REF_SCORE),
        "maxScore": max(scores) if scores else 0.0,
        "meanMatchedScore": sum(scores) / len(scores) if scores else 0.0,
        "extra025": sum(1 for s in unmatched if s >= FOUND_SCORE),
    }


def format_cell(stats: dict | None) -> str:
    if stats is None:
        return "skipped"
    return f"{stats['found025']}/{stats['expected']} ({stats['maxScore']:.2f})"


def md_table(headers: list[str], body: list[list[str]]) -> str:
    lines = ["| " + " | ".join(headers) + " |", "|" + "|".join("---" for _ in headers) + "|"]
    lines += ["| " + " | ".join(r) + " |" for r in body]
    return "\n".join(lines)


def p_label(p: float, ch: int) -> str:
    """'20% (122 px)': p as a percentage and the person height in pixels on a canvas ch px high."""
    return f"{round(p * 100)}% ({round(p * ch)} px)"


def canvas_table(p_ratios, labels: list[str], cells: dict, ch: int, heads: dict | None = None) -> str:
    """cells[(p, label)] -> stats dict or None. Rows = p as % of the canvas height ch (and in px).

    heads maps a label to its column header (default: the label itself).
    """
    body = [[p_label(p, ch)] + [format_cell(cells.get((p, lab))) for lab in labels] for p in p_ratios]
    return md_table(["p"] + [(heads or {}).get(lab, lab) for lab in labels], body)


# ---------------------------------------------------------------- self-test


def self_test() -> None:
    def ok(name: str, cond: bool) -> None:
        if not cond:
            print(f"FAIL {name}")
            raise SystemExit(1)
        print(f"ok   {name}")

    def near(a: float, b: float, tol: float = 1e-6) -> bool:
        return abs(a - b) <= tol

    # model file names
    ok("name square", parse_model_name("yolo26n-pose_640.tflite") == (640, 640))
    ok("name WxH", parse_model_name("yolo26n-pose_832x384.tflite") == (832, 384))
    ok("name portrait WxH", parse_model_name("yolo26n-pose_384x832.tflite") == (384, 832))
    for bad in ("yolo26n-pose_640.json", "yolo26n-pose_.tflite", "yolo26n-pose_640x.tflite",
                "yolo26n-pose_0.tflite", "yolo26n_640.tflite", "x_yolo26n-pose_640.tflite",
                "yolo26n-pose_640x384x2.tflite"):
        ok(f"name rejects {bad!r}", parse_model_name(bad) is None)
    ok("size label square", size_label(640, 640) == "640")
    ok("size label rect", size_label(832, 384) == "832x384")

    # letterbox: gain, content size, centred padding
    g, nw, nh, left, top = letterbox_params(1356, 610, 640, 640)
    ok("letterbox square on landscape", near(g, 640 / 1356) and (nw, nh, left, top) == (640, 288, 0, 176))
    g, nw, nh, left, top = letterbox_params(610, 1356, 640, 640)
    ok("letterbox square on portrait", near(g, 640 / 1356) and (nw, nh, left, top) == (288, 640, 176, 0))
    g, nw, nh, left, top = letterbox_params(1356, 610, 832, 384)
    ok("letterbox 832x384 on landscape", near(g, 832 / 1356) and (nw, nh, left, top) == (832, 374, 0, 5))
    g, nw, nh, left, top = letterbox_params(610, 1356, 832, 384)
    ok("letterbox 832x384 on portrait is height-limited", near(g, 384 / 1356) and (nw, nh, left, top) == (173, 384, 329, 0))
    g, nw, nh, left, top = letterbox_params(610, 1356, 384, 832)
    ok("letterbox 384x832 on portrait", near(g, 832 / 1356) and (nw, nh, left, top) == (374, 832, 5, 0))
    g, nw, nh, left, top = letterbox_params(810, 1080, 640, 640)
    ok("letterbox bus on 640", near(g, 640 / 1080) and (nw, nh, left, top) == (480, 640, 80, 0))
    g, nw, nh, left, top = letterbox_params(320, 320, 640, 640)
    ok("letterbox scales up", near(g, 2.0) and (nw, nh, left, top) == (640, 640, 0, 0))

    # placement: landscape and portrait canvas, centred, clipped
    ok("scene scale landscape", near(scene_scale(100, 0.5, 200), 1.0))
    ok("scene scale portrait", near(scene_scale(100, 0.5, 400), 2.0))
    ok("scene scale 8% of 610", near(scene_scale(800, 0.08, 610), 0.061))
    pl = placement(100, 200, 1.0, 400, 200)
    ok("placement landscape centred", pl == (100, 200, 150, 0))
    ok("box on landscape canvas", place_box((10, 20, 30, 120), 100, 200, *pl) == (160, 20, 180, 120))
    pl = placement(100, 200, 2.0, 200, 400)
    ok("placement portrait fills", pl == (200, 400, 0, 0))
    ok("box on portrait canvas", place_box((10, 20, 30, 120), 100, 200, *pl) == (20, 40, 60, 240))
    pl = placement(100, 200, 1.0, 100, 100)
    ok("placement clipped has negative offset", pl == (100, 200, 0, -50))
    clipped = place_box((10, 20, 30, 120), 100, 200, *pl)
    ok("clipped box is not inside", clipped == (10, -30, 30, 70) and not inside(clipped, 100, 100))
    ok("inside accepts the edge", inside((0, 0, 100, 100), 100, 100) and not inside((0, 0, 100.5, 100), 100, 100))
    pl = placement(810, 1080, scene_scale(800, 0.5, 610), 1356, 610)
    ok("bus at 50% on landscape", pl == (309, 412, 523, 99))

    # rows -> canvas pixels
    rows = np.zeros((1, OUT_COLS))
    rows[0, :5] = (100, 276, 200, 376, 0.9)
    mapped = rows_to_canvas(rows, 0.5, 0, 176, 640, 640, False)
    ok("row to canvas (pixels)", mapped.tolist() == [[200.0, 200.0, 400.0, 400.0, 0.9]])
    rows[0, :5] = (0.25, 0.5, 0.5, 0.75, 0.9)
    mapped = rows_to_canvas(rows, 0.5, 0, 0, 640, 320, True)
    ok("row to canvas (normalised)", mapped.tolist() == [[320.0, 320.0, 640.0, 480.0, 0.9]])
    rows[0, :5] = (10, 10, 50, 50, 0.9)
    mapped = rows_to_canvas(rows, 0.5, 5, 7, 640, 640, False)
    ok("row to canvas removes the padding", mapped.tolist() == [[10.0, 6.0, 90.0, 86.0, 0.9]])
    ok("clamp box", clamp_box((-3.0, 5.0, 812.0, 1081.5), 810, 1080) == (0.0, 5.0, 810.0, 1080.0))
    ok("normalised detection: 0..1", looks_normalised(np.array([[0.1, 0.2, 0.9, 1.0]])))
    ok("pixel rows are not normalised", not looks_normalised(np.array([[10, 20, 300, 400.0]])))
    ok("no rows is not normalised", not looks_normalised(np.zeros((0, 4))))

    # IoU and matching
    ok("iou identical", iou((0, 0, 10, 10), (0, 0, 10, 10)) == 1.0)
    ok("iou third", near(iou((0, 0, 10, 10), (5, 0, 15, 10)), 1 / 3))
    ok("iou disjoint", iou((0, 0, 10, 10), (20, 20, 30, 30)) == 0.0)
    ok("iou touching", iou((0, 0, 10, 10), (10, 0, 20, 10)) == 0.0)

    def mk(*items) -> np.ndarray:
        return np.array([[*b, s] for b, s in items], dtype=np.float64).reshape(-1, 5)

    exp = [(0.0, 0.0, 10.0, 10.0), (100.0, 100.0, 110.0, 110.0)]
    m, un = greedy_match(mk(((0, 0, 10, 10), 0.9), ((100, 100, 110, 110), 0.4)), exp)
    ok("both matched", sorted((i, j) for i, j, _ in m) == [(0, 0), (1, 1)] and un == [])
    m, un = greedy_match(mk(((0, 0, 10, 10), 0.5), ((1, 0, 11, 10), 0.8)), exp)
    ok("one row per box: the higher score wins, the other is unmatched",
       [(i, j) for i, j, _ in m] == [(1, 0)] and un == [0.5])
    m, un = greedy_match(mk(((0, 0, 10, 10), 0.5), ((200, 200, 210, 210), 0.3)), exp)
    ok("a row on nothing is unmatched", len(m) == 1 and un == [0.3])
    m, un = greedy_match(mk(((5, 0, 15, 10), 0.9)), exp)  # iou 1/3 >= 0.3
    ok("iou 1/3 matches at 0.3", len(m) == 1)
    m, un = greedy_match(mk(((7, 0, 17, 10), 0.9)), exp)  # iou 3/17 < 0.3
    ok("low iou does not match", m == [] and un == [0.9])

    st = case_stats(mk(((0, 0, 10, 10), 0.9), ((100, 100, 110, 110), 0.2), ((300, 300, 310, 310), 0.6),
                       ((400, 400, 410, 410), 0.2)), exp)
    ok("case stats", st == {"expected": 2, "found025": 1, "found050": 1, "maxScore": 0.9,
                            "meanMatchedScore": 0.55, "extra025": 1})
    st = case_stats(np.zeros((0, 5)), exp)
    ok("case stats without rows", st["found025"] == 0 and st["maxScore"] == 0.0 and st["meanMatchedScore"] == 0.0)
    ok("found025 counts scores at 0.25",
       case_stats(mk(((0, 0, 10, 10), 0.25), ((100, 100, 110, 110), 0.49)), exp)["found025"] == 2)

    # ignore boxes: people the case does not count are neither found nor extra
    placed = [((10.0, 10.0, 50.0, 90.0), 0.9),      # expected
              ((60.0, 10.0, 100.0, 90.0), 0.3),     # below 0.5 -> ignore
              ((90.0, 10.0, 130.0, 90.0), 0.9),     # clipped by the right edge (cw=100) -> ignore, clamped
              ((200.0, 10.0, 240.0, 90.0), 0.9),    # entirely outside the canvas -> dropped
              ((100.0, 10.0, 140.0, 90.0), 0.9)]    # touches the edge only -> dropped
    ex, ig = split_expected_ignore(placed, 100, 100)
    ok("split: expected is score >= 0.5 and fully inside", ex == [(10.0, 10.0, 50.0, 90.0)])
    ok("split: ignore is weak or clipped boxes still overlapping, clamped",
       ig == [(60.0, 10.0, 100.0, 90.0), (90.0, 10.0, 100.0, 90.0)])
    ok("split: nothing to ignore", split_expected_ignore([((0.0, 0.0, 10.0, 10.0), 0.9)], 100, 100)[1] == [])
    ign = [(60.0, 10.0, 100.0, 90.0)]
    ex1 = [(10.0, 10.0, 50.0, 90.0)]
    rws = mk(((10, 10, 50, 90), 0.9), ((60, 10, 100, 90), 0.8))
    ok("a row on an ignore box is not an extra", case_stats(rws, ex1, ign)["extra025"] == 0)
    ok("... but is an extra without the ignore box", case_stats(rws, ex1)["extra025"] == 1)
    st = case_stats(rws, ex1, ign)
    ok("ignored row is not found either", st["found025"] == 1 and st["maxScore"] == 0.9)
    rws2 = mk(((10, 10, 50, 90), 0.9), ((60, 10, 100, 90), 0.8), ((61, 10, 100, 90), 0.7))
    ok("one row per ignore box: a duplicate is an extra", case_stats(rws2, ex1, ign)["extra025"] == 1)
    rws3 = mk(((10, 10, 50, 90), 0.9), ((70, 10, 90, 30), 0.8))
    ok("a row that overlaps the ignore box below IoU 0.3 is an extra", case_stats(rws3, ex1, ign)["extra025"] == 1)
    ok("a row matched to an expected box never goes to ignore",
       case_stats(mk(((10, 10, 50, 90), 0.9)), ex1, [(10.0, 10.0, 50.0, 90.0)]) ==
       case_stats(mk(((10, 10, 50, 90), 0.9)), ex1))

    # table formatting
    ok("cell", format_cell({"found025": 3, "expected": 4, "maxScore": 0.8765}) == "3/4 (0.88)")
    ok("skipped cell", format_cell(None) == "skipped")
    ok("p label landscape", p_label(0.20, 610) == "20% (122 px)")
    ok("p label portrait", p_label(0.20, 1356) == "20% (271 px)" and p_label(0.08, 610) == "8% (49 px)")
    tbl = canvas_table((0.08, 0.5), ["640", "832x384"],
                       {(0.08, "640"): {"found025": 0, "expected": 5, "maxScore": 0.0},
                        (0.5, "640"): {"found025": 5, "expected": 5, "maxScore": 0.94},
                        (0.5, "832x384"): {"found025": 4, "expected": 5, "maxScore": 0.9}}, 610)
    ok("canvas table", tbl.splitlines() == [
        "| p | 640 | 832x384 |", "|---|---|---|",
        "| 8% (49 px) | 0/5 (0.00) | skipped |", "| 50% (305 px) | 5/5 (0.94) | 4/5 (0.90) |"])

    # decision 1: duplicate reference boxes (IoU >= MATCH_IOU) mean the reference is not clean
    clean = [(0.0, 0.0, 10.0, 10.0), (100.0, 100.0, 110.0, 110.0), (200.0, 0.0, 210.0, 10.0)]
    ok("clean reference has no duplicate pairs", duplicate_reference_pairs(clean) == [])
    dup = clean + [(1.0, 0.0, 11.0, 10.0)]  # iou with the first = 9/11
    pairs = duplicate_reference_pairs(dup)
    ok("duplicate reference pair is found", [(i, j) for i, j, _ in pairs] == [(0, 3)] and near(pairs[0][2], 9 / 11))
    ok("iou exactly at the match threshold counts as duplicate",
       len(duplicate_reference_pairs([(0.0, 0.0, 10.0, 10.0), (5.0, 0.0, 15.0, 10.0)])) == 1)  # 1/3 >= 0.3
    ok("iou below the match threshold is two people",
       duplicate_reference_pairs([(0.0, 0.0, 10.0, 10.0), (7.0, 0.0, 17.0, 10.0)]) == [])  # 3/17 < 0.3
    ok("no reference boxes, no pairs", duplicate_reference_pairs([]) == [])

    # decision 2: plain-scene check, fewer than half the reference people means broken
    ok("plain: all found is fine", not plain_scene_broken(4, 4))
    ok("plain: exactly half is fine", not plain_scene_broken(2, 4))
    ok("plain: less than half is broken", plain_scene_broken(1, 4))
    ok("plain: odd count, 2 of 5 is broken", plain_scene_broken(2, 5))
    ok("plain: odd count, 3 of 5 is fine", not plain_scene_broken(3, 5))
    ok("plain: nothing found is broken", plain_scene_broken(0, 3))
    plain_exp = [(0.0, 0.0, 10.0, 10.0), (100.0, 100.0, 110.0, 110.0), (200.0, 0.0, 210.0, 10.0)]
    plain_rows = mk(((0, 0, 10, 10), 0.9), ((100, 100, 110, 110), 0.35), ((200, 0, 210, 10), 0.2),
                    ((300, 300, 310, 310), 0.9))  # 3 rows match (one only at 0.2), 1 matches nothing
    ok("plain found counts matched rows at score >= found025 only, not every matched row",
       len(greedy_match(plain_rows, plain_exp)[0]) == 3 and case_stats(plain_rows, plain_exp)["found025"] == 2)
    ok("convention: no row on the plain image is unknown", coordinate_convention(np.zeros((0, 4))) == NO_ROW_COORDINATES)
    ok("convention: pixels", coordinate_convention(np.array([[10, 20, 300, 400.0]])) == "pixels of the model input")
    ok("convention: normalised", coordinate_convention(np.array([[0.1, 0.2, 0.9, 1.0]])) == "normalised 0..1")

    # sweep.md: broken marking, reference note, thread count
    fake = {
        "image": {"name": "bus.jpg", "width": 810, "height": 1080},
        "reference": {"model": "yolo26n-pose_640.tflite", "people": 4, "hRef": 700.0},
        "canvases": [{"name": "landscape", "width": 1356, "height": 610}],
        "threads": SWEEP_THREADS,
        "cases": [{"canvas": "landscape", "p": 0.5, "expected": 3, "skipped": False, "models": {
            "640": {"found025": 3, "expected": 3, "maxScore": 0.9},
            "320": {"found025": 0, "expected": 3, "maxScore": 0.0}}}],
        "models": [
            {"label": "640", "gain": {"landscape": 0.47}, "cpuMs": 10.0, "sha256": "a" * 64, "bytes": 1,
             "coordinates": "pixels of the model input", "plainFound": 4, "plainExpected": 4, "broken": False},
            {"label": "320", "gain": {"landscape": 0.23}, "cpuMs": 5.0, "sha256": "b" * 64, "bytes": 1,
             "coordinates": "pixels of the model input", "plainFound": 1, "plainExpected": 4, "broken": True}],
    }
    md = render_md(fake)
    ok("md: broken model is marked", md.count(BROKEN_LABEL) >= 1 and "320 (broken on the plain image)" in md)
    ok("md: a model that is fine is not marked", "640 (broken" not in md)
    ok("md: broken model rows are still printed", "0/3 (0.00)" in md)
    ok("md: names the reference model and its bias",
       "yolo26n-pose_640.tflite" in md and "favours" in md and "not expected from anyone" in md)
    ok("md: prints the thread count", f"{SWEEP_THREADS} threads" in md)
    ok("thread count is fixed at 4", SWEEP_THREADS == 4)
    fake["models"][1]["broken"] = False
    ok("md: nothing marked when no model is broken", BROKEN_LABEL not in render_md(fake))
    fake["models"].append(
        {"label": "960x448", "gain": {"landscape": 0.7}, "cpuMs": 20.0, "sha256": "c" * 64, "bytes": 1,
         "coordinates": NO_ROW_COORDINATES, "plainFound": 0, "plainExpected": 4, "broken": True, "noRows": True})
    md = render_md(fake)
    ok("md: a model with no row on the plain image is listed, marked and its cases are skipped",
       "960x448 (broken on the plain image)" in md and NO_ROW_COORDINATES in md and "960x448 (0/4)" in md
       and "| skipped |" in md)

    # decision 5: publish_tag guard of the workflow (the real "Check inputs" script is run, not a copy)
    wf = Path(__file__).resolve().parent.parent / ".github" / "workflows" / "export-model.yml"
    try:  # the step name "Check inputs" of export-model.yml is part of this test's contract
        script = workflow_check_script(wf.read_text())
    except OSError as e:
        script = ""
        print(f"cannot read {wf}: {e}")
    ok("workflow check script found (step 'Check inputs' of export-model.yml)",
       "publish_tag" in script and script.count("\n") > 5)
    bash = shutil.which("bash")
    ok("bash is available for the tag check", bash is not None)

    def tag_exit(sizes: str, tag: str, sweep: str = "false") -> int:
        env = {"SIZES": sizes, "TAG": tag, "SWEEP": sweep, "PATH": os.environ.get("PATH", "")}
        return subprocess.run([bash, "-c", script], env=env, capture_output=True).returncode

    ok("tag: models is accepted", tag_exit("640", "models") == 0)
    ok("tag: models-v2 is accepted", tag_exit("640", "models-v2") == 0)
    ok("tag: no tag is accepted", tag_exit("640", "") == 0)
    ok("tag: models-sweep is refused", tag_exit("640", "models-sweep") != 0)
    ok("tag: latest is refused", tag_exit("640", "latest") != 0)
    ok("tag: not starting with models is refused", tag_exit("640", "release") != 0)
    ok("tag: a tag without sizes is refused", tag_exit("", "models") != 0)
    ok("tag: sweep with a tag is refused", tag_exit("", "models", "true") != 0)
    ok("tag: sweep alone is accepted", tag_exit("", "", "true") == 0)
    print("self-test passed")


def workflow_check_script(text: str) -> str:
    """The bash of the 'Check inputs' step of export-model.yml (line based, no YAML library).

    The step name "Check inputs" and its `run: |` block are part of the self-test's contract: rename the
    step and the self-test fails.
    """
    lines = text.splitlines()
    for i, line in enumerate(lines):
        if line.strip() == "- name: Check inputs":
            break
    else:
        return ""
    for i in range(i + 1, len(lines)):
        if lines[i].strip() == "run: |":
            indent = len(lines[i]) - len(lines[i].lstrip())
            body = []
            for line in lines[i + 1:]:
                if line.strip() and len(line) - len(line.lstrip()) <= indent:
                    break
                body.append(line[indent + 2:] if line.strip() else "")
            return "\n".join(body).strip() + "\n"
        if lines[i].lstrip().startswith("- name:"):
            return ""
    return ""


# ---------------------------------------------------------------- heavy part


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def package_versions() -> dict:
    out = {}
    for name in PACKAGES:
        try:
            out[name] = metadata.version(name)
        except metadata.PackageNotFoundError:
            out[name] = None
    return out


def to_input(canvas_bgr: np.ndarray, wm: int, hm: int):
    """Letterbox like Ultralytics and return (float32 [1,3,Hm,Wm] RGB 0..1, gain, left, top)."""
    import cv2

    hc, wc = canvas_bgr.shape[:2]
    gain, new_w, new_h, left, top = letterbox_params(wc, hc, wm, hm)
    img = canvas_bgr
    if (wc, hc) != (new_w, new_h):
        img = cv2.resize(img, (new_w, new_h), interpolation=cv2.INTER_LINEAR)
    bottom = hm - new_h - top
    right = wm - new_w - left
    img = cv2.copyMakeBorder(img, top, bottom, left, right, cv2.BORDER_CONSTANT,
                             value=(PAD_VALUE, PAD_VALUE, PAD_VALUE))
    if img.shape[:2] != (hm, wm):
        raise SystemExit(f"letterbox gave {img.shape[:2]}, expected {(hm, wm)}")
    rgb = img[:, :, ::-1]
    tensor = np.ascontiguousarray(rgb.transpose(2, 0, 1)[None], dtype=np.float32) / 255.0
    return tensor, gain, left, top


class LoadedModel:
    def __init__(self, path: Path, width: int, height: int):
        from ai_edge_litert.interpreter import Interpreter

        self.path, self.width, self.height = path, width, height
        self.label = size_label(width, height)
        self.interp = Interpreter(model_path=str(path), num_threads=SWEEP_THREADS)
        self.interp.allocate_tensors()
        ins, outs = self.interp.get_input_details(), self.interp.get_output_details()
        want_in, want_out = [1, 3, height, width], [1, OUT_ROWS, OUT_COLS]
        for kind, got, want in (("input", ins, want_in), ("output", outs, want_out)):
            if len(got) != 1:
                raise SystemExit(f"{path.name}: expected exactly one {kind} tensor, found {len(got)}")
            shape, dtype = [int(x) for x in got[0]["shape"]], np.dtype(got[0]["dtype"]).name
            if shape != want or dtype != "float32":
                raise SystemExit(f"{path.name}: {kind} is {shape} {dtype}, expected {want} float32")
        self.in_index, self.out_index = ins[0]["index"], outs[0]["index"]
        self.normalised = False

    def infer(self, tensor: np.ndarray) -> np.ndarray:
        self.interp.set_tensor(self.in_index, tensor)
        self.interp.invoke()
        return np.array(self.interp.get_tensor(self.out_index)[0], dtype=np.float32)


def detect(infer: Callable[[np.ndarray], np.ndarray], size: tuple[int, int], canvas_bgr: np.ndarray,
           normalised: bool) -> tuple[np.ndarray, float]:
    """Run one canvas: returns (rows >= ROW_SCORE as canvas-pixel x1,y1,x2,y2,score, gain)."""
    wm, hm = size
    tensor, gain, left, top = to_input(canvas_bgr, wm, hm)
    rows = infer(tensor)
    rows = rows[rows[:, 4] >= ROW_SCORE]
    return rows_to_canvas(rows, gain, left, top, wm, hm, normalised), gain


def build_canvas(scene: np.ndarray, scale: float, cw: int, ch: int):
    """Black canvas with the scene scaled and pasted centred (clipped). Returns (canvas, new_w, new_h, ox, oy)."""
    import cv2

    sh, sw = scene.shape[:2]
    new_w, new_h, ox, oy = placement(sw, sh, scale, cw, ch)
    interp = cv2.INTER_AREA if scale < 1.0 else cv2.INTER_LINEAR
    resized = cv2.resize(scene, (new_w, new_h), interpolation=interp)
    canvas = np.zeros((ch, cw, 3), dtype=np.uint8)
    x0, y0 = max(ox, 0), max(oy, 0)
    x1, y1 = min(ox + new_w, cw), min(oy + new_h, ch)
    if x1 > x0 and y1 > y0:
        canvas[y0:y1, x0:x1] = resized[y0 - oy:y1 - oy, x0 - ox:x1 - ox]
    return canvas, new_w, new_h, ox, oy


def median_ms(fn: Callable[[], object]) -> float:
    fn()  # warm-up
    times = []
    for _ in range(TIMING_RUNS):
        t0 = time.perf_counter()
        fn()
        times.append((time.perf_counter() - t0) * 1000.0)
    return statistics.median(times)


def find_image(arg: Path | None) -> Path:
    if arg is not None:
        return arg
    try:
        from ultralytics.utils import ASSETS
    except ImportError as e:
        raise SystemExit("ultralytics is not installed; pass --image PATH to bus.jpg") from e
    return Path(ASSETS) / "bus.jpg"


def render_md(result: dict) -> str:
    labels = [m["label"] for m in result["models"]]
    heads = {m["label"]: m["label"] + (f" ({BROKEN_LABEL})" if m.get("broken") else "") for m in result["models"]}
    out = ["# Detection sweep of the published models", "",
           f"Image: {result['image']['name']} ({result['image']['width']}x{result['image']['height']}), "
           f"reference people: {result['reference']['people']} (largest square model "
           f"{result['reference']['model']}, score >= {REF_SCORE}), tallest {result['reference']['hRef']:.0f} px.",
           "Cell = found at score >= 0.25 / expected people (maxScore of the matched rows). "
           f"Match: score >= {ROW_SCORE} rows, greedy by score, IoU >= {MATCH_IOU}, one row per box. "
           "p is a share of each canvas's height, so the same p is a taller person in px on the portrait canvas "
           "than on the landscape one (the px in the row label).",
           f"Reference model: {result['reference']['model']}. {REFERENCE_NOTE}",
           f"Interpreter threads: {result['threads']} threads for every model.", ""]
    broken = [m for m in result["models"] if m.get("broken")]
    if broken:
        out += [f"Models marked '{BROKEN_LABEL}' found fewer than half of the reference people on the plain "
                "image; their rows are printed but must not be ranked: "
                + ", ".join(f"{m['label']} ({m['plainFound']}/{m['plainExpected']})" for m in broken), ""]
    for canvas in result["canvases"]:
        name = canvas["name"]
        out += [f"## {name} canvas {canvas['width']}x{canvas['height']}", ""]
        cells = {}
        for case in result["cases"]:
            if case["canvas"] == name and not case["skipped"]:
                for lab, st in case["models"].items():
                    cells[(case["p"], lab)] = st
        out += [canvas_table(P_RATIOS, labels, cells, canvas["height"], heads), ""]
        out += ["Content scale (letterbox gain) per model:", ""]
        out += [md_table(["model", "content scale"],
                         [[m["label"], f"{m['gain'][name]:.3f}"] for m in result["models"]]), ""]
    out += [f"CPU time per inference, median of {TIMING_RUNS} after one warm-up ({CPU_NOTE}):", ""]
    out += [md_table(["model", "ms", "sha256", "bytes", "output coordinates"],
                     [[m["label"], f"{m['cpuMs']:.1f}", m["sha256"][:12], str(m["bytes"]), m["coordinates"]]
                      for m in result["models"]]), ""]
    skipped = [c for c in result["cases"] if c["skipped"]]
    out += ["Skipped cases (fewer than "
            f"{MIN_EXPECTED} expected people fully inside the canvas): "
            + (", ".join(f"{c['canvas']} {round(c['p'] * 100)}% ({c['expected']})" for c in skipped) or "none"), ""]
    out += [IMAGE_NOTE, ""]
    return "\n".join(out)


def run_sweep(models_dir: Path, out_dir: Path, image_arg: Path | None) -> None:
    import cv2

    files = []
    for path in sorted(models_dir.glob("*.tflite")):
        size = parse_model_name(path.name)
        if size is None:
            raise SystemExit(f"{path.name}: not a {MODEL}_<S|WxH>.tflite file name")
        files.append((size, path))
    if not files:
        raise SystemExit(f"no {MODEL}_*.tflite files in {models_dir}")
    files.sort(key=lambda f: (f[0][0] * f[0][1], f[0][0]))
    models = [LoadedModel(path, w, h) for (w, h), path in files]

    image_path = find_image(image_arg)
    scene = cv2.imread(str(image_path))
    if scene is None:
        raise SystemExit(f"cannot read image {image_path}")
    sh, sw = scene.shape[:2]

    # Per model on the plain image: the coordinate convention, and the CPU timing input.
    square = [m for m in models if m.width == m.height]
    if not square:
        raise SystemExit("no square model among the files: the reference people need the largest square one")
    for m in models:
        tensor, gain, left, top = to_input(scene, m.width, m.height)
        raw = m.infer(tensor)
        raw = raw[raw[:, 4] >= ROW_SCORE]
        # No row at all: the convention cannot be told, so the model is broken and its cases are skipped.
        m.no_rows = raw.shape[0] == 0
        m.coordinates = coordinate_convention(raw[:, :4])
        m.normalised = m.coordinates == "normalised 0..1"
        # The plain-image rows in scene pixels are reused for the reference and the plain-scene check.
        m.plain_rows = (np.zeros((0, 5)) if m.no_rows else
                        rows_to_canvas(raw, gain, left, top, m.width, m.height, m.normalised))
        m.cpu_ms = median_ms(lambda m=m, t=tensor: m.infer(t))
        print(f"{m.path.name}: {m.coordinates}, {m.cpu_ms:.1f} ms")

    ref = max(square, key=lambda m: m.width)
    ref_rows = ref.plain_rows
    # Boxes may overshoot the image by a pixel or two; clamp them to the image.
    ref_all = [(clamp_box((float(r[0]), float(r[1]), float(r[2]), float(r[3])), sw, sh), float(r[4]))
               for r in ref_rows]
    ref_boxes: list[Box] = [b for b, score in ref_all if score >= REF_SCORE]
    if len(ref_boxes) < MIN_REF_PEOPLE:
        raise SystemExit(f"reference model {ref.path.name} found {len(ref_boxes)} people >= {REF_SCORE} "
                         f"on {image_path.name}, expected at least {MIN_REF_PEOPLE}")
    dups = duplicate_reference_pairs(ref_boxes)
    if dups:
        raise SystemExit(f"reference model {ref.path.name} has duplicate people on {image_path.name} (reference "
                         f"boxes with IoU >= {MATCH_IOU}, the reference is not clean): "
                         + "; ".join(f"#{i} {[round(v) for v in ref_boxes[i]]} and #{j} "
                                     f"{[round(v) for v in ref_boxes[j]]} IoU {v:.2f}" for i, j, v in dups))
    h_ref = max(b[3] - b[1] for b in ref_boxes)
    if h_ref < REF_MIN_HEIGHT_FRACTION * sh:
        raise SystemExit(f"reference boxes look wrong (tallest {h_ref:.0f} px of a {sh} px image, "
                         f"coordinates {ref.coordinates}): {[[round(v) for v in b] for b in ref_boxes]}")
    print(f"reference: {len(ref_boxes)} people from {ref.path.name}, hRef={h_ref:.0f}px")

    # Plain-scene check per model: fewer than half the reference people found means the model is broken.
    for m in models:
        m.plain_found = case_stats(m.plain_rows, ref_boxes)["found025"]
        m.broken = m.no_rows or plain_scene_broken(m.plain_found, len(ref_boxes))
        print(f"{m.path.name}: {m.plain_found}/{len(ref_boxes)} reference people on the plain image"
              + (f" ({BROKEN_LABEL})" if m.broken else ""))

    cases = []
    gains = {m.label: {n: letterbox_params(w, h, m.width, m.height)[0] for n, w, h in CANVASES} for m in models}
    for cname, cw, ch in CANVASES:
        for p in P_RATIOS:
            scale = scene_scale(h_ref, p, ch)
            canvas, new_w, new_h, ox, oy = build_canvas(scene, scale, cw, ch)
            expected, ignore = split_expected_ignore(
                [(place_box(b, sw, sh, new_w, new_h, ox, oy), score) for b, score in ref_all], cw, ch)
            case = {"canvas": cname, "p": p, "scale": scale, "expected": len(expected), "ignoreBoxes": len(ignore),
                    "skipped": len(expected) < MIN_EXPECTED, "models": {}}
            cases.append(case)
            if case["skipped"]:
                continue
            for m in models:
                if m.no_rows:
                    continue
                rows, gain = detect(m.infer, (m.width, m.height), canvas, m.normalised)
                case["models"][m.label] = case_stats(rows, expected, ignore) | {"contentScale": gain}

    result = {
        "image": {"name": image_path.name, "width": sw, "height": sh, "sha256": sha256(image_path)},
        "reference": {"model": ref.path.name, "people": len(ref_boxes), "hRef": h_ref, "note": REFERENCE_NOTE,
                      "boxes": [[round(v, 1) for v in b] for b in ref_boxes]},
        "thresholds": {"reference": REF_SCORE, "row": ROW_SCORE, "found": FOUND_SCORE, "iou": MATCH_IOU},
        "canvases": [{"name": n, "width": w, "height": h} for n, w, h in CANVASES],
        "pRatios": list(P_RATIOS),
        "cpuNote": CPU_NOTE,
        "threads": SWEEP_THREADS,
        "versions": package_versions(),
        "models": [{"name": m.path.name, "label": m.label, "width": m.width, "height": m.height,
                    "sha256": sha256(m.path), "bytes": m.path.stat().st_size, "cpuMs": m.cpu_ms,
                    "coordinates": m.coordinates, "gain": gains[m.label], "plainFound": m.plain_found,
                    "plainExpected": len(ref_boxes), "broken": m.broken, "noRows": m.no_rows} for m in models],
        "cases": cases,
    }
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "sweep.json").write_text(json.dumps(result, indent=2) + "\n")
    (out_dir / "sweep.md").write_text(render_md(result))
    print("Wrote", out_dir / "sweep.json", "and", out_dir / "sweep.md")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--models", type=Path, help=f"directory with {MODEL}_<S|WxH>.tflite files")
    ap.add_argument("--out", type=Path, help="directory for sweep.json and sweep.md")
    ap.add_argument("--image", type=Path, default=None, help="bus.jpg (default: from the installed ultralytics)")
    ap.add_argument("--self-test", action="store_true", help="check the pure helpers and exit")
    args = ap.parse_args()
    if args.self_test:
        self_test()
        return
    if args.models is None or args.out is None:
        ap.error("--models and --out are required (or --self-test)")
    run_sweep(args.models, args.out, args.image)


if __name__ == "__main__":
    sys.exit(main())
