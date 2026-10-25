// Copyright (C) 2026 Toit contributors.
#include "raster.h"
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <filesystem>
#include <stdexcept>
#include <unistd.h>
#include <zlib.h>

namespace luma {
namespace {
constexpr size_t headerSize=32;
void io(bool success) {if(!success) throw std::runtime_error("Raster/PNG file operation failed (check free storage)");}
void put32(uint8_t* p,uint32_t v) {for(int i=0;i<4;i++) p[i]=uint8_t(v>>(i*8));}
uint32_t get32(const uint8_t* p) {uint32_t v=0;for(int i=0;i<4;i++) v|=uint32_t(p[i])<<(i*8);return v;}
std::array<uint8_t,headerSize> header(int w,int h,int rows) {
    std::array<uint8_t,headerSize> b{};std::memcpy(b.data(),"LUMARGB1",8);
    put32(b.data()+8,w);put32(b.data()+12,h);put32(b.data()+16,3);put32(b.data()+20,rows);
    uint64_t bytes=uint64_t(w)*h*3;put32(b.data()+24,uint32_t(bytes));put32(b.data()+28,uint32_t(bytes>>32));return b;
}
void writeHeader(FILE* file,int w,int h,int rows) {auto b=header(w,h,rows);io(fseeko(file,0,SEEK_SET)==0);io(fwrite(b.data(),1,b.size(),file)==b.size());}
void parse(FILE* f,int& w,int& h,int& rows,bool complete) {
    uint8_t b[headerSize];io(fread(b,1,sizeof(b),f)==sizeof(b));
    if(std::memcmp(b,"LUMARGB1",8)!=0 || get32(b+16)!=3) throw std::invalid_argument("Unsupported raster header");
    uint32_t ww=get32(b+8),hh=get32(b+12),rr=get32(b+20);
    if(ww>16384 || hh>16384) throw std::invalid_argument("Raster dimensions exceed limit");
    w=int(ww);h=int(hh);rows=int(rr);checkRasterDimensions(w,h);
    uint64_t bytes=uint64_t(w)*h*3,declared=uint64_t(get32(b+24))|(uint64_t(get32(b+28))<<32);
    if(declared!=bytes || rr>hh || (complete && rr!=hh)) throw std::invalid_argument("Incomplete or corrupt raster");
    io(fseeko(f,0,SEEK_END)==0);auto size=ftello(f);
    if(size<0 || uint64_t(size)!=headerSize+uint64_t(w)*rows*3) throw std::invalid_argument("Raster file size does not match its header");
}
void be32(uint8_t* p,uint32_t v) {p[0]=v>>24;p[1]=v>>16;p[2]=v>>8;p[3]=v;}
float linear(uint8_t v) {float s=v/255.f;return s<=.04045f?s/12.92f:std::pow((s+.055f)/1.055f,2.4f);}
uint8_t encode(float v) {v=std::clamp(v,0.f,1.f);float s=v<=.0031308f?12.92f*v:1.055f*std::pow(v,1.f/2.4f)-.055f;return uint8_t(std::clamp(int(std::lround(255*s)),0,255));}
std::array<float,4> cubicWeights(float t) {
    float t2=t*t,t3=t2*t;
    return {-.5f*t+t2-.5f*t3,1-2.5f*t2+1.5f*t3,.5f*t+2*t2-1.5f*t3,-.5f*t2+.5f*t3};
}
// Interpolate existing samples, without a sharpening/deconvolution pass. The
// nearest 2x2 range bounds the result, including at high-contrast print edges.
// A correction must exceed both quantization and local noise before it can
// replace bilinear interpolation; soft detail and smooth noisy areas retain
// the original interpolation exactly. The four separable second-difference
// responses have white-noise standard deviation 6*sigma; their median absolute
// value is .67449 times that standard deviation for Gaussian noise.
float interpolate(const Image& image,int x,int y,int c,float bilinear,
                  const std::array<float,4>& wx,const std::array<float,4>& wy,
                  const std::array<float,256>& lut) {
    auto code=[&](int dx,int dy) {
        int xx=std::clamp(x+dx,0,image.width-1),yy=std::clamp(y+dy,0,image.height-1);
        return image.rgb[(size_t(yy)*image.width+xx)*3+c];
    };
    uint8_t a=code(0,0),b=code(1,0),d=code(0,1),e=code(1,1);
    float low=std::min({lut[a],lut[b],lut[d],lut[e]}),high=std::max({lut[a],lut[b],lut[d],lut[e]});
    if(high==low) return bilinear;
    int brightest=std::max({a,b,d,e});
    float floor=.375f*(lut[std::min(255,brightest+1)]-lut[std::max(0,brightest-1)]);
    float values[4][4],cubic=0;
    for(int j=0;j<4;j++) for(int i=0;i<4;i++) {
        values[j][i]=lut[code(i-1,j-1)];cubic+=wy[j]*wx[i]*values[j][i];
    }
    cubic=std::clamp(cubic,low,high);
    float correction=cubic-bilinear;
    if(std::abs(correction)<=floor) return bilinear;
    std::array<float,4> responses{};
    for(int j=0;j<2;j++) for(int i=0;i<2;i++)
        responses[j*2+i]=std::abs(values[j][i]-2*values[j][i+1]+values[j][i+2]
            -2*values[j+1][i]+4*values[j+1][i+1]-2*values[j+1][i+2]
            +values[j+2][i]-2*values[j+2][i+1]+values[j+2][i+2]);
    std::sort(responses.begin(),responses.end());
    float sigma=(responses[1]+responses[2])*.5f/(6*.67448975f);
    float threshold=std::max(floor,2.5f*sigma);
    float amount=std::clamp((std::abs(correction)-threshold)/std::max(threshold,.0001f),0.f,1.f);
    return bilinear+amount*correction;
}
}
void checkRasterDimensions(int w,int h) {
    if(w<=0 || h<=0 || w>16384 || h>16384 || uint64_t(w)*h>maxRasterPixels) throw std::invalid_argument("Raster exceeds 50.4MP or 16384 pixels per side");
}
std::string temporaryPath(const std::string& target) {
    static std::atomic<uint64_t> serial{0};
    return target+".tmp-"+std::to_string(getpid())+"-"+std::to_string(++serial);
}
RasterReader::RasterReader(const std::string& path) {
    file=fopen(path.c_str(),"rb");if(!file) throw std::runtime_error("Cannot open raster");
    try {int rows;parse(file,width,height,rows,true);} catch(...) {fclose(file);file=nullptr;throw;}
}
RasterReader::~RasterReader() {if(file) fclose(file);}
const uint8_t* RasterReader::row(int y) {
    if(y<0 || y>=height) throw std::invalid_argument("Raster row out of bounds");
    auto& entry=cache[size_t(y)%cache.size()];
    if(entry.y!=y) {
        entry.pixels.resize(size_t(width)*3);
        io(fseeko(file,off_t(headerSize+uint64_t(y)*width*3),SEEK_SET)==0);
        io(fread(entry.pixels.data(),1,entry.pixels.size(),file)==entry.pixels.size());entry.y=y;
    }
    return entry.pixels.data();
}
Image RasterReader::readImage() {Image im(width,height);for(int y=0;y<height;y++) std::memcpy(im.rgb.data()+size_t(y)*width*3,row(y),size_t(width)*3);return im;}
Image RasterReader::readRegion(int x,int y,int w,int h) {
    if(x<0 || y<0 || w<=0 || h<=0 || x>width-w || y>height-h) throw std::invalid_argument("Raster region out of bounds");
    Image image(w,h);
    for(int j=0;j<h;j++) {
        io(fseeko(file,off_t(headerSize+(uint64_t(y+j)*width+x)*3),SEEK_SET)==0);
        io(fread(image.rgb.data()+size_t(j)*w*3,3,w,file)==size_t(w));
    }
    return image;
}

RasterWriter::RasterWriter(const std::string& path,int w,int h):target(path),temp(temporaryPath(path)),width(w),height(h) {
    checkRasterDimensions(w,h);file=fopen(temp.c_str(),"wbx");if(!file) throw std::runtime_error("Cannot create raster temporary file");
    try {writeHeader(file,w,h,0);} catch(...) {fclose(file);file=nullptr;std::remove(temp.c_str());throw;}
}
RasterWriter::~RasterWriter() {if(file) fclose(file);if(!temp.empty()) std::remove(temp.c_str());}
void RasterWriter::row(const uint8_t* rgb) {if(!file || rows>=height) throw std::runtime_error("Too many raster rows");io(fwrite(rgb,3,width,file)==size_t(width));rows++;}
void RasterWriter::finish() {
    if(!file || rows!=height) throw std::runtime_error("Incomplete raster write");
    writeHeader(file,width,height,rows);io(fflush(file)==0);int result=fclose(file);file=nullptr;io(result==0);
    io(std::rename(temp.c_str(),target.c_str())==0);temp.clear();
}
struct PngWriter::State {
    std::string target,temp;FILE* file=nullptr;z_stream stream{};bool initialized=false;int width,height,rows=0;
    std::vector<uint8_t> filtered;std::array<uint8_t,65536> compressed{};
    void chunk(const char* name,const uint8_t* data,size_t bytes) {
        uint8_t b[4];be32(b,uint32_t(bytes));io(fwrite(b,1,4,file)==4);io(fwrite(name,1,4,file)==4);
        if(bytes) io(fwrite(data,1,bytes,file)==bytes);
        uLong crc=crc32(0,reinterpret_cast<const Bytef*>(name),4);if(bytes) crc=crc32(crc,data,uInt(bytes));be32(b,uint32_t(crc));io(fwrite(b,1,4,file)==4);
    }
    void deflateBytes(int flush) {
        int result;
        do {
            stream.next_out=compressed.data();stream.avail_out=compressed.size();result=deflate(&stream,flush);
            if(result!=Z_OK && result!=Z_STREAM_END) throw std::runtime_error("PNG compression failed");
            size_t bytes=compressed.size()-stream.avail_out;if(bytes) chunk("IDAT",compressed.data(),bytes);
        } while(stream.avail_in || (flush==Z_FINISH && result!=Z_STREAM_END));
    }
    ~State() {if(initialized) deflateEnd(&stream);if(file) fclose(file);if(!temp.empty()) std::remove(temp.c_str());}
};
PngWriter::PngWriter(const std::string& path,int w,int h):state(new State) {
    checkRasterDimensions(w,h);auto& s=*state;s.target=path;s.temp=temporaryPath(path);s.width=w;s.height=h;s.filtered.resize(size_t(w)*3+1);
    s.file=fopen(s.temp.c_str(),"wbx");if(!s.file) throw std::runtime_error("Cannot create PNG temporary file");
    const uint8_t signature[8]={137,80,78,71,13,10,26,10};io(fwrite(signature,1,8,s.file)==8);
    uint8_t ihdr[13]{};be32(ihdr,w);be32(ihdr+4,h);ihdr[8]=8;ihdr[9]=2;s.chunk("IHDR",ihdr,13);
    const uint8_t intent=0;s.chunk("sRGB",&intent,1);
    if(deflateInit(&s.stream,3)!=Z_OK) throw std::runtime_error("Cannot initialize PNG compression");
    s.initialized=true;
}
PngWriter::~PngWriter()=default;
void PngWriter::row(const uint8_t* rgb) {
    auto& s=*state;if(!s.file || s.rows>=s.height) throw std::runtime_error("Too many PNG rows");
    // Sub filtering preserves exact RGB and compresses smooth photographs efficiently.
    s.filtered[0]=1;for(int i=0;i<s.width*3;i++) s.filtered[size_t(i)+1]=uint8_t(rgb[i]-(i>=3?rgb[i-3]:0));
    s.stream.next_in=s.filtered.data();s.stream.avail_in=uInt(s.filtered.size());s.deflateBytes(Z_NO_FLUSH);s.rows++;
}
void PngWriter::finish() {
    auto& s=*state;if(!s.file || s.rows!=s.height) throw std::runtime_error("Incomplete PNG write");
    s.stream.next_in=nullptr;s.stream.avail_in=0;s.deflateBytes(Z_FINISH);s.chunk("IEND",nullptr,0);
    io(fflush(s.file)==0);int result=fclose(s.file);s.file=nullptr;io(result==0);io(std::rename(s.temp.c_str(),s.target.c_str())==0);s.temp.clear();
}
void appendRasterRows(const std::string& path,const Image& strip,int top,int totalHeight) {
    checkRasterDimensions(strip.width,totalHeight);
    if(strip.height<=0 || top<0 || top>totalHeight-strip.height || strip.rgb.size()!=size_t(strip.width)*strip.height*3) throw std::invalid_argument("Invalid raster strip");
    FILE* f=fopen(path.c_str(),top==0?"wb":"r+b");if(!f) throw std::runtime_error("Cannot open raster strip destination");
    try {
        if(top==0) writeHeader(f,strip.width,totalHeight,0);
        else {int w,h,rows;parse(f,w,h,rows,false);if(w!=strip.width || h!=totalHeight || rows!=top) throw std::invalid_argument("Raster strips are out of order");}
        io(fseeko(f,off_t(headerSize+uint64_t(top)*strip.width*3),SEEK_SET)==0);
        io(fwrite(strip.rgb.data(),1,strip.rgb.size(),f)==strip.rgb.size());writeHeader(f,strip.width,totalHeight,top+strip.height);
        io(fflush(f)==0);int result=fclose(f);f=nullptr;io(result==0);
    } catch(...) {if(f) fclose(f);throw;}
}
std::array<int,2> exportRaster(const std::string& raster,const std::string& png,const std::array<Point,4>& normalized,double ratio,const std::array<float,3>& gains) {
    if(raster==png) throw std::invalid_argument("Source raster and exported PNG paths must differ");
    RasterReader source(raster);std::vector<Point> points;
    for(auto p:normalized) {
        if(!std::isfinite(p.x) || !std::isfinite(p.y) || p.x<0 || p.x>1 || p.y<0 || p.y>1) throw std::invalid_argument("Corners must be normalized to 0..1");
        points.push_back({p.x*(source.width-1),p.y*(source.height-1)});
    }
    double sign=0,area=0;
    for(int i=0;i<4;i++) {
        auto a=points[i],b=points[(i+1)%4],c=points[(i+2)%4];double cross=(b.x-a.x)*(c.y-b.y)-(b.y-a.y)*(c.x-b.x);
        if(std::abs(cross)<1 || (sign && cross*sign<0)) throw std::invalid_argument("Corners must form a convex ordered quadrilateral");
        sign=cross;area+=a.x*b.y-a.y*b.x;
    }
    if(std::abs(area)<32) throw std::invalid_argument("Selected area is too small");
    auto distance=[](Point a,Point b){return std::hypot(a.x-b.x,a.y-b.y);};
    double w=(distance(points[0],points[1])+distance(points[3],points[2]))*.5+1;
    double h=(distance(points[0],points[3])+distance(points[1],points[2]))*.5+1;
    if(!std::isfinite(ratio) || ratio<0 || ratio>100 || (ratio>0 && ratio<.01)) throw std::invalid_argument("Invalid output aspect ratio");
    if(ratio>0) {double a=w*h;w=std::sqrt(a*ratio);h=w/ratio;}
    double shrink=std::min({1.,16384/std::max(w,h),std::sqrt(double(maxRasterPixels)/(w*h))});
    int width=std::max(2,int(std::floor(w*shrink))),height=std::max(2,int(std::floor(h*shrink)));checkRasterDimensions(width,height);
    for(float g:gains) if(!std::isfinite(g) || g<.1f || g>10) throw std::invalid_argument("White balance gains must be in 0.1..10");
    std::vector<Point> output{{0,0},{double(width-1),0},{double(width-1),double(height-1)},{0,double(height-1)}};Homography map;
    if(!fit(output,points,map)) throw std::invalid_argument("Cannot fit perspective correction");
    std::array<float,256> lut{};for(int i=0;i<256;i++) lut[i]=linear(uint8_t(i));
    PngWriter writer(png,width,height);
    // A bounded output band and source tiles also make quarter-turn exports linear
    // in image size. Sampling an entire source scanline per output pixel would
    // repeatedly read the full raster when x and y axes are exchanged.
    constexpr int bandHeight=64,tileWidth=64;
    std::vector<uint8_t> band(size_t(width)*bandHeight*3);
    std::function<void(int,int,int,int,int)> tile;
    tile=[&](int left,int top,int tw,int th,int bandTop) {
        double minX=source.width-1,minY=source.height-1,maxX=0,maxY=0;
        for(int yy:{top,top+th-1}) for(int xx:{left,left+tw-1}) {
            auto p=project(map,{double(xx),double(yy)});
            if(!std::isfinite(p.x) || !std::isfinite(p.y)) throw std::invalid_argument("Unbounded perspective transform");
            double x=std::clamp(p.x,0.,double(source.width-1)),y=std::clamp(p.y,0.,double(source.height-1));
            minX=std::min(minX,x);maxX=std::max(maxX,x);minY=std::min(minY,y);maxY=std::max(maxY,y);
        }
        int x0=std::max(0,int(std::floor(minX))-1),y0=std::max(0,int(std::floor(minY))-1);
        int x1=std::min(source.width-1,int(std::floor(maxX))+2),y1=std::min(source.height-1,int(std::floor(maxY))+2);
        if(uint64_t(x1-x0+1)*(y1-y0+1)*3>4*1024*1024 && (tw>1 || th>1)) {
            if(tw>=th && tw>1) {int a=tw/2;tile(left,top,a,th,bandTop);tile(left+a,top,tw-a,th,bandTop);}
            else {int a=th/2;tile(left,top,tw,a,bandTop);tile(left,top+a,tw,th-a,bandTop);}
            return;
        }
        auto region=source.readRegion(x0,y0,x1-x0+1,y1-y0+1);
        for(int y=top;y<top+th;y++) for(int x=left;x<left+tw;x++) {
            Point p=project(map,{double(x),double(y)});
            double sx=std::clamp(p.x,0.,double(source.width-1)),sy=std::clamp(p.y,0.,double(source.height-1));
            int ix=int(sx),iy=int(sy),nx=std::min(ix+1,source.width-1),ny=std::min(iy+1,source.height-1);
            double fx=sx-ix,fy=sy-iy;
            auto wx=cubicWeights(float(fx)),wy=cubicWeights(float(fy));
            const uint8_t* a=region.rgb.data()+size_t(iy-y0)*region.width*3;
            const uint8_t* b=region.rgb.data()+size_t(ny-y0)*region.width*3;
            for(int c=0;c<3;c++) {
                float value=float((1-fy)*((1-fx)*lut[a[3*(ix-x0)+c]]+fx*lut[a[3*(nx-x0)+c]])+fy*((1-fx)*lut[b[3*(ix-x0)+c]]+fx*lut[b[3*(nx-x0)+c]]));
                value=interpolate(region,ix-x0,iy-y0,c,value,wx,wy,lut);
                band[(size_t(y-bandTop)*width+x)*3+c]=encode(value*gains[c]);
            }
        }
    };
    for(int y=0;y<height;y+=bandHeight) {
        int rows=std::min(bandHeight,height-y);
        for(int x=0;x<width;x+=tileWidth) tile(x,y,std::min(tileWidth,width-x),rows,y);
        for(int j=0;j<rows;j++) writer.row(band.data()+size_t(j)*width*3);
    }
    writer.finish();return {width,height};
}
} // namespace luma
