#!/usr/bin/env python3
# Copyright (C) 2026 Toit contributors.
"""Independent linear-light export tests: known text/color truth, blur, noise, geometry.

Requires NumPy, Pillow, DejaVu Serif, a C++17 compiler and zlib. Outputs64 bounded fixtures and
metrics in build/export-quality. The baseline is independently computed bilinear
resampling; no previous implementation or private photographs are required.
These tests measure export interpolation, not an optical super-resolution gain.
"""
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont,ImageFilter
import numpy as np,json,subprocess
ROOT=Path(__file__).resolve().parents[1]
out=ROOT/'build/export-quality';out.mkdir(parents=True,exist_ok=True);W,H,S=384,288,6
rng=np.random.default_rng(20261021)
FONT=next((p for p in [Path('/usr/share/fonts/TTF/DejaVuSerif.ttf'),
 Path('/usr/share/fonts/truetype/dejavu/DejaVuSerif.ttf')] if p.is_file()),Path('DejaVuSerif.ttf'))
def encode(a):return np.uint8(np.clip(np.rint(np.where(a<=.0031308,12.92*a,1.055*np.maximum(a,0)**(1/2.4)-.055)*255),0,255))
lut=np.arange(256)/255;lut=np.where(lut<=.04045,lut/12.92,((lut+.055)/1.055)**2.4)
def sample(a,x,y):
 x=np.clip(x,0,a.shape[1]-1.00001);y=np.clip(y,0,a.shape[0]-1.00001);ix=x.astype(int);iy=y.astype(int);fx=(x-ix)[...,None];fy=(y-iy)[...,None]
 return (1-fy)*((1-fx)*a[iy,ix]+fx*a[iy,ix+1])+fy*((1-fx)*a[iy+1,ix]+fx*a[iy+1,ix+1])
def fit(a,b):
 m=[];v=[]
 for (x,y),(u,w) in zip(a,b):m.extend([[x,y,1,0,0,0,-u*x,-u*y],[0,0,0,x,y,1,-w*x,-w*y]]);v.extend([u,w])
 return np.append(np.linalg.solve(m,v),1).reshape(3,3)
def field(kind,sigma):
 if kind=='text':
  im=Image.new('L',(W*S,H*S),255);d=ImageDraw.Draw(im)
  for y,size in [(19,11),(60,17),(113,23),(180,13),(225,9)]:
   font=ImageFont.truetype(str(FONT),size*S)
   d.text((17*S,y*S),'Faithful 2468 BORDER',font=font,fill=0)
  mask=np.asarray(im.filter(ImageFilter.GaussianBlur(sigma*S)),dtype=float)/255
  return np.array([.02,.07,.16])+mask[:,:,None]*np.array([.71,.55,.39])
 y,x=np.mgrid[:H*S,:W*S]/S
 if kind=='color':
  a=[]
  for c in range(3):
   f1=.10+.025*c;f2=.035-.02*c;f3=.31-.03*c
   a.append(.4+.17*np.exp(-2*np.pi*np.pi*sigma*sigma*(f1*f1+f2*f2))*np.sin(2*np.pi*(f1*x+f2*y)+c)
    +.09*np.exp(-2*np.pi*np.pi*sigma*sigma*f3*f3)*np.sin(2*np.pi*f3*y+c*.7))
  return np.stack(a,-1)
 if kind=='gradient':return np.stack([.05+.6*x/W,.1+.45*y/H,.15+.2*x/W+.25*y/H],-1)
 return np.zeros((H*S,W*S,3))+np.array([.025,.20,.72])
warps={'perspective':np.array([[.035,.06],[.95,.025],[.975,.94],[.02,.965]]),'keystone':np.array([[.12,.08],[.9,.15],[.96,.91],[.04,.98]]),'rotation90':np.array([[.04,.95],[.04,.04],[.96,.04],[.96,.95]]),'identity':np.array([[0,0],[1,0],[1,1],[0,1]])}
fixtures=[];lines=[]
for kind in ['text','color','gradient','constant']:
 sigmas=[.55,1.1,2.8] if kind in ['text','color'] else [0]
 noises=[.0025,.007] if kind in ['text','color'] else [0,.007]
 for sigma in sigmas:
  latent=field(kind,sigma);y,x=np.mgrid[:H,:W];truth=sample(latent,x*S,y*S)
  for noise in noises:
   source=encode(np.clip(truth+rng.normal(0,noise,truth.shape),0,1));name=f'{kind}-{sigma}-{noise}'
   path=out/(name+'.ppm');Image.fromarray(source).save(path)
   for warp,coords in warps.items():
    output=name+'-'+warp+'.png';gains=np.array([1.04,.97,1.1]) if kind=='constant' else np.ones(3)
    lines.append(str(path)+' '+output+' 0 '+' '.join(map(str,gains))+' '+' '.join(map(str,coords.flatten()))+'\n')
    fixtures.append((name,kind,sigma,noise,warp,coords,latent,source,gains,output))
(out/'batch.txt').write_text(''.join(lines))
sources=[p for p in (ROOT/'app/src/main/cpp').glob('*.cpp') if p.name!='bridge.cpp']
exe=out/'export-fixtures'
subprocess.run(['g++','-std=c++17','-O3','-Wall','-Wextra','-I',str(ROOT/'app/src/main/cpp'),
    *map(str,sources),str(ROOT/'tests/export_quality.cpp'),'-lz','-o',str(exe)],check=True)
result=subprocess.run([str(exe),str(out/'batch.txt'),str(out/'candidate')],capture_output=True,text=True,check=True)
(out/'candidate-times.txt').write_text(result.stdout)
rows=[]
for name,kind,sigma,noise,warp,coords,latent,source,gains,output in fixtures:
 candidate=np.array(Image.open(out/'candidate'/output));h,w,_=candidate.shape
 map=fit(np.array([[0,0],[w-1,0],[w-1,h-1],[0,h-1]]),coords*np.array([W-1,H-1]));y,x=np.mgrid[:h,:w];p=np.stack([x,y,np.ones(x.shape)],-1)@map.T;x=p[:,:,0]/p[:,:,2];y=p[:,:,1]/p[:,:,2]
 truth=np.clip(sample(latent,x*S,y*S)*gains,0,1)
 baseline=encode(sample(lut[source],x,y)*gains)
 valid=np.ones((h,w),bool);valid[:5]=False;valid[-5:]=False;valid[:,:5]=False;valid[:,-5:]=False
 a=float(np.mean((lut[baseline][valid]-truth[valid])**2));b=float(np.mean((lut[candidate][valid]-truth[valid])**2))
 ix=np.minimum(x.astype(int),W-2);iy=np.minimum(y.astype(int),H-2);nearest=np.stack([source[iy,ix],source[iy,ix+1],source[iy+1,ix],source[iy+1,ix+1]])
 low=encode(lut[np.min(nearest,axis=0)]*gains);high=encode(lut[np.max(nearest,axis=0)]*gains)
 row={'kind':kind,'sigma':sigma,'noise':noise,'warp':warp,'bilinearMse':a,'candidateMse':b,'mseRatio':b/a if a else 1,'outsideNearestRange':int(np.sum((candidate<low)|(candidate>high))),'maxCodeChange':int(np.max(abs(candidate.astype(int)-baseline.astype(int)))),'changedFraction':float(np.mean(np.any(candidate!=baseline,axis=-1)))}
 rows.append(row)
(out/'metrics.json').write_text(json.dumps(rows,indent=2))
assert all(r['outsideNearestRange']==0 for r in rows),'Resampling introduced a new local extremum'
assert all(r['mseRatio']<=1.01 for r in rows),'Ground-truth error regressed by more than1%'
assert all(r['mseRatio']<=1.005 for r in rows if r['kind']=='constant'),'Flat-field noise regressed'
assert all(r['maxCodeChange']<=1 for r in rows if r['noise']==0 and r['kind'] in ['constant','gradient']), 'Color/linear-gradient change'
assert all(r['maxCodeChange']<=1 for r in rows if r['warp']=='identity'), 'Identity transform changed source samples'
text=[r for r in rows if r['kind']=='text' and r['sigma']==.55 and r['noise']==.0025 and r['warp']!='identity']
assert all(r['mseRatio']<.95 for r in text),'Resampling failed to preserve sharper text'
print(json.dumps({'cases':len(rows),'worstMseRatio':max(r['mseRatio'] for r in rows),
 'worstFlatFieldMseRatio':max(r['mseRatio'] for r in rows if r['kind']=='constant'),
 'textMseRatios':[r['mseRatio'] for r in text],'newExtrema':sum(r['outsideNearestRange'] for r in rows)}))
