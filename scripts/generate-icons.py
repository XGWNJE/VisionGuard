from pathlib import Path
from collections import deque
from PIL import Image, ImageDraw, ImageFilter

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
    "android_detector": "#F7F7F5",
    "android_receiver": "#D9272E",
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


def foreground(role, size, monochrome=False):
    source = resize(master(role), size).convert("RGBA")
    pixels = source.load()
    alpha = Image.new("L", source.size, 0)
    out = alpha.load()
    for y in range(size):
        for x in range(size):
            r, g, b, _ = pixels[x, y]
            red = r > 170 and r > g * 1.55 and r > b * 1.35
            dark = max(r, g, b) < 105
            light = min(r, g, b) > 242 and max(r, g, b) - min(r, g, b) < 16
            if role == "android_detector":
                keep = red or dark
            elif role == "android_receiver":
                keep = dark or light
            else:
                keep = red or dark or light
            out[x, y] = 255 if keep else 0
    visited = set()
    keep_components = []
    for y in range(size):
        for x in range(size):
            if out[x, y] == 0 or (x, y) in visited:
                continue
            queue = deque([(x, y)])
            visited.add((x, y))
            component = []
            touches_edge = False
            while queue:
                px, py = queue.popleft()
                component.append((px, py))
                touches_edge |= px == 0 or py == 0 or px == size - 1 or py == size - 1
                for nx, ny in ((px - 1, py), (px + 1, py), (px, py - 1), (px, py + 1)):
                    if 0 <= nx < size and 0 <= ny < size and out[nx, ny] and (nx, ny) not in visited:
                        visited.add((nx, ny))
                        queue.append((nx, ny))
            if not touches_edge and len(component) >= size * size * 0.004:
                keep_components.append(component)
    alpha = Image.new("L", source.size, 0)
    out = alpha.load()
    for component in keep_components:
        for x, y in component:
            out[x, y] = 255
    alpha = alpha.filter(ImageFilter.GaussianBlur(max(0.35, size / 900)))
    if monochrome:
        result = Image.new("RGBA", source.size, (255, 255, 255, 0))
    else:
        result = source
    result.putalpha(alpha)
    return result


def save_webp(image, path):
    temporary = path.with_name(path.stem + ".tmp.webp")
    image.save(temporary, "WEBP", lossless=True)
    temporary.replace(path)


def save_android(role, base):
    for density, legacy_size in DENSITIES.items():
        directory = base / f"mipmap-{density}"
        directory.mkdir(parents=True, exist_ok=True)
        save_webp(platform_tile(role, legacy_size), directory / "ic_launcher.webp")
        save_webp(platform_tile(role, legacy_size, "circle"), directory / "ic_launcher_round.webp")
        adaptive_size = round(108 * legacy_size / 48)
        save_webp(foreground(role, adaptive_size), directory / "ic_launcher_foreground.webp")
        save_webp(foreground(role, adaptive_size, monochrome=True), directory / "ic_launcher_monochrome.webp")
        Image.new("RGB", (adaptive_size, adaptive_size), BACKGROUND_COLORS[role]).save(
            directory / "ic_launcher_background.webp", "WEBP", lossless=True
        )
    platform_tile(role, 512).save(base.parent / "ic_launcher-playstore.png", "PNG", optimize=True)


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
