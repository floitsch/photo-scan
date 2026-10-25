// Copyright (C) 2026 Toit contributors.
#include "fusion.h"
#include "raster.h"
#include <filesystem>
#include <memory>
#include <cstdio>
#include <algorithm>
#include <cmath>
#include <limits>
#include <numeric>
#include <stdexcept>
#include <chrono>
#include <sstream>
#include <iomanip>
#include <locale>
#include <optional>

namespace luma {
namespace {
constexpr double epsilon = 1e-10;
double square(double x) { return x*x; }
void validate(const Image& im) {
    if (im.width < 32 || im.height < 32 || im.width > 8192 || im.height > 8192 ||
        int64_t(im.width)*im.height > 12600000 || im.rgb.size() != size_t(im.width)*im.height*3)
        throw std::invalid_argument("Frame must be 32..8192 pixels per side and at most 12.6 megapixels");
}
bool solve(double a[8][9], double out[8]) {
    for (int c=0;c<8;c++) {
        int pivot=c;
        for(int r=c+1;r<8;r++) if(std::abs(a[r][c])>std::abs(a[pivot][c])) pivot=r;
        if(std::abs(a[pivot][c])<epsilon) return false;
        for(int k=c;k<9;k++) std::swap(a[c][k],a[pivot][k]);
        double d=a[c][c]; for(int k=c;k<9;k++) a[c][k]/=d;
        for(int r=0;r<8;r++) if(r!=c) {
            d=a[r][c]; for(int k=c;k<9;k++) a[r][k]-=d*a[c][k];
        }
    }
    for(int c=0;c<8;c++) out[c]=a[c][8];
    return true;
}
Homography multiply(const Homography& a,const Homography& b) {
    Homography c{};
    for(int i=0;i<3;i++) for(int j=0;j<3;j++) for(int k=0;k<3;k++) c[i*3+j]+=a[i*3+k]*b[k*3+j];
    return c;
}
Gray down(const Gray& src) {
    Gray g; g.width=src.width/2; g.height=src.height/2; g.pixels.resize(size_t(g.width)*g.height);
    for(int y=0;y<g.height;y++) for(int x=0;x<g.width;x++) {
        double v=0;
        // Binomial smoothing reduces aliasing before decimation.
        const int w[3]={1,2,1};
        for(int j=-1;j<=1;j++) for(int i=-1;i<=1;i++)
            v+=w[i+1]*w[j+1]*src.at(2*x+i,2*y+j);
        g.pixels[size_t(y)*g.width+x]=float(v/16);
    }
    return g;
}
std::vector<Gray> pyramid(const Image& im) {
    Gray g; g.width=im.width; g.height=im.height; g.pixels.resize(size_t(im.width)*im.height);
    for(size_t p=0;p<g.pixels.size();p++) g.pixels[p]=float((.2126*im.rgb[3*p]+.7152*im.rgb[3*p+1]+.0722*im.rgb[3*p+2])/255);
    // Every level retains its exact factor-of-two relationship to the input.
    std::vector<Gray> result; result.push_back(std::move(g));
    while(std::min(result.back().width,result.back().height)>72) result.push_back(down(result.back()));
    return result;
}
double sharpness(const Gray& g) {
    double sum=0; size_t n=0;
    for(int y=2;y<g.height-2;y+=2) for(int x=2;x<g.width-2;x+=2) {
        double lap=4*g.at(x,y)-g.at(x-1,y)-g.at(x+1,y)-g.at(x,y-1)-g.at(x,y+1);
        sum+=lap*lap; n++;
    }
    return n ? 100000*sum/n : 0;
}
std::vector<Point> detect(const Gray& g) {
    struct Candidate { Point p; double score; };
    std::vector<Candidate> candidates;
    for(int cy=10;cy<g.height-10;cy+=24) for(int cx=10;cx<g.width-10;cx+=24) {
        Candidate best{{},.00003};
        for(int y=cy;y<std::min(cy+24,g.height-9);y+=3) for(int x=cx;x<std::min(cx+24,g.width-9);x+=3) {
            double xx=0,xy=0,yy=0;
            for(int j=-2;j<=2;j++) for(int i=-2;i<=2;i++) {
                double dx=(g.at(x+i+1,y+j)-g.at(x+i-1,y+j))*.5;
                double dy=(g.at(x+i,y+j+1)-g.at(x+i,y+j-1))*.5;
                xx+=dx*dx; xy+=dx*dy; yy+=dy*dy;
            }
            double score=(xx+yy-std::sqrt(square(xx-yy)+4*xy*xy))*.5;
            if(score>best.score) best={{double(x),double(y)},score};
        }
        if(best.score>.00003) candidates.push_back(best);
    }
    std::sort(candidates.begin(),candidates.end(),[](auto a,auto b){return a.score>b.score;});
    std::vector<Point> out;
    for(auto c:candidates) {
        bool close=false; for(auto p:out) if(square(p.x-c.p.x)+square(p.y-c.p.y)<100) {close=true;break;}
        if(!close) out.push_back(c.p);
        if(out.size()==420) break;
    }
    return out;
}
bool inside(const Gray& g,Point p,int margin=6) {
    return p.x>=margin && p.y>=margin && p.x<g.width-margin && p.y<g.height-margin;
}
double patchError(const Gray& a,const Gray& b,Point p,Point q) {
    double av=0,bv=0,err=0;
    for(int j=-4;j<=4;j++) for(int i=-4;i<=4;i++) {av+=a.at(p.x+i,p.y+j);bv+=b.at(q.x+i,q.y+j);}
    av/=81; bv/=81;
    for(int j=-4;j<=4;j++) for(int i=-4;i<=4;i++) err+=square(a.at(p.x+i,p.y+j)-av-b.at(q.x+i,q.y+j)+bv);
    return err/81;
}
struct TrackFailures {int bounds=0,gradient=0,patch=0,consistency=0,levelReduced=0,levelReductions=0,baseStarts=0;};
bool track(const std::vector<Gray>& a,const std::vector<Gray>& b,Point p,Point prediction,Point& q,TrackFailures* failures=nullptr) {
    auto boundsFailure=[&](){if(failures)failures->bounds++;return false;};
    int top=int(a.size())-1,requestedTop=top;
    while(top>0 && (!inside(a[top],{p.x/std::ldexp(1.,top),p.y/std::ldexp(1.,top)}) ||
                   !inside(b[top],{prediction.x/std::ldexp(1.,top),prediction.y/std::ldexp(1.,top)}))) top--;
    if(failures){failures->levelReduced+=top<requestedTop;failures->levelReductions+=requestedTop-top;failures->baseStarts+=top==0;}
    q={prediction.x/std::ldexp(1.,top),prediction.y/std::ldexp(1.,top)};
    for(int level=top;level>=0;level--) {
        double factor=std::ldexp(1.,level); Point r{p.x/factor,p.y/factor};
        if(level<top) {q.x*=2;q.y*=2;}
        if(!inside(a[level],r) || !inside(b[level],q)) return boundsFailure();
        if(level==top) {
            Point best=q; double error=patchError(a[level],b[level],r,q);
            for(int j=-5;j<=5;j++) for(int i=-5;i<=5;i++) {
                Point c{q.x+i,q.y+j}; if(!inside(b[level],c)) continue;
                double e=patchError(a[level],b[level],r,c); if(e<error) {error=e;best=c;}
            }
            q=best;
        }
        for(int iter=0;iter<15;iter++) {
            if(!inside(b[level],q)) return boundsFailure();
            double meanA=0,meanB=0;
            for(int j=-4;j<=4;j++) for(int i=-4;i<=4;i++) {meanA+=a[level].at(r.x+i,r.y+j);meanB+=b[level].at(q.x+i,q.y+j);}
            double bias=(meanA-meanB)/81;
            double xx=0,xy=0,yy=0,ex=0,ey=0;
            for(int j=-4;j<=4;j++) for(int i=-4;i<=4;i++) {
                double x=q.x+i,y=q.y+j;
                double gx=(b[level].at(x+1,y)-b[level].at(x-1,y))*.5;
                double gy=(b[level].at(x,y+1)-b[level].at(x,y-1))*.5;
                double e=a[level].at(r.x+i,r.y+j)-b[level].at(x,y)-bias;
                xx+=gx*gx;xy+=gx*gy;yy+=gy*gy;ex+=gx*e;ey+=gy*e;
            }
            double det=xx*yy-xy*xy; if(det<1e-8) {if(failures)failures->gradient++;return false;}
            double dx=(yy*ex-xy*ey)/det,dy=(xx*ey-xy*ex)/det;
            double step=std::hypot(dx,dy); if(step>3) {dx*=3/step;dy*=3/step;}
            q.x+=dx;q.y+=dy;
            if(step<.015) break;
        }
    }
    if(!inside(b[0],q)) return boundsFailure();
    bool accepted=patchError(a[0],b[0],p,q)<.012;
    if(!accepted && failures) failures->patch++;
    return accepted;
}
// Refine an already supported planar match using the full homography across
// every template pixel. Translation-only patches are not photometrically aligned
// when a print grows in the view or rotates, even if its center is predicted well.
bool trackWarped(const std::vector<Gray>& reference,const std::vector<Gray>& source,
                 Point p,Point initial,const Homography& map,int startLevel,Point& q,
                 TrackFailures* failures=nullptr) {
    auto failBounds=[&](){if(failures)failures->bounds++;return false;};
    Point predicted=project(map,p);int top=std::min({startLevel,int(reference.size())-1,int(source.size())-1}),requestedTop=top;
    auto validLevel=[&](int level) {
        double factor=std::ldexp(1.,level);
        if(!inside(reference[level],{p.x/factor,p.y/factor}))return false;
        for(int j:{-4,4})for(int i:{-4,4}) {
            auto at=project(map,{p.x+i*factor,p.y+j*factor});
            at={(at.x+initial.x-predicted.x)/factor,(at.y+initial.y-predicted.y)/factor};
            if(!std::isfinite(at.x) || !std::isfinite(at.y) || !inside(source[level],at,2))return false;
        }
        return true;
    };
    while(top>0 && !validLevel(top))top--;
    if(failures){failures->levelReduced+=top<requestedTop;failures->levelReductions+=requestedTop-top;failures->baseStarts+=top==0;}
    if(!validLevel(top))return failBounds();
    double factor=std::ldexp(1.,top);Point residual{(initial.x-predicted.x)/factor,(initial.y-predicted.y)/factor};
    for(int level=top;level>=0;level--) {
        factor=std::ldexp(1.,level);Point r{p.x/factor,p.y/factor};
        if(level<top){residual.x*=2;residual.y*=2;}
        if(!inside(reference[level],r))return failBounds();
        std::array<Point,81> locations;std::array<double,81> values;int index=0;
        for(int j=-4;j<=4;j++)for(int i=-4;i<=4;i++) {
            Point sample=project(map,{p.x+i*factor,p.y+j*factor});locations[index]={sample.x/factor,sample.y/factor};
            values[index++]=reference[level].at(r.x+i,r.y+j);
        }
        double finalError=0;
        for(int iteration=0;iteration<15;iteration++) {
            double meanA=0,meanB=0;
            for(int k=0;k<81;k++) {
                Point at{locations[k].x+residual.x,locations[k].y+residual.y};
                if(!std::isfinite(at.x) || !std::isfinite(at.y) || !inside(source[level],at,2))return failBounds();
                meanA+=values[k];meanB+=source[level].at(at.x,at.y);
            }
            double bias=(meanA-meanB)/81,xx=0,xy=0,yy=0,ex=0,ey=0;finalError=0;
            for(int k=0;k<81;k++) {
                double x=locations[k].x+residual.x,y=locations[k].y+residual.y;
                double gx=(source[level].at(x+1,y)-source[level].at(x-1,y))*.5;
                double gy=(source[level].at(x,y+1)-source[level].at(x,y-1))*.5;
                double e=values[k]-source[level].at(x,y)-bias;
                xx+=gx*gx;xy+=gx*gy;yy+=gy*gy;ex+=gx*e;ey+=gy*e;finalError+=e*e;
            }
            double det=xx*yy-xy*xy;if(det<1e-8){if(failures)failures->gradient++;return false;}
            double dx=(yy*ex-xy*ey)/det,dy=(xx*ey-xy*ex)/det,step=std::hypot(dx,dy);
            if(step>3){dx*=3/step;dy*=3/step;}residual.x+=dx;residual.y+=dy;if(step<.015)break;
        }
        if(level==0 && finalError/81>=.012){if(failures)failures->patch++;return false;}
    }
    q={predicted.x+residual.x,predicted.y+residual.y};return true;
}
struct RansacDiagnostics {
    std::optional<double> attempted,tracked,inliers,rms,spanX,spanY;
    const char* result="unmeasured";
    const char* trackingMethod="unmeasured";
    TrackFailures failures;
};
void number(std::ostream& out,std::optional<double> value) {
    if(value && std::isfinite(*value)) out<<*value;else out<<"null";
}
void pointJson(std::ostream& out,Point p) {out<<'[';number(out,p.x);out<<',';number(out,p.y);out<<']';}
std::ostringstream jsonStream() {std::ostringstream out;out.imbue(std::locale::classic());out<<std::setprecision(12);return out;}
struct AttemptDiagnostics {
    int width,height,acceptedBefore,phasesBefore;
    AttemptDiagnostics(int w,int h,int accepted,int phases):width(w),height(h),acceptedBefore(accepted),phasesBefore(phases) {}
    std::optional<double> trackingWidth,trackingHeight,scale,referenceFeatures,detectedFeatures,sharpness,relativeSharpnessThreshold;
    std::optional<double> overlap,areaRatio,minDisplacement,denominatorMin,denominatorMax,comparedFrames;
    RansacDiagnostics coarse,fine;
    std::vector<RansacDiagnostics> coarseHypotheses;
    const char* reason="exception";const char* stage="input";const char* pairsStage="unmeasured";
    std::vector<Point> a,b;
    bool timed=true;
    std::chrono::steady_clock::time_point start=std::chrono::steady_clock::now();
    void pairs(const std::vector<Point>& reference,const std::vector<Point>& source,double factor,const char* label) {
        if(reference.empty() && !a.empty()) return;
        a.clear();b.clear();pairsStage=label;
        for(size_t i=0;i<reference.size() && a.size()<48;i++) {
            // Evenly spaced samples retain coverage across the tracked features.
            size_t index=reference.size()<=48?i:i*reference.size()/48;
            a.push_back({reference[index].x*factor,reference[index].y*factor});
            b.push_back({source[index].x*factor,source[index].y*factor});
        }
    }
    std::string json(int acceptedAfter,int phasesAfter) const {
        auto out=jsonStream();
        out<<"{\"schema\":1,\"reason\":\""<<reason<<"\",\"stage\":\""<<stage<<"\",\"width\":";number(out,width>0?std::optional<double>(width):std::nullopt);out<<",\"height\":";number(out,height>0?std::optional<double>(height):std::nullopt);
        auto value=[&](const char* key,std::optional<double> n){out<<",\""<<key<<"\":";number(out,n);};
        value("trackingWidth",trackingWidth);value("trackingHeight",trackingHeight);value("trackingScaleToSource",scale);
        value("referenceFeatures",referenceFeatures);value("detectedReferenceFeatures",detectedFeatures);value("sharpness",sharpness);
        value("relativeSharpnessThreshold",relativeSharpnessThreshold);value("overlapGridCount",overlap);value("areaRatio",areaRatio);
        value("minimumCornerDisplacement",minDisplacement);value("displacementFramesCompared",comparedFrames);value("projectionDenominatorMin",denominatorMin);value("projectionDenominatorMax",denominatorMax);
        out<<",\"acceptedBefore\":"<<acceptedBefore<<",\"acceptedAfter\":"<<acceptedAfter<<",\"phasesBefore\":"<<phasesBefore<<",\"phasesAfter\":"<<phasesAfter;
        auto ransac=[&](const char* key,const RansacDiagnostics& d,double factor) {
            if(key)out<<",\""<<key<<"\":";
            out<<"{\"result\":\""<<d.result<<"\",\"trackingMethod\":\""<<d.trackingMethod<<"\",\"coordinateUnits\":\"tracking_pixels; multiply rms/spans by coordinateScaleToSource for native source pixels\",\"coordinateScaleToSource\":";number(out,factor);
            auto item=[&](const char* k,std::optional<double> n){out<<",\""<<k<<"\":";number(out,n);};
            item("attempted",d.attempted);item("tracked",d.tracked);item("inliers",d.inliers);item("rms",d.rms);item("referenceSpanX",d.spanX);item("referenceSpanY",d.spanY);
            item("featuresWithReducedStartLevel",d.attempted?std::optional<double>(d.failures.levelReduced):std::nullopt);
            item("startLevelReductions",d.attempted?std::optional<double>(d.failures.levelReductions):std::nullopt);
            item("featuresStartingAtBaseLevel",d.attempted?std::optional<double>(d.failures.baseStarts):std::nullopt);
            out<<",\"trackingFailures\":{\"bounds\":";number(out,d.attempted?std::optional<double>(d.failures.bounds):std::nullopt);
            out<<",\"lowGradient\":";number(out,d.attempted?std::optional<double>(d.failures.gradient):std::nullopt);
            out<<",\"patchError\":";number(out,d.attempted?std::optional<double>(d.failures.patch):std::nullopt);out<<",\"forwardBackward\":";number(out,d.attempted?std::optional<double>(d.failures.consistency):std::nullopt);out<<"}}";
        };
        ransac("coarse",coarse,scale.value_or(std::numeric_limits<double>::quiet_NaN()));ransac("fine",fine,1);
        out<<",\"coarseHypotheses\":[";for(size_t i=0;i<coarseHypotheses.size();i++){if(i)out<<',';ransac(nullptr,coarseHypotheses[i],scale.value_or(std::numeric_limits<double>::quiet_NaN()));}out<<']';
        out<<",\"thresholds\":{\"fineRefinement\":\"coarse-consensus homography-warped multiscale patches with reverse check\",\"fineForwardBackwardErrorMax\":1,\"trackingMargin\":6,\"trackingPatchErrorMaxExclusive\":0.012,\"trackingGradientDetMin\":1e-8,\"sharpnessMin\":0.35,\"relativeSharpnessFraction\":0.36,\"referenceFeaturesMin\":18,\"featureScoreMin\":0.00003,\"featureSeparationMin\":10,\"featureGridCell\":24,\"featuresMax\":420,\"ransacCorrespondencesMin\":12,\"ransacInliersMin\":12,\"ransacInitialInlierFractionMin\":0.38,\"ransacInlierRadius\":1.5,\"ransacRmsMaxExclusive\":1.1,\"ransacReferenceSpanMinExclusive\":35,\"projectionDenominatorMin\":0.25,\"projectionDenominatorMax\":4,\"projectionCoordinateMaxSourceSides\":3,\"areaRatioMin\":0.45,\"areaRatioMax\":2.2,\"overlapGridCountMin\":12,\"overlapGridTotal\":25,\"duplicateDisplacementMin\":0.16,\"maxFrames\":24}";
        out<<",\"elapsedScope\":\""<<(timed?"scanner.add including thumbnail, excluding YUV/bitmap conversion":"unmeasured input conversion")<<"\",\"displacementScope\":\"minimum across accepted frames visited before return\",\"correspondenceCoordinateSpace\":\"native_source_pixels; reference=first accepted frame; source=current attempted frame\",\"correspondenceStage\":\""<<pairsStage<<"\",\"correspondences\":[";
        for(size_t i=0;i<a.size();i++) {if(i)out<<',';out<<"{\"reference\":";pointJson(out,a[i]);out<<",\"source\":";pointJson(out,b[i]);out<<'}';}
        out<<"],\"elapsedMs\":";number(out,timed?std::optional<double>(std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count()):std::nullopt);out<<'}';return out.str();
    }
};
Image thumbnail(const Image& image,int side) {
    double ratio=std::min(1.,double(side)/std::max(image.width,image.height));
    Image result(std::max(1,int(std::lround(image.width*ratio))),std::max(1,int(std::lround(image.height*ratio))));
    for(int y=0;y<result.height;y++) for(int x=0;x<result.width;x++) {
        int sx=std::min(image.width-1,int((x+.5)*image.width/result.width));
        int sy=std::min(image.height-1,int((y+.5)*image.height/result.height));
        for(int c=0;c<3;c++) result.rgb[(size_t(y)*result.width+x)*3+c]=image.rgb[(size_t(sy)*image.width+sx)*3+c];
    }
    return result;
}
bool ransac(const std::vector<Point>& a,const std::vector<Point>& b,Homography& h,int& count,double& error,RansacDiagnostics* diagnostics=nullptr) {
    auto result=[&](const char* reason){if(diagnostics)diagnostics->result=reason;return false;};
    if(a.size()<12) return result("insufficient-correspondences");
    uint32_t seed=0x21a5437; std::vector<int> best;
    for(int trial=0;trial<450;trial++) {
        std::vector<Point> x,y; std::vector<size_t> ids;
        while(ids.size()<4) {
            seed=1664525*seed+1013904223; size_t id=seed%a.size();
            if(std::find(ids.begin(),ids.end(),id)==ids.end()) {ids.push_back(id);x.push_back(a[id]);y.push_back(b[id]);}
        }
        Homography candidate; if(!fit(x,y,candidate)) continue;
        std::vector<int> inliers;
        for(size_t k=0;k<a.size();k++) {
            Point q=project(candidate,a[k]);
            if(square(q.x-b[k].x)+square(q.y-b[k].y)<2.25) inliers.push_back(int(k));
        }
        if(inliers.size()>best.size()) best=std::move(inliers);
    }
    if(diagnostics) diagnostics->inliers=double(best.size());
    if(best.size()<12 || best.size()<a.size()*.38) return result("insufficient-inliers");
    for(int refine=0;refine<2;refine++) {
        std::vector<Point> x,y; for(int id:best) {x.push_back(a[id]);y.push_back(b[id]);}
        if(!fit(x,y,h)) return result("refit-failed");
        best.clear(); for(size_t k=0;k<a.size();k++) {
            Point q=project(h,a[k]); if(std::hypot(q.x-b[k].x,q.y-b[k].y)<1.5) best.push_back(int(k));
        }
    }
    error=0; for(int id:best) {Point q=project(h,a[id]);error+=square(q.x-b[id].x)+square(q.y-b[id].y);}
    count=int(best.size());error=std::sqrt(error/std::max(1,count));
    // A single textured object must not establish an otherwise unsupported plane.
    double minX=1e9,maxX=0,minY=1e9,maxY=0;
    for(int id:best) {minX=std::min(minX,a[id].x);maxX=std::max(maxX,a[id].x);minY=std::min(minY,a[id].y);maxY=std::max(maxY,a[id].y);}
    bool accepted=count>=12 && maxX-minX>35 && maxY-minY>35 && error<1.1;
    if(diagnostics) {diagnostics->inliers=count;diagnostics->rms=count?std::optional<double>(error):std::nullopt;if(count){diagnostics->spanX=maxX-minX;diagnostics->spanY=maxY-minY;}diagnostics->result=accepted?"accepted":count<12?"insufficient-refined-inliers":(maxX-minX<=35 || maxY-minY<=35)?"insufficient-spatial-span":"rms-too-high";}
    return accepted;
}
int phase(const Homography& h,int width,int height) {
    Point p{width*.5,height*.5},q=project(h,p);
    double dx=q.x-p.x,dy=q.y-p.y;
    return std::min(3,int((dx-std::floor(dx))*4))+4*std::min(3,int((dy-std::floor(dy))*4));
}
float linear(uint8_t value) {
    float s=value/255.f;return s<=.04045f ? s/12.92f : std::pow((s+.055f)/1.055f,2.4f);
}
uint8_t encode(float value) {
    value=std::clamp(value,0.f,1.f);
    float s=value<=.0031308f ? value*12.92f : 1.055f*std::pow(value,1.f/2.4f)-.055f;
    return uint8_t(std::clamp(int(std::lround(s*255)),0,255));
}
struct Sample {float r=0,g=0,b=0,w=0;};
Sample sample(const Image& im,double x,double y,const std::array<float,256>& lut) {
    if(x<0 || y<0 || x>im.width-1 || y>im.height-1) return {};
    int ix=int(x),iy=int(y);double fx=x-ix,fy=y-iy;Sample s;
    for(int j=0;j<2;j++) for(int i=0;i<2;i++) {
        int xx=std::min(im.width-1,ix+i),yy=std::min(im.height-1,iy+j);
        double w=(i?fx:1-fx)*(j?fy:1-fy);size_t p=(size_t(yy)*im.width+xx)*3;
        s.r+=float(w*lut[im.rgb[p]]);s.g+=float(w*lut[im.rgb[p+1]]);s.b+=float(w*lut[im.rgb[p+2]]);
    }
    s.w=1;return s;
}
float luminance(const Sample& s) {return .2126f*s.r+.7152f*s.g+.0722f*s.b;}
} // namespace

Point project(const Homography& h,Point p) {
    double d=h[6]*p.x+h[7]*p.y+h[8];
    if(std::abs(d)<epsilon) return {1e12,1e12};
    return {(h[0]*p.x+h[1]*p.y+h[2])/d,(h[3]*p.x+h[4]*p.y+h[5])/d};
}
bool fit(const std::vector<Point>& from,const std::vector<Point>& to,Homography& result) {
    if(from.size()!=to.size() || from.size()<4) return false;
    Point ca{},cb{};
    for(size_t i=0;i<from.size();i++) {ca.x+=from[i].x;ca.y+=from[i].y;cb.x+=to[i].x;cb.y+=to[i].y;}
    double n=double(from.size());ca.x/=n;ca.y/=n;cb.x/=n;cb.y/=n;
    double sa=0,sb=0;
    for(size_t i=0;i<from.size();i++) {sa+=std::hypot(from[i].x-ca.x,from[i].y-ca.y);sb+=std::hypot(to[i].x-cb.x,to[i].y-cb.y);}
    if(sa<epsilon || sb<epsilon) return false;
    sa=std::sqrt(2.)*n/sa;sb=std::sqrt(2.)*n/sb;
    double normal[8][9]{};
    for(size_t i=0;i<from.size();i++) {
        double x=(from[i].x-ca.x)*sa,y=(from[i].y-ca.y)*sa,u=(to[i].x-cb.x)*sb,v=(to[i].y-cb.y)*sb;
        double rows[2][9]={{x,y,1,0,0,0,-u*x,-u*y,u},{0,0,0,x,y,1,-v*x,-v*y,v}};
        for(auto& row:rows) for(int j=0;j<8;j++) for(int k=0;k<9;k++) normal[j][k]+=row[j]*row[k];
    }
    double coeff[8];if(!solve(normal,coeff)) return false;
    Homography h{coeff[0],coeff[1],coeff[2],coeff[3],coeff[4],coeff[5],coeff[6],coeff[7],1};
    Homography ta{sa,0,-sa*ca.x,0,sa,-sa*ca.y,0,0,1},ib{1/sb,0,cb.x,0,1/sb,cb.y,0,0,1};
    result=multiply(multiply(ib,h),ta);
    if(std::abs(result[8])<epsilon) return false;
    double norm=result[8];for(double& v:result) {v/=norm;if(!std::isfinite(v)) return false;}
    return true;
}
Homography inverse(const Homography& h) {
    for(double v:h) if(!std::isfinite(v)) throw std::invalid_argument("Nonfinite camera transform");
    Homography r{h[4]*h[8]-h[5]*h[7],h[2]*h[7]-h[1]*h[8],h[1]*h[5]-h[2]*h[4],
                 h[5]*h[6]-h[3]*h[8],h[0]*h[8]-h[2]*h[6],h[2]*h[3]-h[0]*h[5],
                 h[3]*h[7]-h[4]*h[6],h[1]*h[6]-h[0]*h[7],h[0]*h[4]-h[1]*h[3]};
    double d=h[0]*r[0]+h[1]*r[3]+h[2]*r[6];
    if(!std::isfinite(d) || std::abs(d)<epsilon) throw std::invalid_argument("Singular camera transform");
    for(double& v:r) {v/=d;if(!std::isfinite(v)) throw std::invalid_argument("Unbounded camera transform");}
    return r;
}
float Gray::at(double x,double y) const {
    x=std::clamp(x,0.,double(width-1));y=std::clamp(y,0.,double(height-1));
    int ix=int(x),iy=int(y),nx=std::min(ix+1,width-1),ny=std::min(iy+1,height-1);
    double fx=x-ix,fy=y-iy;
    return float((1-fy)*((1-fx)*pixels[size_t(iy)*width+ix]+fx*pixels[size_t(iy)*width+nx])+fy*((1-fx)*pixels[size_t(ny)*width+ix]+fx*pixels[size_t(ny)*width+nx]));
}
void Scanner::configureStorage(const std::string& directory) {
    if(directory.empty()) throw std::invalid_argument("Storage directory is empty");
    std::filesystem::create_directories(directory);
    if(!std::filesystem::is_directory(directory)) throw std::invalid_argument("Storage path must be a directory");
    reset();storage=directory;
}
void Scanner::reset() {
    for(const auto& path:ownedFiles) std::remove(path.c_str());
    ownedFiles.clear();frames.clear();reference.clear();features.clear();fullReference.clear();phaseBins.fill(false);last=identity();initialSharpness=0;
    attemptDiagnostics="{\"schema\":1,\"reason\":\"reset\",\"stage\":\"idle\"}";attemptThumbnail=Image();
}
void Scanner::noteInputFailure(int width,int height) {
    AttemptDiagnostics d{width,height,int(frames.size()),int(std::count(phaseBins.begin(),phaseBins.end(),true))};
    d.reason="invalid-input";d.stage="input-conversion";d.timed=false;attemptDiagnostics=d.json(d.acceptedBefore,d.phasesBefore);attemptThumbnail=Image();
}
Report Scanner::add(Image image) {
    AttemptDiagnostics d{image.width,image.height,int(frames.size()),int(std::count(phaseBins.begin(),phaseBins.end(),true))};
    Report report;report.accepted=int(frames.size());report.phases=d.phasesBefore;
    attemptThumbnail=Image();
    auto finish=[&](const char* reason,const char* stage,int status) {
        d.reason=reason;d.stage=stage;report.status=status;
        attemptDiagnostics=d.json(int(frames.size()),int(std::count(phaseBins.begin(),phaseBins.end(),true)));return report;
    };
    try {
        validate(image);attemptThumbnail=thumbnail(image,960);
        if(frames.size()>=maxFrames) return finish("full","admission",4);
        if(!frames.empty() && (image.width!=frames[0].image.width || image.height!=frames[0].image.height)) throw std::invalid_argument("Frame dimensions changed; reset capture first");
        d.stage="pyramid";auto gray=pyramid(image);
        int level=0;while(level+1<int(gray.size()) && std::max(gray[level].width,gray[level].height)>800) level++;
        double factor=std::ldexp(1.,level);
        d.scale=factor;d.trackingWidth=gray[level].width;d.trackingHeight=gray[level].height;
        std::vector<Gray> tracking(gray.begin()+level,gray.end());
        report.sharpness=sharpness(tracking[0]);d.sharpness=report.sharpness;
        if(!frames.empty()) {d.referenceFeatures=features.size();d.relativeSharpnessThreshold=initialSharpness*.36;}
        if(report.sharpness<.35 || (!frames.empty() && report.sharpness<initialSharpness*.36)) return finish("blur","sharpness",1);
        bool referenceFrame=frames.empty();
        if(referenceFrame) {
            d.stage="reference-features";auto points=detect(tracking[0]);d.detectedFeatures=points.size();d.referenceFeatures=points.size();
            if(points.size()<18) return finish("insufficient-reference-features","reference-features",2);
            reference=std::move(tracking);features=std::move(points);initialSharpness=report.sharpness;
            if(level>0) fullReference=std::move(gray);
            d.stage="source-storage";addKnown(std::move(image),identity());
        } else {
            Homography smallLast=last;
            smallLast[2]/=factor;smallLast[5]/=factor;smallLast[6]*=factor;smallLast[7]*=factor;
            std::vector<Point> a,b;d.stage="coarse-tracking";d.coarse.attempted=features.size();
            d.coarse.trackingMethod=frames.size()>1?"previous-homography-warped-pyramid":"translation-pyramid-acquisition";
            for(auto p:features) {
                Point q;bool matched=frames.size()>1
                    ?trackWarped(reference,tracking,p,project(smallLast,p),smallLast,int(reference.size())-1,q,&d.coarse.failures)
                    :track(reference,tracking,p,project(smallLast,p),q,&d.coarse.failures);
                if(matched) {a.push_back(p);b.push_back(q);}
            }
            d.coarse.tracked=a.size();d.pairs(a,b,factor,"coarse");d.stage="coarse-ransac";
            Homography h;
            bool acquired=ransac(a,b,h,report.inliers,report.error,&d.coarse);d.coarseHypotheses.push_back(d.coarse);
            if(!acquired && frames.size()>1) {
                // A stale pose can predict the subject outside its actual basin.
                // Retry independently of that pose; all geometric gates remain
                // identical, and only a validated plane can proceed to sensor refinement.
                d.coarse=RansacDiagnostics();d.coarse.trackingMethod="translation-pyramid-identity-reacquisition";
                d.coarse.attempted=features.size();a.clear();b.clear();d.stage="coarse-reacquisition";
                for(auto p:features){Point q;if(track(reference,tracking,p,p,q,&d.coarse.failures)){a.push_back(p);b.push_back(q);}}
                d.coarse.tracked=a.size();d.pairs(a,b,factor,"coarse-reacquisition");report.inliers=0;report.error=0;
                acquired=ransac(a,b,h,report.inliers,report.error,&d.coarse);d.coarseHypotheses.push_back(d.coarse);
            }
            if(!acquired) return finish("coarse-ransac","coarse-ransac",2);
            h[2]*=factor;h[5]*=factor;h[6]/=factor;h[7]/=factor;report.error*=factor;
            if(level>0) {
                // Keep only matches supporting the acquired plane. Refine those
                // through all sensor pyramid levels with geometrically warped
                // patches, then independently check reverse tracking consistency.
                std::vector<Point> consensusReference,consensusSource;
                for(size_t i=0;i<a.size();i++) {
                    Point r{a[i].x*factor,a[i].y*factor},observed{b[i].x*factor,b[i].y*factor};
                    Point predicted=project(h,r);
                    if(std::hypot(predicted.x-observed.x,predicted.y-observed.y)<1.5*factor){consensusReference.push_back(r);consensusSource.push_back(observed);}
                }
                a.clear();b.clear();d.stage="fine-tracking";d.fine.attempted=consensusReference.size();d.fine.trackingMethod="coarse-consensus-homography-warped-pyramid-forward-backward";
                Homography reverse=inverse(h);
                for(size_t i=0;i<consensusReference.size();i++) {
                    Point r=consensusReference[i],q,back;
                    if(!trackWarped(fullReference,gray,r,consensusSource[i],h,level,q,&d.fine.failures))continue;
                    if(!trackWarped(gray,fullReference,q,r,reverse,level,back) || std::hypot(back.x-r.x,back.y-r.y)>1.){d.fine.failures.consistency++;continue;}
                    a.push_back(r);b.push_back(q);
                }
                d.fine.tracked=a.size();d.pairs(a,b,1,"fine");d.stage="fine-ransac";
                if(!ransac(a,b,h,report.inliers,report.error,&d.fine)) return finish("fine-ransac","fine-ransac",2);
            }
            int w=image.width,ht=image.height;d.stage="projection";
            std::array<Point,4> corners{{{0,0},{double(w-1),0},{double(w-1),double(ht-1)},{0,double(ht-1)}}};
            double area=0;int overlap=0;
            for(int i=0;i<4;i++) {
                double denominator=h[6]*corners[i].x+h[7]*corners[i].y+h[8];
                d.denominatorMin=d.denominatorMin?std::min(*d.denominatorMin,denominator):denominator;
                d.denominatorMax=d.denominatorMax?std::max(*d.denominatorMax,denominator):denominator;
                if(denominator<.25 || denominator>4) return finish("invalid-projection","projection-denominator",2);
                Point p=project(h,corners[i]),q=project(h,corners[(i+1)%4]);
                if(!std::isfinite(p.x) || !std::isfinite(p.y) || std::abs(p.x)>w*3 || std::abs(p.y)>ht*3) return finish("invalid-projection","projection-coordinates",2);
                area+=p.x*q.y-p.y*q.x;
            }
            for(int y=1;y<=5;y++) for(int x=1;x<=5;x++) {Point p=project(h,{w*x/6.,ht*y/6.});if(p.x>=0 && p.x<w && p.y>=0 && p.y<ht) overlap++;}
            double ratio=area/(2.*w*ht);d.overlap=overlap;d.areaRatio=ratio;
            if(ratio<.45 || ratio>2.2 || overlap<12) return finish("scale-overlap","scale-overlap",2);
            report.transform=h;d.stage="duplicate";
            d.comparedFrames=0;
            for(const auto& f:frames) {
                *d.comparedFrames+=1;double displacement=0;
                for(auto p:corners) {Point x=project(h,p),y=project(f.transform,p);displacement+=std::hypot(x.x-y.x,x.y-y.y);}
                d.minDisplacement=d.minDisplacement?std::min(*d.minDisplacement,displacement/4):displacement/4;
                if(displacement/4<.16) return finish("duplicate","duplicate",3);
            }
            d.stage="source-storage";addKnown(std::move(image),h);
        }
        report.accepted=int(frames.size());report.phases=int(std::count(phaseBins.begin(),phaseBins.end(),true));report.transform=last;
        return finish(referenceFrame?"accepted-reference":"accepted","accepted",0);
    } catch(...) {
        d.reason=std::string(d.stage)=="input"?"invalid-input":"exception";attemptDiagnostics=d.json(int(frames.size()),int(std::count(phaseBins.begin(),phaseBins.end(),true)));throw;
    }
}
void Scanner::addKnown(Image image,const Homography& transform) {
    validate(image);if(frames.size()>=maxFrames) throw std::invalid_argument("Capture is full");
    if(!frames.empty() && (image.width!=frames[0].image.width || image.height!=frames[0].image.height)) throw std::invalid_argument("Frame dimensions do not match");
    inverse(transform);int phaseIndex=phase(transform,image.width,image.height);
    Frame frame;frame.transform=transform;frame.sharpness=0;
    if(!storage.empty()) {
        frame.diskPath=temporaryPath((std::filesystem::path(storage)/"source.rgb").string());
        RasterWriter writer(frame.diskPath,image.width,image.height);
        for(int y=0;y<image.height;y++) writer.row(image.rgb.data()+size_t(y)*image.width*3);
        writer.finish();ownedFiles.push_back(frame.diskPath);
        frame.image.width=image.width;frame.image.height=image.height;
    }
    double ratio=std::min(1.,640./std::max(image.width,image.height));
    frame.thumbnail=Image(std::max(1,int(std::lround(image.width*ratio))),std::max(1,int(std::lround(image.height*ratio))));
    for(int y=0;y<frame.thumbnail.height;y++) for(int x=0;x<frame.thumbnail.width;x++) {
        int sx=std::min(image.width-1,int((x+.5)*image.width/frame.thumbnail.width));
        int sy=std::min(image.height-1,int((y+.5)*image.height/frame.thumbnail.height));
        for(int c=0;c<3;c++) frame.thumbnail.rgb[(size_t(y)*frame.thumbnail.width+x)*3+c]=image.rgb[(size_t(sy)*image.width+sx)*3+c];
    }
    if(storage.empty()) frame.image=std::move(image);
    frames.push_back(std::move(frame));phaseBins[phaseIndex]=true;last=transform;
}

std::array<double,5> Scanner::process(int scale,int maxSide,const std::function<void(const uint8_t*,int,int)>& emit) const {
    if(frames.empty()) throw std::runtime_error("Capture at least one frame first");
    if(scale<1 || scale>2 || maxSide<0 || maxSide>16384) throw std::invalid_argument("Invalid render size");
    int sourceW=frames[0].image.width,sourceH=frames[0].image.height;
    double zoom=scale;
    if(maxSide>0) zoom=std::min(zoom,double(maxSide)/std::max(sourceW,sourceH));
    int width=std::max(1,int(std::lround(sourceW*zoom))),height=std::max(1,int(std::lround(sourceH*zoom)));
    checkRasterDimensions(width,height);
    std::array<float,256> lut{};for(int i=0;i<256;i++) lut[i]=linear(uint8_t(i));
    constexpr int tileHeight=8;
    size_t support=0,clipped=0,rejected=0,observations=0;
    bool preview=zoom<.75 || frames.size()==1;
    std::vector<Homography> inv;for(const auto& frame:frames) inv.push_back(inverse(frame.transform));
    std::vector<std::unique_ptr<RasterReader>> sources;
    for(const auto& frame:frames) sources.push_back(frame.diskPath.empty()?nullptr:std::make_unique<RasterReader>(frame.diskPath));
    auto sourceRow=[&](size_t f,int y)->const uint8_t* {
        return sources[f]?sources[f]->row(y):frames[f].image.rgb.data()+size_t(y)*sourceW*3;
    };
    auto fetch=[&](size_t f,double x,double y)->Sample {
        if(x<0 || y<0 || x>sourceW-1 || y>sourceH-1) return {};
        int ix=int(x),iy=int(y),nx=std::min(ix+1,sourceW-1),ny=std::min(iy+1,sourceH-1);
        double fx=x-ix,fy=y-iy;const uint8_t* a=sourceRow(f,iy);const uint8_t* b=sourceRow(f,ny);Sample s;
        float* channels[3]={&s.r,&s.g,&s.b};
        for(int c=0;c<3;c++) *channels[c]=float((1-fy)*((1-fx)*lut[a[3*ix+c]]+fx*lut[a[3*nx+c]])+fy*((1-fx)*lut[b[3*ix+c]]+fx*lut[b[3*nx+c]]));
        s.w=1;return s;
    };
    for(int tileY=0;tileY<height;tileY+=tileHeight) {
        int rows=std::min(tileHeight,height-tileY);size_t pixels=size_t(width)*rows;
        std::vector<Sample> data(pixels*frames.size());
        std::vector<uint8_t> output(pixels*3);
        for(size_t f=0;f<frames.size();f++) {
            const auto& image=frames[f].image;Sample* tile=data.data()+pixels*f;
            if(preview) {
                for(int j=0;j<rows;j++) for(int i=0;i<width;i++) {
                    Point p=project(frames[f].transform,{(i+.5)/zoom-.5,(tileY+j+.5)/zoom-.5});
                    if(!frames[f].thumbnail.rgb.empty() && zoom<.75) {
                        const auto& thumb=frames[f].thumbnail;
                        tile[size_t(j)*width+i]=sample(thumb,(p.x+.5)*thumb.width/sourceW-.5,(p.y+.5)*thumb.height/sourceH-.5,lut);
                    } else tile[size_t(j)*width+i]=fetch(f,p.x,p.y);
                }
                continue;
            }
            double minX=image.width,minY=image.height,maxX=0,maxY=0;
            for(int y:{tileY-2,tileY+rows+2}) for(int x:{-2,width+2}) {
                Point p=project(frames[f].transform,{(x+.5)/zoom-.5,(y+.5)/zoom-.5});
                minX=std::min(minX,p.x);maxX=std::max(maxX,p.x);minY=std::min(minY,p.y);maxY=std::max(maxY,p.y);
            }
            int x0=std::max(0,int(std::floor(minX))-3),x1=std::min(image.width-1,int(std::ceil(maxX))+3);
            int y0=std::max(0,int(std::floor(minY))-3),y1=std::min(image.height-1,int(std::ceil(maxY))+3);
            for(int y=y0;y<=y1;y++) {
                const uint8_t* source=sourceRow(f,y);
                for(int x=x0;x<=x1;x++) {
                Point p=project(inv[f],{double(x),double(y)});
                double ox=(p.x+.5)*zoom-.5,oy=(p.y+.5)*zoom-.5;
                if(ox<-1 || ox>width || oy<tileY-1 || oy>tileY+rows) continue;
                int ix=int(std::floor(ox)),iy=int(std::floor(oy));double fx=ox-ix,fy=oy-iy;
                size_t src=size_t(x)*3;
                for(int j=0;j<2;j++) for(int i=0;i<2;i++) {
                    int xx=ix+i,yy=iy+j;if(xx<0 || xx>=width || yy<tileY || yy>=tileY+rows) continue;
                    float w=float((i?fx:1-fx)*(j?fy:1-fy));if(w<.0001f) continue;
                    Sample& s=tile[size_t(yy-tileY)*width+xx];
                    s.r+=w*lut[source[src]];s.g+=w*lut[source[src+1]];s.b+=w*lut[source[src+2]];s.w+=w;
                }
            }
            }
            for(size_t p=0;p<pixels;p++) if(tile[p].w>.0001f) {tile[p].r/=tile[p].w;tile[p].g/=tile[p].w;tile[p].b/=tile[p].w;}
        }
        for(size_t p=0;p<pixels;p++) {
            std::array<float,maxFrames> lumas{},deviations{};int n=0;
            for(size_t f=0;f<frames.size();f++) if(data[f*pixels+p].w>.025f) lumas[n++]=luminance(data[f*pixels+p]);
            Sample fused;int kept=0;bool saturated=false;
            if(n) {
                std::sort(lumas.begin(),lumas.begin()+n);float median=lumas[n/2];
                for(int i=0;i<n;i++) deviations[i]=std::abs(lumas[i]-median);
                std::sort(deviations.begin(),deviations.begin()+n);
                // A robust temporal location suppresses moving bright and dark contaminants.
                // Two observations cannot identify which sample is corrupted.
                float threshold=std::max(.035f,std::min(.16f,3.0f*deviations[n/2]+.025f));
                for(size_t f=0;f<frames.size();f++) {
                    const Sample& s=data[f*pixels+p];if(s.w<=.025f) continue;
                    observations++;
                    if(n>=3 && std::abs(luminance(s)-median)>threshold) {rejected++;continue;}
                    float w=std::min(s.w,1.f);fused.r+=w*s.r;fused.g+=w*s.g;fused.b+=w*s.b;fused.w+=w;kept++;
                    saturated|=std::max({s.r,s.g,s.b})>.985f;
                }
            }
            if(fused.w>0) {fused.r/=fused.w;fused.g/=fused.w;fused.b/=fused.w;}
            else {
                int x=int(p%width),y=tileY+int(p/width);
                fused=fetch(0,std::clamp((x+.5)/zoom-.5,0.,double(sourceW-1)),std::clamp((y+.5)/zoom-.5,0.,double(sourceH-1)));
                saturated=std::max({fused.r,fused.g,fused.b})>.985f;
            }
            if(kept>=2) support++;
            if(saturated) clipped++;
            size_t out=p*3;
            output[out]=encode(fused.r);output[out+1]=encode(fused.g);output[out+2]=encode(fused.b);
        }
        for(int y=0;y<rows;y++) emit(output.data()+size_t(y)*width*3,width,tileY+y);
    }
    return {double(width),double(height),double(support)/(size_t(width)*height),double(clipped)/(size_t(width)*height),observations?double(rejected)/observations:0};
}

Image Scanner::loadFrame(size_t n) const {
    const auto& frame=frames.at(n);
    return frame.diskPath.empty()?frame.image:RasterReader(frame.diskPath).readImage();
}
void Scanner::exportLastSource(const std::string& png) const {
    if(frames.empty()) throw std::runtime_error("No accepted source frame");
    if(png.empty()) throw std::invalid_argument("PNG path is empty");
    for(const auto& source:frames) if(source.diskPath==png) throw std::invalid_argument("Cannot overwrite a capture source");
    const auto& frame=frames.back();PngWriter writer(png,frame.image.width,frame.image.height);
    std::unique_ptr<RasterReader> reader;if(!frame.diskPath.empty()) reader=std::make_unique<RasterReader>(frame.diskPath);
    for(int y=0;y<frame.image.height;y++) writer.row(reader?reader->row(y):frame.image.rgb.data()+size_t(y)*frame.image.width*3);
    writer.finish();
}
Render Scanner::render(int scale,int maxSide) const {
    if(scale<1 || scale>2 || maxSide<0 || maxSide>16384) throw std::invalid_argument("Invalid render size");
    Render result;
    if(frames.empty()) throw std::runtime_error("Capture at least one frame first");
    double zoom=scale;if(maxSide>0) zoom=std::min(zoom,double(maxSide)/std::max(frames[0].image.width,frames[0].image.height));
    int w=std::max(1,int(std::lround(frames[0].image.width*zoom))),h=std::max(1,int(std::lround(frames[0].image.height*zoom)));
    checkRasterDimensions(w,h);
    if(uint64_t(w)*h>12600000) throw std::invalid_argument("Use streaming renderToFiles for outputs above 12.6MP");
    result.image=Image(w,h);
    auto info=process(scale,maxSide,[&](const uint8_t* row,int width,int y){std::copy(row,row+size_t(width)*3,result.image.rgb.data()+size_t(y)*width*3);});
    result.supported=info[2];result.clipped=info[3];result.rejected=info[4];return result;
}
std::array<double,5> Scanner::renderToFiles(const std::string& png,const std::string& raster,int scale,int maxSide) const {
    if(scale<1 || scale>2 || maxSide<0 || maxSide>16384) throw std::invalid_argument("Invalid render size");
    if(png.empty() || raster.empty() || png==raster) throw std::invalid_argument("PNG and raster paths must differ");
    if(frames.empty()) throw std::runtime_error("Capture at least one frame first");
    for(const auto& frame:frames) if(png==frame.diskPath || raster==frame.diskPath) throw std::invalid_argument("Cannot overwrite a capture source");
    double zoom=scale;if(maxSide>0) zoom=std::min(zoom,double(maxSide)/std::max(frames[0].image.width,frames[0].image.height));
    int w=std::max(1,int(std::lround(frames[0].image.width*zoom))),h=std::max(1,int(std::lround(frames[0].image.height*zoom)));
    checkRasterDimensions(w,h);PngWriter output(png,w,h);RasterWriter raw(raster,w,h);
    auto info=process(scale,maxSide,[&](const uint8_t* row,int,int){output.row(row);raw.row(row);});
    raw.finish();output.finish();return info;
}

} // namespace luma
