"""Mechanically export one shared brand bitmap to all platform formats."""
from pathlib import Path
from PIL import Image, ImageDraw
ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "icon/visionguard-icon-master.png"
DENSITIES = {"mdpi":48,"hdpi":72,"xhdpi":96,"xxhdpi":144,"xxxhdpi":192}
BACKGROUND = "#1B1B1B"
ICO_SIZES = [(s,s) for s in (16,24,32,48,64,128,256)]
def artwork(size):
    with Image.open(SOURCE) as source:
        return source.convert("RGBA").resize((size,size),Image.Resampling.LANCZOS)
def foreground(size):
    result=Image.new("RGBA",(size,size))
    inner=round(size*0.72)
    result.alpha_composite(artwork(inner),((size-inner)//2,)*2)
    return result
def launcher(size,circle=False):
    result=Image.new("RGBA",(size,size),BACKGROUND)
    result.alpha_composite(foreground(size))
    mask=Image.new("L",(size*4,size*4))
    draw=ImageDraw.Draw(mask); bounds=(0,0,size*4-1,size*4-1)
    if circle: draw.ellipse(bounds,fill=255)
    else: draw.rounded_rectangle(bounds,radius=round(size*0.175*4),fill=255)
    result.putalpha(mask.resize((size,size),Image.Resampling.LANCZOS))
    return result
def main():
    icon=launcher(1024)
    for name in ("visionguard-windows.png","visionguard-server-web.png"):
        icon.save(ROOT/"icon"/name,optimize=True)
    for name in ("icon/favicon.ico","detector/windows-wpf/favico3n.ico"):
        icon.save(ROOT/name,sizes=ICO_SIZES)
    public=ROOT/"receiver/web/public"; public.mkdir(parents=True,exist_ok=True)
    icon.resize((192,192),Image.Resampling.LANCZOS).save(public/"icon.png")
    icon.save(public/"favicon.ico",sizes=ICO_SIZES)
    for component in ("detector","receiver","notifier"):
        base=ROOT/component/"android/app/src/main/res"
        for density,size in DENSITIES.items():
            directory=base/f"mipmap-{density}"; directory.mkdir(parents=True,exist_ok=True)
            launcher(size).save(directory/"ic_launcher.webp",lossless=True)
            launcher(size,True).save(directory/"ic_launcher_round.webp",lossless=True)
            adaptive=round(108*size/48)
            foreground(adaptive).save(directory/"ic_launcher_foreground.webp",lossless=True)
            Image.new("RGB",(adaptive,adaptive),BACKGROUND).save(directory/"ic_launcher_background.webp",lossless=True)
        launcher(512).save(base.parent/"ic_launcher-playstore.png",optimize=True)
if __name__ == "__main__": main()
