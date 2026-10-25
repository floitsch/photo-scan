// Copyright (C) 2026 Toit contributors.
// Host check: python3 tools/test_native.py --output build/native-artifacts
#include "../app/src/main/cpp/fusion.h"
#include "../app/src/main/cpp/raster.h"
#include <algorithm>
#include <cassert>
#include <cmath>
#include <iostream>
#include <limits>
#include <filesystem>
#include <fstream>
#include <string>
#include <random>
#include <chrono>
#include <optional>
#include <cstring>
#include <zlib.h>
#include <sys/resource.h>

using namespace luma;
constexpr double pi=3.14159265358979323846;
double detail(double x,double y) {
    return std::clamp(.46+.16*std::sin(2*pi*(.36*x+.08*y))+.13*std::sin(2*pi*(.11*x+.33*y))+
           .06*std::sin(2*pi*(.027*x+.037*y))+.06*std::sin(2*pi*(.013*x-.019*y)),.02,.95);
}
double texture(double x,double y) {
    double v=.48;
    for(int i=1;i<=13;i++) v+=.028*std::sin(x*(.031*i+.003*i*i)+y*(.017*i-.004*i*i)+i*i);
    return std::clamp(v+.11*std::sin(.14*x)*std::cos(.12*y),.04,.94);
}
Image synthetic(int w,int h,Homography pose,bool high=false,int glare=-1) {
    Image image(w,h);auto back=inverse(pose);
    for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
        auto p=project(back,{double(x),double(y)});double value=high?detail(p.x,p.y):texture(p.x,p.y);
        if(glare>=0 && x>=glare && x<glare+35) value=.98;
        uint8_t q=uint8_t(std::lround(value*255));
        for(int c=0;c<3;c++) image.rgb[(size_t(y)*w+x)*3+c]=q;
    }
    return image;
}
double mse(const Image& im,bool high) {
    double error=0;int n=0;
    for(int y=12;y<im.height-12;y++) for(int x=12;x<im.width-12;x++) {
        double truth=high?detail((x+.5)/2-.5,(y+.5)/2-.5):texture((x+.5)/2-.5,(y+.5)/2-.5);
        error+=std::pow(im.rgb[(size_t(y)*im.width+x)*3]/255.-truth,2);n++;
    }
    return error/n;
}
void ppm(const std::filesystem::path& path,const Image& image) {
    std::ofstream out(path,std::ios::binary);
    out<<"P6\n"<<image.width<<" "<<image.height<<"\n255\n";
    out.write(reinterpret_cast<const char*>(image.rgb.data()),std::streamsize(image.rgb.size()));
    if(!out) throw std::runtime_error("Cannot write image artifact");
}
Image groundTruth(int w,int h,bool high) {
    Image image(w*2,h*2);
    for(int y=0;y<image.height;y++) for(int x=0;x<image.width;x++) {
        double value=high?detail((x+.5)/2-.5,(y+.5)/2-.5):texture((x+.5)/2-.5,(y+.5)/2-.5);
        for(int c=0;c<3;c++) image.rgb[(size_t(y)*image.width+x)*3+c]=uint8_t(std::lround(value*255));
    }
    return image;
}
// Decode the lossless streaming output independently, including chunk CRCs and
// PNG Sub filtering. This checks container validity as well as exact RGB pixels.
Image readPng(const std::filesystem::path& path) {
    std::ifstream in(path,std::ios::binary);
    std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(in)),{});
    const uint8_t signature[]={137,80,78,71,13,10,26,10};
    assert(bytes.size()>=8 && !std::memcmp(bytes.data(),signature,8));
    auto be=[](const uint8_t* p){return uint32_t(p[0])<<24|uint32_t(p[1])<<16|uint32_t(p[2])<<8|p[3];};
    int w=0,h=0;std::vector<uint8_t> compressed;bool end=false;
    for(size_t offset=8;offset<bytes.size();) {
        assert(offset+12<=bytes.size());size_t n=be(bytes.data()+offset);assert(offset+n+12<=bytes.size());
        const uint8_t* type=bytes.data()+offset+4;const uint8_t* data=type+4;
        assert(uint32_t(crc32(0,type,uInt(n+4)))==be(data+n));
        if(!std::memcmp(type,"IHDR",4)) {assert(n==13 && data[8]==8 && data[9]==2);w=int(be(data));h=int(be(data+4));}
        if(!std::memcmp(type,"IDAT",4)) compressed.insert(compressed.end(),data,data+n);
        if(!std::memcmp(type,"IEND",4)) {assert(n==0 && offset+12==bytes.size());end=true;}
        offset+=n+12;
    }
    assert(end && w>0 && h>0);Image image(w,h);std::vector<uint8_t> filtered((size_t(w)*3+1)*h);
    uLongf n=filtered.size();assert(uncompress(filtered.data(),&n,compressed.data(),compressed.size())==Z_OK && n==filtered.size());
    for(int y=0;y<h;y++) {
        const uint8_t* src=filtered.data()+size_t(y)*(size_t(w)*3+1);assert(src[0]==1);
        uint8_t* dst=image.rgb.data()+size_t(y)*w*3;
        for(int x=0;x<w*3;x++) dst[x]=uint8_t(src[x+1]+(x>=3?dst[x-3]:0));
    }
    return image;
}
void storageChecks(const std::filesystem::path& artifacts,bool fullSize) {
    auto work=std::filesystem::temp_directory_path()/("lumascan-native-"+std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    std::filesystem::create_directories(work);
    Scanner resident,disk;disk.configureStorage((work/"frames").string());
    for(int i=0;i<5;i++) {Homography pose{1,.002,(i%3)*.31,-.001,1,(i/3)*.37,0,0,1};auto image=synthetic(160,128,pose);resident.addKnown(image,pose);disk.addKnown(std::move(image),pose);}
    assert(disk.frame(0).image.rgb.empty() && !disk.frame(0).thumbnail.rgb.empty());
    assert(disk.loadFrame(4).rgb==resident.frame(4).image.rgb);
    disk.exportLastSource((work/"latest-disk.png").string());resident.exportLastSource((work/"latest-resident.png").string());
    assert(readPng(work/"latest-disk.png").rgb==resident.frame(4).image.rgb);
    assert(readPng(work/"latest-resident.png").rgb==resident.frame(4).image.rgb);
    auto expected=resident.render(2),actual=disk.render(2);
    assert(expected.image.rgb==actual.image.rgb && expected.supported==actual.supported && expected.rejected==actual.rejected);
    assert(resident.render(2,80).image.rgb==disk.render(2,80).image.rgb);
    auto info=disk.renderToFiles((work/"fused.png").string(),(work/"fused.rgb").string(),2,0);
    assert(info[0]==320 && info[1]==256 && info[2]==actual.supported);
    assert(RasterReader((work/"fused.rgb").string()).readImage().rgb==actual.image.rgb);
    assert(readPng(work/"fused.png").rgb==actual.image.rgb);
    if(!artifacts.empty()) {std::filesystem::copy_file(work/"fused.png",artifacts/"streamed-fusion.png",std::filesystem::copy_options::overwrite_existing);ppm(artifacts/"streamed-fusion.ppm",actual.image);}
    // Reset removes accepted source spills and preserves both user outputs and unrelated files.
    std::ofstream(work/"frames"/"keep.txt")<<"User file";
    auto spill=disk.frame(0).diskPath;disk.reset();assert(!std::filesystem::exists(spill));
    assert(std::filesystem::exists(work/"fused.rgb") && std::filesystem::exists(work/"frames"/"keep.txt"));
    Image colors(96,64);for(int y=0;y<64;y++) for(int x=0;x<96;x++) {
        size_t p=(size_t(y)*96+x)*3;colors.rgb[p]=uint8_t(30+x);colors.rgb[p+1]=uint8_t(40+y);colors.rgb[p+2]=90;
    }
    Image first(96,17),second(96,47);std::copy_n(colors.rgb.begin(),first.rgb.size(),first.rgb.begin());std::copy(colors.rgb.begin()+first.rgb.size(),colors.rgb.end(),second.rgb.begin());
    auto raster=(work/"import.rgb").string();appendRasterRows(raster,first,0,64);
    bool rejected=false;try {RasterReader reader(raster);} catch(const std::invalid_argument&) {rejected=true;}assert(rejected);
    rejected=false;try {appendRasterRows(raster,second,16,64);} catch(const std::invalid_argument&) {rejected=true;}assert(rejected);
    appendRasterRows(raster,second,17,64);assert(RasterReader(raster).readImage().rgb==colors.rgb);
    std::array<Point,4> corners{{{0,0},{1,0},{1,1},{0,1}}};
    auto shape=exportRaster(raster,(work/"identity.png").string(),corners,0,{1,1,1});assert(shape[0]==96 && shape[1]==64);assert(readPng(work/"identity.png").rgb==colors.rgb);
    std::array<Point,4> rotated{{{0,1},{0,0},{1,0},{1,1}}};
    shape=exportRaster(raster,(work/"rotated.png").string(),rotated,0,{1,1,1});assert(shape[0]==64 && shape[1]==96);
    auto rotation=readPng(work/"rotated.png");
    for(int y=0;y<96;y++) for(int x=0;x<64;x++) for(int c=0;c<3;c++) assert(rotation.rgb[(size_t(y)*64+x)*3+c]==colors.rgb[(size_t(63-x)*96+y)*3+c]);
    std::array<Point,4> perspective{{{.12,.08},{.91,.17},{.85,.9},{.04,.81}}};
    shape=exportRaster(raster,(work/"perspective.png").string(),perspective,1.5,{1,1,1});auto corrected=readPng(work/"perspective.png");
    assert(std::abs(double(shape[0])/shape[1]-1.5)<.04);
    for(int i=0;i<4;i++) {
        int x=(i==1 || i==2)?corrected.width-1:0,y=i>=2?corrected.height-1:0;
        size_t p=(size_t(y)*corrected.width+x)*3;
        assert(std::abs(int(corrected.rgb[p])-(30+perspective[i].x*95))<2 && std::abs(int(corrected.rgb[p+1])-(40+perspective[i].y*63))<2);
    }
    exportRaster(raster,(work/"balanced.png").string(),corners,0,{2,.5,1});auto balanced=readPng(work/"balanced.png");
    assert(balanced.rgb[0]>colors.rgb[0] && balanced.rgb[1]<colors.rgb[1] && balanced.rgb[2]==colors.rgb[2]);
    if(!artifacts.empty()) for(const char* name:{"identity.png","rotated.png","perspective.png","balanced.png"}) std::filesystem::copy_file(work/name,artifacts/name,std::filesystem::copy_options::overwrite_existing);
    if(fullSize) {
        Scanner large;large.configureStorage((work/"large").string());
        // Exactly 12.6MP and 24 sources exercise persistent storage without retaining source RGB.
        for(int i=0;i<24;i++) {Image image(4200,3000);std::fill(image.rgb.begin(),image.rgb.end(),128);large.addKnown(std::move(image),identity());assert(large.frame(i).image.rgb.empty());}
        assert(large.count()==24);auto preview=large.render(2,128);assert(preview.image.width==128 && preview.image.height==91);
        auto source=large.loadFrame(23);assert(source.width==4200 && source.height==3000);source=Image();large.reset();
        Image image(4200,3000);std::fill(image.rgb.begin(),image.rgb.end(),128);large.addKnown(std::move(image),identity());
        auto result=large.renderToFiles((work/"large.png").string(),(work/"large.rgb").string(),2,0);
        assert(result[0]==8400 && result[1]==6000);RasterReader final((work/"large.rgb").string());assert(final.width==8400 && final.height==6000);
        // Inspect rows without decoding a 50.4MP PNG into a resident test image.
        for(int y:{0,2999,5999}) {auto row=final.row(y);for(int x=0;x<8400*3;x++) assert(row[x]==128);}
        struct rusage usage{};getrusage(RUSAGE_SELF,&usage);std::cout<<"24 x 12.6MP capture / 50.4MP streamed output peak RSS "<<usage.ru_maxrss/1024.<<" MiB\n";
        assert(usage.ru_maxrss<256*1024);large.reset();
        Scanner trackedLarge;trackedLarge.configureStorage((work/"tracked-large").string());
        assert(trackedLarge.add(synthetic(4200,3000,identity())).status==0);
        Homography pose{1,0,.45,0,1,.65,0,0,1};auto report=trackedLarge.add(synthetic(4200,3000,pose));
        auto center=project(report.transform,{2100,1500});double error=std::hypot(center.x-2100.45,center.y-1500.65);
        getrusage(RUSAGE_SELF,&usage);std::cout<<"12.6MP real registration status "<<report.status<<", center error "<<error<<", peak RSS "<<usage.ru_maxrss/1024.<<" MiB\n";
        assert(report.status==0 && error<.2 && usage.ru_maxrss<256*1024);trackedLarge.reset();
    }
    std::filesystem::remove_all(work);
}
std::optional<double> diagnosticNumber(const std::string& json,const std::string& key) {
    auto at=json.find("\""+key+"\":");assert(at!=std::string::npos);at+=key.size()+3;
    if(json.compare(at,4,"null")==0)return std::nullopt;
    double number=std::stod(json.substr(at));assert(std::isfinite(number));return number;
}
size_t occurrences(const std::string& haystack,const std::string& needle) {
    size_t count=0,at=0;while((at=haystack.find(needle,at))!=std::string::npos){count++;at+=needle.size();}return count;
}
void diagnosticChecks(const std::filesystem::path& output) {
    auto save=[&](const char* name,const std::string& json) {
        assert(json.front()=='{' && json.back()=='}' && json.find("NaN")==std::string::npos && json.find(":nan")==std::string::npos);
        assert(diagnosticNumber(json,"schema")==1);
        if(!output.empty()) {std::ofstream file(output/(std::string(name)+".json"));file<<json;assert(file.good());}
    };
    Scanner scanner;Image flat(384,288);std::fill(flat.rgb.begin(),flat.rgb.end(),128);
    assert(scanner.add(flat).status==1);auto json=scanner.lastAttemptDiagnostics();
    assert(json.find("\"reason\":\"blur\"")!=std::string::npos);
    assert(!diagnosticNumber(json,"inliers") && !diagnosticNumber(json,"rms") && *diagnosticNumber(json,"sharpness")<1e-6);
    assert(scanner.diagnosticThumbnail().rgb==flat.rgb);save("attempt-blur",json);
    Image stripes(384,288);for(int y=0;y<288;y++)for(int x=0;x<384;x++)for(int c=0;c<3;c++)stripes.rgb[(size_t(y)*384+x)*3+c]=x%8<4?230:20;
    assert(scanner.add(std::move(stripes)).status==2);json=scanner.lastAttemptDiagnostics();
    assert(json.find("\"reason\":\"insufficient-reference-features\"")!=std::string::npos);
    assert(diagnosticNumber(json,"detectedReferenceFeatures")==0 && *diagnosticNumber(json,"sharpness")>.35);save("attempt-no-reference-features",json);
    assert(scanner.add(synthetic(384,288,identity())).status==0);json=scanner.lastAttemptDiagnostics();
    assert(json.find("\"reason\":\"accepted-reference\"")!=std::string::npos && !diagnosticNumber(json,"rms"));save("attempt-reference",json);
    assert(scanner.add(synthetic(384,288,identity())).status==3);json=scanner.lastAttemptDiagnostics();
    assert(json.find("\"reason\":\"duplicate\"")!=std::string::npos && *diagnosticNumber(json,"inliers")>=12 && *diagnosticNumber(json,"minimumCornerDisplacement")<.16);
    auto coarse=json.substr(json.find("\"coarse\":"));
    double attempted=*diagnosticNumber(coarse,"attempted"),tracked=*diagnosticNumber(coarse,"tracked");
    assert(tracked+*diagnosticNumber(coarse,"bounds")+*diagnosticNumber(coarse,"lowGradient")+*diagnosticNumber(coarse,"patchError")==attempted);
    assert(occurrences(json,"\"reference\":[")>0 && occurrences(json,"\"reference\":[")<=48);save("attempt-duplicate",json);
    auto beforeRender=json;scanner.render(1,128);assert(scanner.lastAttemptDiagnostics()==beforeRender);
    Image noise(384,288);std::mt19937 random(17);for(auto& v:noise.rgb)v=uint8_t(random()%256);
    assert(scanner.add(noise).status==2);json=scanner.lastAttemptDiagnostics();
    assert(json.find("\"reason\":\"coarse-ransac\"")!=std::string::npos);assert(scanner.diagnosticThumbnail().rgb==noise.rgb);
    assert(json.find("\"result\":\"insufficient-")!=std::string::npos);save("attempt-coarse-failure",json);
    Scanner large;Image largeFlat(1024,768);std::fill(largeFlat.rgb.begin(),largeFlat.rgb.end(),150);assert(large.add(std::move(largeFlat)).status==1);
    assert(large.diagnosticThumbnail().width==960 && large.diagnosticThumbnail().height==720 && large.diagnosticThumbnail().rgb.size()==960*720*3);
    assert(large.add(synthetic(1024,768,identity())).status==0);Homography pose{1,0,.45,0,1,.65,0,0,1};assert(large.add(synthetic(1024,768,pose)).status==0);
    json=large.lastAttemptDiagnostics();assert(diagnosticNumber(json,"trackingScaleToSource")==2);
    auto fine=json.substr(json.find("\"fine\":"));assert(*diagnosticNumber(fine,"inliers")>=12 && diagnosticNumber(fine,"coordinateScaleToSource")==1);
    assert(occurrences(json,"\"reference\":[")<=48);save("attempt-fine",json);
    Scanner full;for(int i=0;i<24;i++)full.addKnown(Image(32,32),identity());assert(full.add(Image(32,32)).status==4);
    json=full.lastAttemptDiagnostics();assert(json.find("\"reason\":\"full\"")!=std::string::npos && !diagnosticNumber(json,"sharpness"));save("attempt-full",json);
    scanner.reset();assert(scanner.lastAttemptDiagnostics().find("\"reason\":\"reset\"")!=std::string::npos && scanner.diagnosticThumbnail().rgb.empty());
    bool invalid=false;try {scanner.add(Image(20,20));}catch(const std::invalid_argument&){invalid=true;}assert(invalid);
    json=scanner.lastAttemptDiagnostics();assert(json.find("\"reason\":\"invalid-input\"")!=std::string::npos);save("attempt-invalid",json);
    scanner.noteInputFailure(0,0);json=scanner.lastAttemptDiagnostics();assert(!diagnosticNumber(json,"width") && !diagnosticNumber(json,"height") && !diagnosticNumber(json,"elapsedMs"));save("attempt-conversion-failure",json);
    Image dark(128,96);std::fill(dark.rgb.begin(),dark.rgb.end(),16);std::string cornersJson;auto fallback=documentCorners(dark,&cornersJson);
    assert(cornersJson.find("\"fallback\":true")!=std::string::npos && cornersJson.find("no-supported-quadrilateral")!=std::string::npos);
    assert(diagnosticNumber(cornersJson,"candidateCount")==0 && fallback[0].x==.05 && fallback[2].x==.95);save("corners-fallback",cornersJson);
    Image paper=dark;for(int y=12;y<84;y++)for(int x=16;x<112;x++)for(int c=0;c<3;c++)paper.rgb[(size_t(y)*128+x)*3+c]=240;
    auto points=documentCorners(paper,&cornersJson);assert(cornersJson.find("\"fallback\":false")!=std::string::npos);
    assert(*diagnosticNumber(cornersJson,"candidateCount")>0 && *diagnosticNumber(cornersJson,"connectedComponents")>0);
    for(auto p:points)assert(p.x>=0 && p.x<=1 && p.y>=0 && p.y<=1);
    assert(cornersJson.find("\"componentAnalysisUsed\":true")!=std::string::npos);save("corners-proposed",cornersJson);
}
Image rectangularScene(int width,int height,const std::array<Point,4>& corners,bool bright,bool frame=false,bool distractors=false) {
    Image image(width,height);std::vector<Point> target;for(auto p:corners)target.push_back({p.x*width,p.y*height});
    Homography map;assert(fit({{0,0},{1,0},{1,1},{0,1}},target,map));auto back=inverse(map);
    for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
        auto p=project(back,{double(x),double(y)});double value=bright?.10:.93;
        if(p.x>=0 && p.y>=0 && p.x<=1 && p.y<=1) {
            if(frame && std::min({p.x,p.y,1-p.x,1-p.y})<.06)value=.03;
            else value=bright?.82+.07*std::sin(p.x*43)*std::sin(p.y*57):.14+.08*std::sin(p.x*43)*std::sin(p.y*57);
        }
        if(distractors && x>width*.02 && x<width*.19 && y>height*.03 && y<height*.29)value=bright?.85:.08;
        for(int c=0;c<3;c++)image.rgb[(size_t(y)*width+x)*3+c]=uint8_t(value*255);
    }
    return image;
}
void boundaryChecks(const std::filesystem::path& output) {
    const std::array<Point,4> expected{{{.26,.18},{.76,.22},{.72,.83},{.23,.78}}};
    for(bool bright:{false,true})for(bool frame:{false,true}) {
        auto scene=rectangularScene(640,480,expected,bright,frame,true);std::string diagnostics;auto corners=documentCorners(scene,&diagnostics);
        assert(diagnostics.find("\"fallback\":false")!=std::string::npos);
        double error=0;for(int i=0;i<4;i++)error=std::max(error,std::hypot(corners[i].x-expected[i].x,corners[i].y-expected[i].y));
        std::cout<<"Boundary "<<(bright?"bright":"dark")<<(frame?" framed":"")<<" maximum normalized corner error "<<error<<"\n";assert(error<.018);
        if(!output.empty()) {auto name=std::string("boundary-")+(bright?"bright":"dark")+(frame?"-framed":"");ppm(output/(name+".ppm"),scene);std::ofstream(output/(name+".json"))<<diagnostics;}
    }
    Image circle(320,240);std::fill(circle.rgb.begin(),circle.rgb.end(),240);
    for(int y=0;y<240;y++)for(int x=0;x<320;x++)if(std::hypot(x-160.,y-120.)<75)for(int c=0;c<3;c++)circle.rgb[(size_t(y)*320+x)*3+c]=20;
    std::string diagnostics;documentCorners(circle,&diagnostics);assert(diagnostics.find("\"fallback\":true")!=std::string::npos);
}
Image nonperiodicScene(int width,int height,const Homography& pose) {
    auto back=inverse(pose);Image image(width,height);
    auto value=[](int x,int y) {
        uint32_t h=uint32_t(x)*0x8da6b343u^uint32_t(y)*0xd8163841u^0x63d83595u;
        h^=h>>16;h*=0x7feb352du;h^=h>>15;h*=0x846ca68bu;h^=h>>16;return double(h&65535)/65535;
    };
    for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
        auto p=project(back,{double(x),double(y)});double light=.08;
        for(auto octave:std::array<std::array<double,2>,4>{{{{8,.20}},{{20,.30}},{{55,.30}},{{123,.20}}}}) {
            double sx=p.x/octave[0],sy=p.y/octave[0];int ix=int(std::floor(sx)),iy=int(std::floor(sy));double fx=sx-ix,fy=sy-iy;
            fx=fx*fx*(3-2*fx);fy=fy*fy*(3-2*fy);
            light+=.84*octave[1]*((1-fy)*((1-fx)*value(ix,iy)+fx*value(ix+1,iy))+fy*((1-fx)*value(ix,iy+1)+fx*value(ix+1,iy+1)));
        }
        for(int c=0;c<3;c++)image.rgb[(size_t(y)*width+x)*3+c]=uint8_t(std::lround(light*255));
    }
    return image;
}
void reacquisitionChecks(const std::filesystem::path& output) {
    Scanner scanner;assert(scanner.add(nonperiodicScene(1024,768,identity())).status==0);
    for(double shift:{24.,48.,72.,96.,120.,144.,168.,192.}) {
        Homography pose{1,0,shift,0,1,.65,0,0,1};auto report=scanner.add(nonperiodicScene(1024,768,pose));
        if(report.status!=0)std::cerr<<scanner.lastAttemptDiagnostics()<<"\n";
        assert(report.status==0);
        if(shift==96) {
            auto diagnostics=scanner.lastAttemptDiagnostics();auto section=diagnostics.substr(diagnostics.find("\"coarse\":"));section.resize(section.find("\"fine\":"));
            double attempted=*diagnosticNumber(section,"attempted"),bounds=*diagnosticNumber(section,"bounds"),reduced=*diagnosticNumber(section,"featuresWithReducedStartLevel");
            assert(section.find("previous-homography-warped-pyramid")!=std::string::npos && reduced>0 && bounds<attempted*.35);
            std::cout<<"Valid pyramid levels: "<<reduced<<" features start finer, "<<bounds<<" / "<<attempted<<" bounds rejections\n";
        }
    }
    // Return abruptly toward the first view after a long motion. The last pose
    // now predicts the wrong search basin; an independent acquisition must recover.
    Homography returned{1,0,3.35,0,1,-1.7,0,0,1};auto report=scanner.add(nonperiodicScene(1024,768,returned));
    auto diagnostics=scanner.lastAttemptDiagnostics();double error=0;
    for(Point p:std::vector<Point>{{200,160},{820,160},{820,610},{200,610},{512,384}}) {
        auto a=project(report.transform,p),b=project(returned,p);error=std::max(error,std::hypot(a.x-b.x,a.y-b.y));
    }
    std::cout<<"Abrupt return status "<<report.status<<", max pose error "<<error<<" source pixels\n";
    assert(report.status==0 && error<.2);
    assert(diagnostics.find("translation-pyramid-identity-reacquisition")!=std::string::npos);
    assert(occurrences(diagnostics,"\"trackingMethod\":")<=4);
    assert(*diagnosticNumber(diagnostics,"featuresWithReducedStartLevel")>0);
    if(!output.empty())std::ofstream(output/"attempt-reacquisition.json")<<diagnostics;
    size_t accepted=scanner.count();Image unrelated(1024,768);std::mt19937 random(813);
    for(auto& value:unrelated.rgb)value=uint8_t(random()%256);
    assert(scanner.add(std::move(unrelated)).status==2 && scanner.count()==accepted);
    auto failure=scanner.lastAttemptDiagnostics();assert(failure.find("translation-pyramid-identity-reacquisition")!=std::string::npos);
    if(!output.empty())std::ofstream(output/"attempt-unrelated-reacquisition.json")<<failure;
}
void zoomChecks() {
    Scanner scanner;assert(scanner.add(synthetic(1536,1152,identity())).status==0);
    for(double zoom:{1.04,1.08,1.12,1.16,1.2,1.25,1.3,1.35,1.4}) {
        double angle=.035,c=std::cos(angle)*zoom,s=std::sin(angle)*zoom;
        Homography pose{c,-s,(1-c)*768+s*576+.4,s,c,-s*768+(1-c)*576+.6,0,0,1};
        auto report=scanner.add(synthetic(1536,1152,pose));double maximum=0;
        for(Point p:std::vector<Point>{{400,300},{1100,300},{1100,850},{400,850},{768,576}}) {auto a=project(report.transform,p),b=project(pose,p);maximum=std::max(maximum,std::hypot(a.x-b.x,a.y-b.y));}
        std::cout<<"Zoom "<<zoom<<" + rotation status "<<report.status<<", maximum pose error "<<maximum<<" source pixels\n";
        if(report.status!=0)std::cerr<<scanner.lastAttemptDiagnostics()<<"\n";
        assert(report.status==0 && maximum<.2);
    }
}
int main(int argc,char** argv) {
    std::filesystem::path output;
    bool fullSize=false;
    for(int i=1;i<argc;i++) {
        if(std::string(argv[i])=="--output" && i+1<argc) {output=argv[++i];std::filesystem::create_directories(output);}
        else if(std::string(argv[i])=="--full-resolution") fullSize=true;
        else {std::cerr<<"Usage: fusion_test [--output directory] [--full-resolution]\n";return 2;}
    }
    std::vector<Point> p{{0,0},{400,0},{400,300},{0,300},{170,80},{270,220}};std::vector<Point> q;
    Homography truth{1.02,.03,4,-.02,.98,8,.0001,-.00008,1},fitted;
    for(auto v:p) q.push_back(project(truth,v));
    assert(fit(p,q,fitted));for(size_t i=0;i<p.size();i++) {auto r=project(fitted,p[i]);assert(std::hypot(r.x-q[i].x,r.y-q[i].y)<1e-6);}
    Scanner tracking;auto first=tracking.add(synthetic(384,288,identity()));assert(first.status==0);
    auto duplicate=tracking.add(synthetic(384,288,identity()));assert(duplicate.status==3);
    Homography shifted{1,0,2.35,0,1,-1.7,0,0,1};
    auto report=tracking.add(synthetic(384,288,shifted));
    std::cout<<"Translation status "<<report.status<<", inliers "<<report.inliers<<", RMS "<<report.error<<"\n";
    assert(report.status==0 && report.inliers>=20);
    auto center=project(report.transform,{192,144}),expected=project(shifted,{192,144});
    assert(std::hypot(center.x-expected.x,center.y-expected.y)<.25);
    Homography perspective{1.006,.008,2.2,-.006,.997,1.6,.000025,-.00002,1};
    auto perspectiveReport=tracking.add(synthetic(384,288,perspective));
    std::cout<<"Perspective status "<<perspectiveReport.status<<", inliers "<<perspectiveReport.inliers<<", RMS "<<perspectiveReport.error<<"\n";
    assert(perspectiveReport.status==0);
    for(auto point:p) if(point.x<384 && point.y<288) {
        auto a=project(perspectiveReport.transform,point),b=project(perspective,point);assert(std::hypot(a.x-b.x,a.y-b.y)<.55);
    }
    auto trackedGlare=tracking.add(synthetic(384,288,shifted,false,60));
    assert(trackedGlare.status==3 || trackedGlare.status==0);
    // This known pose is already in the capture; glare must not move the plane.
    auto glareCenter=project(trackedGlare.transform,{192,144});
    assert(std::hypot(glareCenter.x-expected.x,glareCenter.y-expected.y)<.3);
    Scanner fullResolution;
    assert(fullResolution.add(synthetic(1024,768,identity())).status==0);
    Homography fineShift{1,0,.45,0,1,.65,0,0,1};
    auto fineReport=fullResolution.add(synthetic(1024,768,fineShift));
    auto fineCenter=project(fineReport.transform,{512,384});
    std::cout<<"Full-resolution status "<<fineReport.status<<", center error "<<std::hypot(fineCenter.x-512.45,fineCenter.y-384.65)<<"\n";
    assert(fineReport.status==0 && std::hypot(fineCenter.x-512.45,fineCenter.y-384.65)<.15);
    Image noise(384,288);std::mt19937 random(7);for(auto& v:noise.rgb) v=uint8_t(random()%256);
    assert(tracking.add(std::move(noise)).status==2);
    Image blank(384,288);std::fill(blank.rgb.begin(),blank.rgb.end(),128);assert(tracking.add(std::move(blank)).status==1);
    Scanner single,multiple;single.addKnown(synthetic(160,128,identity(),true),identity());
    for(int y=0;y<4;y++) for(int x=0;x<4;x++) {
        Homography h{1,0,x*.25,0,1,y*.25,0,0,1};multiple.addKnown(synthetic(160,128,h,true),h);
    }
    double baseline=mse(single.render(2).image,true);auto fused=multiple.render(2);double reconstructed=mse(fused.image,true);
    std::cout<<"Detail MSE single bilinear "<<baseline<<", 16 phase drizzle "<<reconstructed<<", support "<<fused.supported<<"\n";
    assert(reconstructed<baseline*.8);assert(fused.supported>.95);
    Scanner clean,glare;
    for(int i=0;i<9;i++) {
        Homography h{1,0,(i%3)*.33,0,1,(i/3)*.33,0,0,1};
        clean.addKnown(synthetic(180,128,h),h);glare.addKnown(synthetic(180,128,h,false,5+i*18),h);
    }
    auto clearRender=clean.render(2),glareRender=glare.render(2);
    double clearError=mse(clearRender.image,false),glareError=mse(glareRender.image,false);
    std::cout<<"Moving glare MSE "<<glareError<<", clean "<<clearError<<", rejected "<<glareRender.rejected<<"\n";
    assert(glareError<.001);assert(glareRender.rejected>.05);
    auto preview=glare.render(2,100);assert(preview.image.width==100 && preview.image.height==71);
    bool invalid=false;try {single.addKnown(Image(8193,32),identity());} catch(const std::invalid_argument&) {invalid=true;}assert(invalid);
    invalid=false;try {Homography h=identity();h[0]=std::numeric_limits<double>::quiet_NaN();single.addKnown(Image(160,128),h);} catch(const std::invalid_argument&) {invalid=true;}assert(invalid);
    if(!output.empty()) {
        ppm(output/"detail-truth.ppm",groundTruth(160,128,true));
        ppm(output/"detail-source.ppm",single.frame(0).image);
        ppm(output/"detail-bilinear.ppm",single.render(2).image);
        ppm(output/"detail-drizzle.ppm",fused.image);
        ppm(output/"glare-truth.ppm",groundTruth(180,128,false));
        ppm(output/"glare-source.ppm",glare.frame(0).image);
        ppm(output/"glare-reconstructed.ppm",glareRender.image);
        std::ofstream note(output/"README.txt");
        note<<"Ideal analytic point samples; this validates registration/reconstruction, not a phone resolution guarantee.\n"
            <<"No sensor PSF, Bayer demosaic, YUV compression or lens distortion is modeled.\n"
            <<"Detail linear-light bilinear MSE: "<<baseline<<"; multi-frame MSE: "<<reconstructed<<"\n"
            <<"Moving glare MSE: "<<glareError<<"; rejected observation fraction: "<<glareRender.rejected<<"\n";
    }
    Scanner narrow;Image narrowImage(8192,32);std::fill(narrowImage.rgb.begin(),narrowImage.rgb.end(),128);assert(narrow.add(std::move(narrowImage)).status==1);
    storageChecks(output,fullSize);
    diagnosticChecks(output);
    boundaryChecks(output);
    zoomChecks();
    reacquisitionChecks(output);
    std::cout<<"All native checks passed\n";
}
