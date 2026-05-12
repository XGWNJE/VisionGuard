"""
Export YOLOv5 models (n/s/m/l/x) to ONNX at 320 and 640 resolutions.

Requirements: pip install ultralytics onnx
Output: D:\ObjectCode\YOLO\models\{variant}\onnx\{variant}_{size}.onnx
        D:\ObjectCode\YOLO\models\{variant}\pt\{variant}.pt
"""

import os
import shutil
from ultralytics import YOLO

VARIANTS = ["yolov5n", "yolov5s", "yolov5m", "yolov5l", "yolov5x"]
SIZES = [320, 640]
OUTPUT_BASE = r"D:\ObjectCode\YOLO\models"

def export_one(variant: str, size: int):
    model_dir = os.path.join(OUTPUT_BASE, variant)
    onnx_dir = os.path.join(model_dir, "onnx")
    pt_dir = os.path.join(model_dir, "pt")
    os.makedirs(onnx_dir, exist_ok=True)
    os.makedirs(pt_dir, exist_ok=True)

    print(f"\n{'='*60}")
    print(f"Exporting {variant} @ {size}x{size} ...")

    # ultralytics auto-downloads .pt if not cached
    model = YOLO(f"{variant}.pt")
    model.export(format="onnx", imgsz=size, opset=12, simplify=True)

    # move exported .onnx to target directory
    src_onnx = f"{variant}.onnx"
    dst_onnx = os.path.join(onnx_dir, f"{variant}_{size}.onnx")
    if os.path.exists(src_onnx):
        shutil.move(src_onnx, dst_onnx)
        print(f"  ONNX  -> {dst_onnx}  ({os.path.getsize(dst_onnx)/1024/1024:.1f} MB)")
    else:
        print(f"  WARNING: {src_onnx} not found after export")

    # copy .pt to target directory (if not already there)
    src_pt = f"{variant}.pt"
    dst_pt = os.path.join(pt_dir, f"{variant}.pt")
    if os.path.exists(src_pt) and not os.path.exists(dst_pt):
        shutil.copy2(src_pt, dst_pt)
        print(f"  PT    -> {dst_pt}")

    del model


def main():
    print("YOLOv5 ONNX Export (opset=16, simplify=True)")
    print(f"Models: {VARIANTS}")
    print(f"Sizes:  {SIZES}")
    print(f"Output: {OUTPUT_BASE}")

    for variant in VARIANTS:
        for size in SIZES:
            try:
                export_one(variant, size)
            except Exception as e:
                print(f"  ERROR: {e}")

    print(f"\n{'='*60}")
    print("Done. Copy .onnx files to:")
    print("  detector\\windows-winforms\\Assets\\")
    print("\nExample:")
    for variant in VARIANTS:
        for size in SIZES:
            src = os.path.join(OUTPUT_BASE, variant, "onnx", f"{variant}_{size}.onnx")
            print(f"  copy \"{src}\" \"detector\\windows-winforms\\Assets\\\"")


if __name__ == "__main__":
    main()
