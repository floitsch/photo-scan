// Copyright (C) 2026 Toit contributors.
#pragma once
#include <array>
#include <cstdint>
#include <vector>
#include <functional>
#include <string>

namespace luma {
struct Point { double x = 0, y = 0; };
using Homography = std::array<double, 9>;
inline Homography identity() { return {1,0,0,0,1,0,0,0,1}; }
Point project(const Homography&, Point);
bool fit(const std::vector<Point>&, const std::vector<Point>&, Homography&);
Homography inverse(const Homography&);
struct Image {
    int width = 0, height = 0;
    std::vector<uint8_t> rgb;
    Image() = default;
    Image(int w, int h) : width(w), height(h), rgb(size_t(w)*h*3) {}
};
struct Gray {
    int width = 0, height = 0;
    std::vector<float> pixels;
    float at(double x, double y) const;
};
// Transform maps first-frame pixel centers into this frame.
struct Frame { Image image; Homography transform; double sharpness; Image thumbnail; std::string diskPath; };
struct Report {
    // 0 accepted, 1 blur, 2 alignment, 3 duplicate, 4 full.
    int status = 0, accepted = 0, inliers = 0, phases = 0;
    double sharpness = 0, error = 0;
    Homography transform = identity();
};
struct Render {
    Image image;
    // Fractions: output pixels with >=2 accepted observations; clipped pixels;
    // discarded observations / all available observations.
    double supported = 0, clipped = 0, rejected = 0;
};
class Scanner {
public:
    // Up to 12.6 MP sources; disk backing retains only thumbnails and reference gray.
    static constexpr int maxFrames = 24;
    void configureStorage(const std::string& directory);
    void reset();
    Image loadFrame(size_t n) const;
    void exportLastSource(const std::string& png) const;
    std::array<double,5> renderToFiles(const std::string& png,const std::string& raster,int scale=2,int maxSide=0) const;
    Report add(Image image);
    const std::string& lastAttemptDiagnostics() const { return attemptDiagnostics; }
    const Image& diagnosticThumbnail() const { return attemptThumbnail; }
    // Bridge input conversion may fail before add can inspect the frame.
    void noteInputFailure(int width,int height);
    // Used by the deterministic benchmark with known camera poses.
    void addKnown(Image image, const Homography&);
    // First-frame field of view; scale 1 or 2. A 2x grid is not a guarantee
    // of a 2x improvement in resolved detail. maxSide=0 keeps the grid size.
    Render render(int scale = 2, int maxSide = 0) const;
    const Frame& frame(size_t n) const { return frames.at(n); }
    size_t count() const { return frames.size(); }
private:
    std::array<double,5> process(int scale,int maxSide,const std::function<void(const uint8_t*,int,int)>& row) const;
    std::string attemptDiagnostics = "{\"schema\":1,\"reason\":\"reset\",\"stage\":\"idle\"}";
    Image attemptThumbnail;
    std::string storage;
    std::vector<std::string> ownedFiles;
    std::vector<Frame> frames;
    std::vector<Gray> reference;
    std::vector<Gray> fullReference;
    std::vector<Point> features;
    std::array<bool,16> phaseBins{};
    Homography last = identity();
    double initialSharpness = 0;
};
std::array<Point,4> documentCorners(const Image&,std::string* diagnostics=nullptr);
} // namespace luma
