from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]

DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

SOURCE = ROOT / "icon" / "visionguard-icon-master.png"
BOXES = {
    "windows": (112, 108, 595, 596),
    "android_detector": (657, 108, 1140, 596),
    "android_receiver": (112, 660, 595, 1148),
    "server_web": (657, 660, 1140, 1148),
}


def master(role):
    with Image.open(SOURCE) as source:
        return source.convert("RGBA").crop(BOXES[role])


def resize(image, size):
    return image.resize((size, size), Image.Resampling.LANCZOS)


def save_webp(image, path):
    temporary = path.with_name(path.stem + ".tmp.webp")
    image.save(temporary, "WEBP", lossless=True)
    temporary.replace(path)


def save_android(role, base):
    image = master(role)
    for density, legacy_size in DENSITIES.items():
        directory = base / f"mipmap-{density}"
        directory.mkdir(parents=True, exist_ok=True)
        save_webp(resize(image, legacy_size), directory / "ic_launcher.webp")
        save_webp(resize(image, legacy_size), directory / "ic_launcher_round.webp")
        adaptive_size = round(108 * legacy_size / 48)
        save_webp(resize(image, adaptive_size), directory / "ic_launcher_foreground.webp")
        save_webp(resize(image.convert("L").convert("RGBA"), adaptive_size), directory / "ic_launcher_monochrome.webp")
    resize(image, 512).convert("RGB").save(base.parent / "ic_launcher-playstore.png", "PNG", optimize=True)


def main():
    windows = resize(master("windows"), 1024)
    icon_dir = ROOT / "icon"
    icon_dir.mkdir(exist_ok=True)
    windows.save(icon_dir / "visionguard-windows.png", "PNG", optimize=True)
    windows.save(icon_dir / "favicon.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    for target in [ROOT / "detector/windows-winforms/favico3n.ico", ROOT / "detector/windows-wpf/favico3n.ico", ROOT / "detector/windows-wpf/favicon.ico"]:
        windows.save(target, sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    save_android("android_detector", ROOT / "detector/android/app/src/main/res")
    save_android("android_receiver", ROOT / "receiver/android/app/src/main/res")
    resize(master("server_web"), 512).save(icon_dir / "visionguard-server-web.png", "PNG", optimize=True)


if __name__ == "__main__":
    main()
