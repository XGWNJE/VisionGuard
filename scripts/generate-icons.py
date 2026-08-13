from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]

DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

SOURCE = ROOT / "icon" / "visionguard-icon-master.png"
BOXES = {
    "windows": (112, 108, 595, 596),
    "android_detector": (657, 108, 1140, 596),
    "android_receiver": (112, 660, 595, 1148),
    "server_web": (657, 660, 1140, 1148),
}

BACKGROUND_COLORS = {
    "android_detector": "#F8F7F6",
    "android_receiver": "#D91C1F",
}


def master(role):
    with Image.open(SOURCE) as source:
        tile = source.convert("RGBA").crop(BOXES[role])
    # Remove the concept-board presentation outline/glow while preserving the
    # confirmed bitmap artwork. The review images use this exact crop.
    width, height = tile.size
    trim = round(min(width, height) * 0.065)
    return tile.crop((trim, trim, width - trim, height - trim)).resize(
        (width, height), Image.Resampling.LANCZOS
    )


def resize(image, size):
    return image.resize((size, size), Image.Resampling.LANCZOS)


def shape_mask(size, kind="rounded"):
    scale = 4
    mask = Image.new("L", (size * scale, size * scale), 0)
    draw = ImageDraw.Draw(mask)
    box = (0, 0, size * scale - 1, size * scale - 1)
    if kind == "circle":
        draw.ellipse(box, fill=255)
    else:
        draw.rounded_rectangle(box, radius=round(size * 0.175 * scale), fill=255)
    return mask.resize((size, size), Image.Resampling.LANCZOS)


def platform_tile(role, size, kind="rounded"):
    image = resize(master(role), size)
    image.putalpha(shape_mask(size, kind))
    return image


def android_foreground(role, size, artwork_scale=0.72):
    """Place the complete approved bitmap in the adaptive-icon safe area.

    The V is intentionally not extracted. Its original background remains part
    of the artwork and blends into the matching adaptive background layer.
    """
    artwork_size = round(size * artwork_scale)
    artwork = resize(master(role), artwork_size)
    artwork.putalpha(shape_mask(artwork_size))
    result = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    offset = (size - artwork_size) // 2
    result.alpha_composite(artwork, (offset, offset))
    return result


def android_launcher(role, size, kind="rounded"):
    image = Image.new("RGBA", (size, size), BACKGROUND_COLORS[role])
    image.alpha_composite(android_foreground(role, size))
    image.putalpha(shape_mask(size, kind))
    return image


def save_webp(image, path):
    temporary = path.with_name(path.stem + ".tmp.webp")
    image.save(temporary, "WEBP", lossless=True)
    temporary.replace(path)


def save_android(role, base):
    for density, legacy_size in DENSITIES.items():
        directory = base / f"mipmap-{density}"
        directory.mkdir(parents=True, exist_ok=True)
        save_webp(android_launcher(role, legacy_size), directory / "ic_launcher.webp")
        save_webp(android_launcher(role, legacy_size, "circle"), directory / "ic_launcher_round.webp")
        adaptive_size = round(108 * legacy_size / 48)
        save_webp(android_foreground(role, adaptive_size), directory / "ic_launcher_foreground.webp")
        Image.new("RGB", (adaptive_size, adaptive_size), BACKGROUND_COLORS[role]).save(
            directory / "ic_launcher_background.webp", "WEBP", lossless=True
        )
    android_launcher(role, 512).save(base.parent / "ic_launcher-playstore.png", "PNG", optimize=True)


def main():
    windows = platform_tile("windows", 1024)
    icon_dir = ROOT / "icon"
    icon_dir.mkdir(exist_ok=True)
    windows.save(icon_dir / "visionguard-windows.png", "PNG", optimize=True)
    windows.save(icon_dir / "favicon.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    for target in [ROOT / "detector/windows-winforms/favico3n.ico", ROOT / "detector/windows-wpf/favico3n.ico", ROOT / "detector/windows-wpf/favicon.ico"]:
        windows.save(target, sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    save_android("android_detector", ROOT / "detector/android/app/src/main/res")
    save_android("android_receiver", ROOT / "receiver/android/app/src/main/res")
    platform_tile("server_web", 512).save(icon_dir / "visionguard-server-web.png", "PNG", optimize=True)


if __name__ == "__main__":
    main()
