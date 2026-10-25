// Copyright (C) 2026 Toit contributors.
// Independent image-formation benchmark: optical blur, pixel integration,
// signal-dependent noise, RGB channels, subpixel sampling, and moving glare.
#include "fusion.h"
#include <algorithm>
#include <cmath>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <random>
#include <stdexcept>

namespace {
constexpr double pi = 3.14159265358979323846;
double linear(double s) {
    return s <= .04045 ? s / 12.92 : std::pow((s + .055) / 1.055, 2.4);
}
uint8_t encoded(double value) {
    value = std::clamp(value, 0., 1.);
    const double s = value <= .0031308 ? value * 12.92 : 1.055 * std::pow(value, 1. / 2.4) - .055;
    return uint8_t(std::lround(s * 255));
}
double sinc(double x) { return std::abs(x) < 1e-10 ? 1 : std::sin(pi*x)/(pi*x); }
double field(double x, double y, int channel) {
    // Gaussian optical PSF (sigma=.20 input pixels), then a one-pixel box aperture.
    // The last two frequencies lie above a single frame's Nyquist limit.
    const double frequencies[][3] = {
        {.018,.013,.08}, {.071,-.043,.07}, {.22,.11,.08},
        {.39,-.14,.07}, {.58,.09,.10}, {.12,.61,.08}
    };
    double value = .34 + channel * .04;
    for (int i=0; i<6; ++i) {
        const auto& f = frequencies[i];
        const double transfer = std::exp(-2*pi*pi*.20*.20*(f[0]*f[0]+f[1]*f[1])) * sinc(f[0]) * sinc(f[1]);
        value += f[2] * transfer * std::sin(2*pi*(f[0]*x+f[1]*y)+i*1.47+channel*.31);
    }
    return value;
}
luma::Image capture(int width, int height, double dx, double dy, int index, bool glare) {
    luma::Image image(width,height);
    std::mt19937 generator(8503 + index);
    std::normal_distribution<double> normal;
    for (int y=0; y<height; ++y) for (int x=0; x<width; ++x) for (int c=0; c<3; ++c) {
        double value = field(x-dx,y-dy,c);
        // Approximate shot + read noise in normalized sensor-linear units.
        value += normal(generator)*std::sqrt(std::max(0.,value)*.00004+.000004);
        if (glare) {
            const double center = 8 + index * (width-16.)/16;
            if (std::abs(x-center) < 13) value += .50;
        }
        image.rgb[(size_t(y)*width+x)*3+c] = encoded(value);
    }
    return image;
}
luma::Image enlarge(const luma::Image& source) {
    luma::Image output(source.width*2,source.height*2);
    for (int y=0; y<output.height; ++y) for(int x=0; x<output.width; ++x) {
        double sx=std::clamp((x+.5)/2-.5,0.,double(source.width-1));
        double sy=std::clamp((y+.5)/2-.5,0.,double(source.height-1));
        int ix=int(sx),iy=int(sy);
        for(int c=0;c<3;c++) {
            double value=0;
            for(int j=0;j<2;j++) for(int i=0;i<2;i++) {
                int xx=std::min(ix+i,source.width-1),yy=std::min(iy+j,source.height-1);
                double weight=(i?sx-ix:1-sx+ix)*(j?sy-iy:1-sy+iy);
                value+=weight*linear(source.rgb[(size_t(yy)*source.width+xx)*3+c]/255.);
            }
            output.rgb[(size_t(y)*output.width+x)*3+c]=encoded(value);
        }
    }
    return output;
}
double error(const luma::Image& image) {
    double sum=0;size_t count=0;
    for(int y=16;y<image.height-16;y++) for(int x=16;x<image.width-16;x++) for(int c=0;c<3;c++) {
        double difference=linear(image.rgb[(size_t(y)*image.width+x)*3+c]/255.)-field((x+.5)/2-.5,(y+.5)/2-.5,c);
        sum+=difference*difference;count++;
    }
    return sum/count;
}
void write(const std::filesystem::path& path,const luma::Image& image) {
    std::ofstream stream(path,std::ios::binary);
    stream<<"P6\n"<<image.width<<" "<<image.height<<"\n255\n";
    stream.write(reinterpret_cast<const char*>(image.rgb.data()),image.rgb.size());
    if(!stream)throw std::runtime_error("Cannot write benchmark artifact");
}
}

int main(int argc,char** argv) {
    constexpr int width=256,height=192;
    luma::Scanner scanner,corrupted;
    for(int y=0;y<4;y++)for(int x=0;x<4;x++) {
        const int i=y*4+x;
        luma::Homography pose{1,0,x*.25,0,1,y*.25,0,0,1};
        scanner.addKnown(capture(width,height,x*.25,y*.25,i,false),pose);
        corrupted.addKnown(capture(width,height,x*.25,y*.25,i,true),pose);
    }
    auto single=enlarge(scanner.frame(0).image);
    auto denoised=enlarge(scanner.render(1).image);
    auto fused=scanner.render(2).image;
    auto glare=corrupted.render(2).image;
    const double a=error(single),b=error(denoised),c=error(fused),d=error(glare);
    std::cout<<"{\"singleLinearMse\":"<<a<<",\"aligned1xLinearMse\":"<<b
             <<",\"superResolutionLinearMse\":"<<c<<",\"movingGlareLinearMse\":"<<d<<"}\n";
    // SR must beat multi-frame denoising at the same display size, not just a noisy input.
    if(!(c < a && c < b && d < a)) {
        std::cerr<<"Reconstruction did not improve on the image-formation baselines\n";
        return 1;
    }
    if(argc==2) {
        std::filesystem::path out(argv[1]);std::filesystem::create_directories(out);
        luma::Image truth(width*2,height*2);
        for(int y=0;y<truth.height;y++)for(int x=0;x<truth.width;x++)for(int k=0;k<3;k++)
            truth.rgb[(size_t(y)*truth.width+x)*3+k]=encoded(field((x+.5)/2-.5,(y+.5)/2-.5,k));
        write(out/"sensor-truth.ppm",truth);
        write(out/"sensor-single.ppm",single);
        write(out/"sensor-denoised.ppm",denoised);
        write(out/"sensor-super-resolution.ppm",fused);
        write(out/"sensor-moving-glare.ppm",glare);
    }
}
