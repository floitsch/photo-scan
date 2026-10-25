// Copyright (C) 2026 Toit contributors.
#include "fusion.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <iomanip>
#include <limits>
#include <locale>
#include <numeric>
#include <sstream>
#include <stdexcept>

namespace luma {
namespace {
double cross(Point a,Point b,Point c) {return (b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x);}
double polygonArea(const std::vector<Point>& p) {
    double area=0;for(size_t i=0;i<p.size();i++) {auto a=p[i],b=p[(i+1)%p.size()];area+=a.x*b.y-a.y*b.x;}return area*.5;
}
std::vector<Point> hull(std::vector<Point> p) {
    std::sort(p.begin(),p.end(),[](Point a,Point b){return a.x<b.x || (a.x==b.x && a.y<b.y);});
    p.erase(std::unique(p.begin(),p.end(),[](Point a,Point b){return a.x==b.x && a.y==b.y;}),p.end());
    if(p.size()<4)return p;
    std::vector<Point> h(p.size()*2);size_t n=0;
    for(auto q:p) {while(n>=2 && cross(h[n-2],h[n-1],q)<=0)n--;h[n++]=q;}
    size_t lower=n;
    for(size_t i=p.size()-1;i-->0;) {auto q=p[i];while(n>lower && cross(h[n-2],h[n-1],q)<=0)n--;h[n++]=q;}
    h.resize(n-1);return h;
}
bool refineEdges(const Gray& image,std::vector<Point>& polygon,double& rms) {
    struct Line {double x,y,c;};std::array<Line,4> lines;rms=0;
    for(int side=0;side<4;side++) {
        auto a=polygon[side],b=polygon[(side+1)%4];double length=std::hypot(b.x-a.x,b.y-a.y),nx=-(b.y-a.y)/length,ny=(b.x-a.x)/length;
        std::vector<Point> points;int samples=std::max(16,int(length*.7));
        for(int k=0;k<samples;k++) {
            double t=.15+.7*(k+.5)/samples,x=a.x+t*(b.x-a.x),y=a.y+t*(b.y-a.y),best=0,offset=0;
            for(int j=-10;j<=10;j++) {
                double d=j*.5,strength=std::abs(double(image.at(x+nx*(d+1.5),y+ny*(d+1.5))-image.at(x+nx*(d-1.5),y+ny*(d-1.5))));
                if(strength>best){best=strength;offset=d;}
            }
            if(best>=.055)points.push_back({x+nx*offset,y+ny*offset});
        }
        if(points.size()<size_t(samples*.55))return false;
        Point mean;for(auto p:points){mean.x+=p.x/points.size();mean.y+=p.y/points.size();}
        double xx=0,xy=0,yy=0;for(auto p:points){double x=p.x-mean.x,y=p.y-mean.y;xx+=x*x;xy+=x*y;yy+=y*y;}
        double angle=.5*std::atan2(2*xy,xx-yy);Line line{-std::sin(angle),std::cos(angle),0};line.c=-line.x*mean.x-line.y*mean.y;
        if(std::abs(line.x*nx+line.y*ny)<.96)return false;
        double error=0;for(auto p:points){double e=line.x*p.x+line.y*p.y+line.c;error+=e*e;}
        double fitRms=std::sqrt(error/points.size());if(fitRms>1.3)return false;rms=std::max(rms,fitRms);lines[side]=line;
    }
    auto refined=polygon;
    for(int i=0;i<4;i++) {
        auto a=lines[(i+3)%4],b=lines[i];double det=a.x*b.y-a.y*b.x;if(std::abs(det)<.1)return false;
        Point p{(a.y*b.c-a.c*b.y)/det,(a.c*b.x-a.x*b.c)/det};
        if(std::hypot(p.x-polygon[i].x,p.y-polygon[i].y)>12 || p.x<0 || p.y<0 || p.x>=image.width || p.y>=image.height)return false;
        refined[i]=p;
    }
    polygon=std::move(refined);return true;
}
struct Candidate {
    std::array<Point,4> corners;
    double area=0,rectangularity=0,edgeSupport=0,minSideSupport=0,contrast=0,centerDistance=0,score=0,threshold=0;
    int pixels=0;bool bright=false,lineRefined=false;double lineFitRms=0;
};
void pointsJson(std::ostream& out,const std::array<Point,4>& points) {
    out<<'[';for(int i=0;i<4;i++){if(i)out<<',';out<<'['<<points[i].x<<','<<points[i].y<<']';}out<<']';
}
}
std::array<Point,4> documentCorners(const Image& image,std::string* diagnostics) {
    const auto start=std::chrono::steady_clock::now();
    if(diagnostics)*diagnostics="{\"schema\":1,\"reason\":\"invalid-input\",\"stage\":\"input\",\"fallback\":null,\"finalCorners\":null}";
    if(image.width<32 || image.height<32 || image.width>8192 || image.height>8192 || uint64_t(image.width)*image.height>12600000 || image.rgb.size()!=size_t(image.width)*image.height*3)
        throw std::invalid_argument("Invalid corner detector input");
    const int step=std::max(1,(std::max(image.width,image.height)+399)/400);
    Gray gray;gray.width=(image.width+step-1)/step;gray.height=(image.height+step-1)/step;gray.pixels.resize(size_t(gray.width)*gray.height);
    std::array<int,256> histogram{};
    for(int y=0;y<gray.height;y++)for(int x=0;x<gray.width;x++) {
        double sum=0;int samples=0;
        for(int yy=y*step;yy<std::min(image.height,(y+1)*step);yy++)for(int xx=x*step;xx<std::min(image.width,(x+1)*step);xx++) {
            auto p=image.rgb.data()+(size_t(yy)*image.width+xx)*3;sum+=.2126*p[0]+.7152*p[1]+.0722*p[2];samples++;
        }
        float value=float(sum/(255*samples));gray.pixels[size_t(y)*gray.width+x]=value;histogram[std::clamp(int(value*255),0,255)]++;
    }
    double totalMean=0;for(int i=0;i<256;i++)totalMean+=i*histogram[i];
    double sum=0,bestVariance=-1;int weight=0,otsu=127,total=gray.width*gray.height;
    for(int i=0;i<255;i++) {
        weight+=histogram[i];sum+=i*histogram[i];if(!weight || weight==total)continue;
        double difference=sum/weight-(totalMean-sum)/(total-weight),variance=double(weight)*(total-weight)*difference*difference;
        if(variance>bestVariance){bestVariance=variance;otsu=i;}
    }
    std::vector<double> thresholds{.04,.08,.12,.25,.38,.5,.62,.75,.87,.94};
    double adaptive=(otsu+.5)/255.;if(std::all_of(thresholds.begin(),thresholds.end(),[&](double t){return std::abs(t-adaptive)>.025;}))thresholds.push_back(adaptive);
    std::vector<uint8_t> mask(total),seen(total);std::vector<int> queue;queue.reserve(total);
    std::vector<Candidate> candidates;int components=0,borderRejected=0,sizeRejected=0,shapeRejected=0,edgeRejected=0;
    for(double threshold:thresholds)for(bool bright:{false,true}) {
        for(int i=0;i<total;i++)mask[i]=bright?gray.pixels[i]>threshold:gray.pixels[i]<threshold;
        std::fill(seen.begin(),seen.end(),0);
        for(int seed=0;seed<total;seed++)if(mask[seed] && !seen[seed]) {
            components++;queue.clear();queue.push_back(seed);seen[seed]=1;bool border=false;std::vector<Point> boundary;
            for(size_t head=0;head<queue.size();head++) {
                int at=queue[head],x=at%gray.width,y=at/gray.width;bool edge=false;
                border|=x==0 || y==0 || x==gray.width-1 || y==gray.height-1;
                for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++)if(dx || dy) {
                    int xx=x+dx,yy=y+dy;if(xx<0 || yy<0 || xx>=gray.width || yy>=gray.height){edge=true;continue;}
                    int next=yy*gray.width+xx;
                    if(!mask[next]){edge=true;continue;}if(!seen[next]){seen[next]=1;queue.push_back(next);}
                }
                if(edge)boundary.push_back({double(x),double(y)});
            }
            if(border){borderRejected++;continue;}
            if(queue.size()<32 || boundary.size()<4){sizeRejected++;continue;}
            auto polygon=hull(std::move(boundary));double hullArea=polygonArea(polygon);
            if(polygon.size()<4 || hullArea<total*.025 || hullArea>total*.92){sizeRejected++;continue;}
            while(polygon.size()>4) {
                size_t remove=0;double least=std::numeric_limits<double>::infinity();
                for(size_t i=0;i<polygon.size();i++) {double loss=std::abs(cross(polygon[(i+polygon.size()-1)%polygon.size()],polygon[i],polygon[(i+1)%polygon.size()]));if(loss<least){least=loss;remove=i;}}
                polygon.erase(polygon.begin()+remove);
            }
            double lineFitRms=0;bool lineRefined=refineEdges(gray,polygon,lineFitRms);
            double area=polygonArea(polygon),rectangularity=area/hullArea;
            bool shape=rectangularity>=.86;
            for(int i=0;i<4;i++) {
                auto a=polygon[(i+3)%4],b=polygon[i],c=polygon[(i+1)%4];double u=std::hypot(a.x-b.x,a.y-b.y),v=std::hypot(c.x-b.x,c.y-b.y);
                double cosine=((a.x-b.x)*(c.x-b.x)+(a.y-b.y)*(c.y-b.y))/(u*v);
                if(u<std::min(gray.width,gray.height)*.08 || !std::isfinite(cosine) || std::abs(cosine)>.88)shape=false;
            }
            if(!shape){shapeRejected++;continue;}
            auto first=std::min_element(polygon.begin(),polygon.end(),[](Point a,Point b){return a.x+a.y<b.x+b.y;});std::rotate(polygon.begin(),first,polygon.end());
            Candidate candidate;candidate.lineRefined=lineRefined;candidate.lineFitRms=lineFitRms;candidate.area=area/total;candidate.rectangularity=rectangularity;candidate.threshold=threshold;candidate.bright=bright;candidate.pixels=int(queue.size());
            double supportSum=0,contrastSum=0;candidate.minSideSupport=1;
            for(int side=0;side<4;side++) {
                auto a=polygon[side],b=polygon[(side+1)%4];double length=std::hypot(b.x-a.x,b.y-a.y),nx=-(b.y-a.y)/length,ny=(b.x-a.x)/length;
                int samples=std::max(16,int(length)),supported=0;double contrast=0;
                for(int k=0;k<samples;k++) {
                    double t=(k+.5)/samples,x=a.x+t*(b.x-a.x),y=a.y+t*(b.y-a.y),strength=0;
                    for(int offset=-2;offset<=2;offset++)strength=std::max(strength,std::abs(double(gray.at(x+nx*(offset+2),y+ny*(offset+2))-gray.at(x+nx*(offset-2),y+ny*(offset-2)))));
                    supported+=strength>=.055;contrast+=strength;
                }
                double support=double(supported)/samples;supportSum+=support;contrastSum+=contrast/samples;candidate.minSideSupport=std::min(candidate.minSideSupport,support);
            }
            candidate.edgeSupport=supportSum/4;candidate.contrast=contrastSum/4;
            if(candidate.edgeSupport<.65 || candidate.minSideSupport<.4 || candidate.contrast<.065){edgeRejected++;continue;}
            Point center;for(int i=0;i<4;i++){center.x+=polygon[i].x/(4*gray.width);center.y+=polygon[i].y/(4*gray.height);candidate.corners[i]={(polygon[i].x+.5)*step/image.width,(polygon[i].y+.5)*step/image.height};}
            candidate.centerDistance=std::hypot(center.x-.5,center.y-.5);
            candidate.score=std::sqrt(candidate.area)*std::min(1.,candidate.rectangularity)*candidate.edgeSupport*std::exp(-6*candidate.centerDistance*candidate.centerDistance);
            candidates.push_back(candidate);
        }
    }
    std::sort(candidates.begin(),candidates.end(),[](const Candidate& a,const Candidate& b){return a.score>b.score;});
    const bool fallback=candidates.empty();std::array<Point,4> result=fallback?std::array<Point,4>{{{.05,.05},{.95,.05},{.95,.95},{.05,.95}}}:candidates.front().corners;
    if(diagnostics) {
        std::ostringstream json;json.imbue(std::locale::classic());json<<std::setprecision(10);
        json<<"{\"schema\":1,\"reason\":\""<<(fallback?"no-supported-quadrilateral":"proposed")<<"\",\"stage\":\"complete\",\"fallback\":"<<(fallback?"true":"false")
            <<",\"method\":\"both-polarity-components-convex-hull-edge-validation\",\"width\":"<<image.width<<",\"height\":"<<image.height<<",\"analysisWidth\":"<<gray.width<<",\"analysisHeight\":"<<gray.height<<",\"analysisScaleToInput\":"<<step
            <<",\"coordinateSpace\":\"normalized_input_bitmap; TL,TR,BR,BL\",\"componentAnalysisUsed\":true,\"connectedComponents\":"<<components<<",\"candidateCount\":"<<candidates.size()
            <<",\"candidateRejectCounts\":{\"touchesBorder\":"<<borderRejected<<",\"size\":"<<sizeRejected<<",\"shape\":"<<shapeRejected<<",\"edgeSupport\":"<<edgeRejected<<"},\"thresholds\":{\"lumaLevels\":[";
        for(size_t i=0;i<thresholds.size();i++){if(i)json<<',';json<<thresholds[i];}
        json<<"],\"hullAreaFractionMin\":0.025,\"hullAreaFractionMax\":0.92,\"quadToHullAreaMin\":0.86,\"meanSideSupportMin\":0.65,\"eachSideSupportMin\":0.4,\"sideContrastMin\":0.065},\"candidateCorners\":";pointsJson(json,result);
        json<<",\"finalCorners\":";pointsJson(json,result);json<<",\"candidates\":[";
        for(size_t i=0;i<std::min(size_t(12),candidates.size());i++) {
            const auto& c=candidates[i];if(i)json<<',';json<<"{\"rankScore\":"<<c.score<<",\"areaFraction\":"<<c.area<<",\"quadToHullArea\":"<<c.rectangularity<<",\"meanSideSupport\":"<<c.edgeSupport<<",\"minSideSupport\":"<<c.minSideSupport<<",\"meanSideContrast\":"<<c.contrast<<",\"centerDistance\":"<<c.centerDistance<<",\"lineRefined\":"<<(c.lineRefined?"true":"false")<<",\"lineFitMaxSideRms\":"<<c.lineFitRms<<",\"componentPixels\":"<<c.pixels<<",\"threshold\":"<<c.threshold<<",\"polarity\":\""<<(c.bright?"bright":"dark")<<"\",\"corners\":";pointsJson(json,c.corners);json<<'}';
        }
        json<<"],\"elapsedMs\":"<<std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count()<<'}';*diagnostics=json.str();
    }
    return result;
}
} // namespace luma
