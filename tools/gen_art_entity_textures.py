"""Original procedural painted materials, no downloaded assets; pip install pillow numpy."""
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, ImageFilter
OUT = Path(__file__).resolve().parents[1] / 'src/main/resources/assets/zhushenspace/textures/entity/art'
OUT.mkdir(parents=True, exist_ok=True)

# Animated equirectangular flame material: coherent large tongues + folded hot seams.
w,h,frames=384,192,8
u,v=np.meshgrid(np.linspace(0,2*np.pi,w),np.linspace(0,np.pi,h))
fire=[]; wave=[]
for frame in range(frames):
    t=frame/frames*2*np.pi
    field=(np.sin(u*5+np.sin(v*7-t)*1.8+t)+.55*np.sin(u*11-v*13+np.sin(u*3+t))+.25*np.sin(u*23+v*21-t*2))/1.8
    heat=np.clip(.54+field*.38+.13*np.sin(v*4+u*3-t),0,1)
    # Deep red pockets through saturated orange to yellow-white folds.
    rgb=np.stack([235+20*heat, 35+210*heat**1.7, 8+170*heat**4],axis=-1)
    fire.append(np.dstack((rgb,np.full((h,w),255))).astype('uint8'))
    flow=np.sin(u*7+v*12-np.sin(v*5-t)*2-t*2)
    hot=np.clip((flow*.5+.5)**5,0,1)
    waves=np.stack([80+170*hot, 180+75*hot, 225+30*hot],axis=-1)
    wave.append(np.dstack((waves,np.full((h,w),245))).astype('uint8'))
Image.fromarray(np.concatenate(fire)).save(OUT/'fire.png')
Image.fromarray(np.concatenate(wave)).save(OUT/'wave.png')

# Deliberate blade sweep, not a magic circle: tapered crescent silhouette with brush streaks.
S=768
im=Image.new('RGBA',(S,S));d=ImageDraw.Draw(im)
pts=[]
for t in np.linspace(-1,1,200): pts.append((S*(.12+.72*(1-t*t)), S*(.5+.43*t)))
for t in np.linspace(1,-1,200): pts.append((S*(.12+.49*(1-t*t)), S*(.5+.43*t)))
d.polygon(pts,fill=(175,245,218,230))
for k in range(10):
    pts=[(S*(.12+(.7-k*.013)*(1-t*t)),S*(.5+.43*t)) for t in np.linspace(-.99,.99,150)]
    d.line(pts, fill=(230,255,245,max(20,235-k*20)),width=max(1,6-k//2))
glow=im.filter(ImageFilter.GaussianBlur(7));Image.alpha_composite(glow,im).resize((512,512),Image.Resampling.LANCZOS).save(OUT/'slash.png')

# A real Bagua seal: eight distinct trigrams and a yin-yang center, transparent negative space.
im=Image.new('RGBA',(S,S));d=ImageDraw.Draw(im)
col=(230,255,250,235);cx=cy=S/2
for radius,width in [(341,4),(330,2),(180,3),(167,2)]:
    d.ellipse((cx-radius,cy-radius,cx+radius,cy+radius),outline=col,width=width)
# Each trigram, ordered around the seal; full and broken strokes are visibly different.
for i,bits in enumerate([7,3,5,1,0,4,2,6]):
    layer=Image.new('RGBA',(S,S));ld=ImageDraw.Draw(layer)
    for j in range(3):
        y=90+j*22
        if bits & (1<<j): ld.rounded_rectangle((cx-48,y,cx+48,y+11),radius=1,fill=col)
        else:
            ld.rectangle((cx-48,y,cx-10,y+11),fill=col);ld.rectangle((cx+10,y,cx+48,y+11),fill=col)
    im=Image.alpha_composite(im,layer.rotate(-i*45,resample=Image.Resampling.BICUBIC))
d=ImageDraw.Draw(im);r=115
# Distinct light/dark lobes, with transparent/dark negative space.
d.ellipse((cx-r,cy-r,cx+r,cy+r),fill=(25,55,60,165),outline=col,width=3)
d.pieslice((cx-r,cy-r,cx+r,cy+r),90,270,fill=col)
d.ellipse((cx-r/2,cy-r,cx+r/2,cy),fill=col)
d.ellipse((cx-r/2,cy,cx+r/2,cy+r),fill=(25,55,60,210))
d.ellipse((cx-12,cy-r/2-12,cx+12,cy-r/2+12),fill=(25,55,60,230))
d.ellipse((cx-12,cy+r/2-12,cx+12,cy+r/2+12),fill=col)
im.resize((512,512),Image.Resampling.LANCZOS).save(OUT/'bagua.png')
print('Generated 4 entity materials (2 animated sheets).')
