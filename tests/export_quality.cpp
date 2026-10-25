// Copyright (C) 2026 Toit contributors.
// Batch driver for test_export_quality.py, exercising the production raster/PNG path.
#include "raster.h"
#include <fstream>
#include <iostream>
#include <filesystem>
#include <chrono>
int main(int argc,char**argv) {
 if(argc!=3)return 2;
 std::ifstream list(argv[1]);std::string input,output;std::filesystem::create_directories(argv[2]);
 while(list>>input>>output) {
  double ratio;std::array<float,3> gains;std::array<luma::Point,4> corners;list>>ratio;for(auto& g:gains)list>>g;for(auto& p:corners)list>>p.x>>p.y;
  std::ifstream f(input,std::ios::binary);std::string magic;int w,h,max;f>>magic>>w>>h>>max;f.get();if(magic!="P6"||max!=255)return 3;
  luma::Image image(w,h);f.read((char*)image.rgb.data(),image.rgb.size());std::string raster=std::string(argv[2])+"/input.rgb";
  luma::RasterWriter writer(raster,w,h);for(int y=0;y<h;y++)writer.row(image.rgb.data()+size_t(y)*w*3);writer.finish();
  auto start=std::chrono::steady_clock::now();auto size=luma::exportRaster(raster,std::string(argv[2])+"/"+output,corners,ratio,gains);
  std::cout<<output<<" "<<size[0]<<" "<<size[1]<<" "<<std::chrono::duration<double>(std::chrono::steady_clock::now()-start).count()<<std::endl;
 }
}
