// Copyright (C) 2026 Toit contributors.
// Minimal host entry point; the Android app and this tool use the same core.
#include "fusion.h"
#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <algorithm>
#include <chrono>

static luma::Image readImage(const std::filesystem::path& path) {
    std::ifstream input(path, std::ios::binary);
    std::string magic;
    int width = 0, height = 0, maximum = 0;
    input >> magic >> width >> height >> maximum;
    if (magic != "P6" || width < 16 || height < 16 || width > 8192 || height > 8192 ||
        int64_t(width) * height > 16000000 || maximum != 255) {
        throw std::runtime_error("Invalid PPM input " + path.string());
    }
    input.get();
    luma::Image image(width, height);
    input.read(reinterpret_cast<char*>(image.rgb.data()), image.rgb.size());
    if (!input) throw std::runtime_error("Truncated PPM " + path.string());
    return image;
}

static void writeImage(const std::filesystem::path& path, const luma::Image& image) {
    std::ofstream output(path, std::ios::binary);
    output << "P6\n" << image.width << " " << image.height << "\n255\n";
    output.write(reinterpret_cast<const char*>(image.rgb.data()), image.rgb.size());
    if (!output) throw std::runtime_error("Cannot write " + path.string());
}

int main(int argc, char** argv) {
    if (argc != 3) {
        std::cerr << "Usage: replay-native PPM_DIRECTORY OUTPUT_PREFIX\n";
        return 2;
    }
    try {
        std::vector<std::filesystem::path> paths;
        for (const auto& entry : std::filesystem::directory_iterator(argv[1])) {
            if (entry.path().extension() == ".ppm") paths.push_back(entry.path());
        }
        std::sort(paths.begin(), paths.end());
        if (paths.empty()) throw std::runtime_error("No source frames");
        auto started = std::chrono::steady_clock::now();
        luma::Scanner scanner;
        scanner.configureStorage((std::filesystem::path(argv[1]) / ".native-cache").string());
        for (const auto& path : paths) {
            auto report = scanner.add(readImage(path));
            std::cout << "{\"frame\":\"" << path.filename().string()
                      << "\",\"status\":" << report.status << ",\"accepted\":" << report.accepted
                      << ",\"inliers\":" << report.inliers << ",\"error\":" << report.error << "}\n";
        }
        if (scanner.count() == 0) throw std::runtime_error("No acceptable frames");
        auto single = scanner.loadFrame(0);
        writeImage(std::string(argv[2]) + "-reference.ppm", single);
        // Keep the full-resolution reconstruction out of RAM, just as on Android.
        const auto raster = (std::filesystem::path(argv[1]) / "result.rgb").string();
        auto fused = scanner.renderToFiles(std::string(argv[2]) + "-fused.png", raster, 2);
        const auto elapsed = std::chrono::duration<double>(std::chrono::steady_clock::now() - started).count();
        std::cout << "{\"summary\":true,\"accepted\":" << scanner.count()
                  << ",\"width\":" << fused[0] << ",\"height\":" << fused[1]
                  << ",\"supported\":" << fused[2] << ",\"clipped\":" << fused[3]
                  << ",\"rejected\":" << fused[4] << ",\"seconds\":" << elapsed << "}\n";
        scanner.reset();
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << "\n";
        return 1;
    }
}
