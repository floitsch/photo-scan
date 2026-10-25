// Copyright (C) 2026 Toit contributors.
#pragma once
#include "fusion.h"
#include <cstdio>
#include <memory>
#include <string>

namespace luma {
constexpr uint64_t maxRasterPixels = 50400000;
void checkRasterDimensions(int width,int height);
std::string temporaryPath(const std::string& target);
class RasterReader {
public:
    explicit RasterReader(const std::string& path);
    ~RasterReader();
    RasterReader(const RasterReader&)=delete;
    int width=0,height=0;
    const uint8_t* row(int y);
    Image readImage();
    Image readRegion(int x,int y,int width,int height);
private:
    FILE* file=nullptr;
    struct Cache {int y=-1; std::vector<uint8_t> pixels;};
    std::array<Cache,32> cache;
};
class RasterWriter {
public:
    RasterWriter(const std::string& target,int width,int height);
    ~RasterWriter();
    void row(const uint8_t* rgb);
    void finish();
private:
    std::string target,temp;
    FILE* file=nullptr;
    int width,height,rows=0;
};
class PngWriter {
public:
    PngWriter(const std::string& target,int width,int height);
    ~PngWriter();
    void row(const uint8_t* rgb);
    void finish();
private:
    struct State;
    std::unique_ptr<State> state;
};
void appendRasterRows(const std::string& path,const Image& strip,int top,int totalHeight);
std::array<int,2> exportRaster(const std::string& raster,const std::string& png,
                             const std::array<Point,4>& corners,double ratio,const std::array<float,3>& gains);
} // namespace luma
