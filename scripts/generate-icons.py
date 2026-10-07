"""Export the approved scanner brand bitmap to every application icon format.

Requires Pillow. Use --check to detect stale or missing exports without writing.
The editable SVG and its transparent PNG rendering live together in icon/.
"""
import argparse
from io import BytesIO
from pathlib import Path
import xml.etree.ElementTree as ET
from PIL import Image, ImageDraw
ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "icon/visionguard-icon-master.png"
DENSITIES = {"mdpi":48,"hdpi":72,"xhdpi":96,"xxhdpi":144,"xxxhdpi":192}
BACKGROUND = "#0F172A"
ICO_SIZES = [(s,s) for s in (16,24,32,48,64,128,256)]
def artwork(size):
    with Image.open(SOURCE) as source:
        return source.convert("RGBA").resize((size,size),Image.Resampling.LANCZOS)
def foreground(size,adaptive=False,monochrome=False):
    result=Image.new("RGBA",(size,size))
    # Android crops the 108dp layer to 72dp. Keep the entire mark, including
    # its scanner corners, inside the central 66dp diameter safe circle.
    inner=round(size*(0.5 if adaptive else 0.72))
    mark=artwork(inner)
    if monochrome:
        mask=mark.getchannel("A")
        mark=Image.new("RGBA",mark.size,"white")
        mark.putalpha(mask)
    result.alpha_composite(mark,((size-inner)//2,)*2)
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
def exports():
    icon=launcher(1024)
    for name in ("visionguard-windows.png","visionguard-server-web.png"):
        yield f"icon/{name}",icon,"PNG",{"optimize":True}
    yield "icon/visionguard-round.png",launcher(1024,True),"PNG",{"optimize":True}
    for name in ("icon/favicon.ico","icon/favico2n.ico","detector/windows-wpf/favico3n.ico","receiver/web/public/favicon.ico"):
        yield name,icon,"ICO",{"sizes":ICO_SIZES}
    for size in (16,24,32,48,64,128,192,256,512):
        yield f"icon/png/{size}.png",launcher(size),"PNG",{"optimize":True}
    yield "receiver/web/public/icon.png",launcher(192),"PNG",{"optimize":True}
    for component in ("detector","notifier"):
        base=f"{component}/android/app/src/main/res"
        for density,size in DENSITIES.items():
            directory=f"{base}/mipmap-{density}"
            yield f"{directory}/ic_launcher.webp",launcher(size),"WEBP",{"lossless":True}
            yield f"{directory}/ic_launcher_round.webp",launcher(size,True),"WEBP",{"lossless":True}
            adaptive=round(108*size/48)
            yield f"{directory}/ic_launcher_foreground.webp",foreground(adaptive,True),"WEBP",{"lossless":True}
            yield f"{directory}/ic_launcher_monochrome.webp",foreground(adaptive,True,True),"WEBP",{"lossless":True}
            yield f"{directory}/ic_launcher_background.webp",Image.new("RGB",(adaptive,adaptive),BACKGROUND),"WEBP",{"lossless":True}
        # Store listing assets have a fully opaque square background; launchers
        # apply their own masks. Do not bake transparent rounded corners here.
        store=Image.new("RGBA",(512,512),BACKGROUND)
        store.alpha_composite(foreground(512))
        yield f"{component}/android/app/src/main/ic_launcher-playstore.png",store,"PNG",{"optimize":True}

def same_pixels(actual,expected,format):
    with Image.open(actual) as left, Image.open(BytesIO(expected)) as right:
        if format=="ICO":
            if left.ico.sizes()!=right.ico.sizes():
                return False
            return all(left.ico.getimage(size).convert("RGBA").tobytes()==right.ico.getimage(size).convert("RGBA").tobytes() for size in right.ico.sizes())
        return left.size==right.size and left.convert("RGBA").tobytes()==right.convert("RGBA").tobytes()

def svg_exports():
    ET.register_namespace("", "http://www.w3.org/2000/svg")
    namespace="{http://www.w3.org/2000/svg}"
    source=ET.parse(ROOT/"icon/visionguard-icon.svg").getroot()
    for shape in ("app","round"):
        output=ET.Element(namespace+"svg",{"viewBox":"0 0 64 64","fill":"none"})
        ET.SubElement(output,namespace+"title").text="VisionGuard"
        if shape=="app":
            ET.SubElement(output,namespace+"rect",{"width":"64","height":"64","rx":"11.2","fill":BACKGROUND})
        else:
            ET.SubElement(output,namespace+"circle",{"cx":"32","cy":"32","r":"32","fill":BACKGROUND})
        group=ET.SubElement(output,namespace+"g",{"transform":"translate(8.96 8.96) scale(.72)"})
        for child in source:
            if child.tag!=namespace+"title":
                group.append(child)
        encoded=ET.tostring(output,encoding="utf-8")+b"\n"
        yield f"icon/visionguard-{shape}.svg",encoded
        if shape=="app":
            yield "receiver/web/public/icon.svg",encoded
    for variant in ("", "-light", "-mono"):
        source=ET.parse(ROOT/f"icon/visionguard-icon{variant}.svg").getroot()
        output=ET.Element(namespace+"svg",{"viewBox":"0 0 300 64","fill":"none"})
        ET.SubElement(output,namespace+"title").text="VisionGuard"
        group=ET.SubElement(output,namespace+"g")
        for child in source:
            if child.tag!=namespace+"title":
                group.append(child)
        ET.SubElement(output,namespace+"text",{"x":"78","y":"43","font-family":"Segoe UI, sans-serif","font-size":"34","font-weight":"700","letter-spacing":"-.5","fill":"#0F172A" if variant else "#E2E8F0"}).text="VisionGuard"
        yield f"icon/visionguard-wordmark{variant}.svg",ET.tostring(output,encoding="utf-8")+b"\n"

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check",action="store_true",help="Check exports without changing them")
    args=parser.parse_args()
    failures=[]
    count=0
    for relative,encoded in svg_exports():
        target=ROOT/relative
        if args.check:
            # Git may check SVG files out with CRLF on Windows.
            if not target.is_file() or target.read_text(encoding="utf-8").encode("utf-8")!=encoded:
                failures.append(relative)
        else:
            target.parent.mkdir(parents=True,exist_ok=True)
            target.write_bytes(encoded)
        count+=1
    for relative,image,format,options in exports():
        target=ROOT/relative
        buffer=BytesIO()
        image.save(buffer,format=format,**options)
        encoded=buffer.getvalue()
        if args.check:
            if not target.is_file() or not same_pixels(target,encoded,format):
                failures.append(relative)
        else:
            target.parent.mkdir(parents=True,exist_ok=True)
            target.write_bytes(encoded)
        count+=1
    if failures:
        raise SystemExit("Stale or missing brand exports:\n"+"\n".join(failures))
    print(f"{'Checked' if args.check else 'Exported'} {count} scanner brand assets")

if __name__ == "__main__":
    main()
