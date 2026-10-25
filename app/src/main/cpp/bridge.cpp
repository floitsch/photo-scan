// Copyright (C) 2026 Toit contributors.
#include "fusion.h"
#include "raster.h"
#include <jni.h>
#include <android/bitmap.h>
#include <algorithm>
#include <cstring>
#include <exception>
#include <stdexcept>

namespace {
luma::Scanner scanner;
std::array<double,3> lastMetrics{};
std::string cornerDiagnostics="{\"schema\":1,\"reason\":\"reset\",\"stage\":\"idle\"}";
void fail(JNIEnv* env,const std::exception& error) {
    if(env->ExceptionCheck()) return;
    const char* type=dynamic_cast<const std::invalid_argument*>(&error)?"java/lang/IllegalArgumentException":"java/lang/IllegalStateException";
    jclass cls=env->FindClass(type);if(cls) {env->ThrowNew(cls,error.what());env->DeleteLocalRef(cls);}
}
void dimensions(int w,int h) {
    if(w<32 || h<32 || w>8192 || h>8192 || int64_t(w)*h>12600000)
        throw std::invalid_argument("Use frames of at most 12.6 megapixels (32..8192 per side)");
}
struct Pixels {
    JNIEnv* env; jobject bitmap; void* data=nullptr;
    Pixels(JNIEnv* e,jobject b):env(e),bitmap(b) {
        if(AndroidBitmap_lockPixels(env,bitmap,&data)!=ANDROID_BITMAP_RESULT_SUCCESS || !data) throw std::runtime_error("Cannot lock bitmap pixels");
    }
    ~Pixels() {if(data) AndroidBitmap_unlockPixels(env,bitmap);}
};
luma::Image readBitmap(JNIEnv* env,jobject bitmap,bool thumbnail=false,bool strip=false) {
    if(!bitmap) throw std::invalid_argument("Bitmap is null");
    AndroidBitmapInfo info{};
    if(AndroidBitmap_getInfo(env,bitmap,&info)!=ANDROID_BITMAP_RESULT_SUCCESS) throw std::invalid_argument("Cannot read bitmap");
    if(thumbnail) {
        if(info.width<32 || info.height<32 || info.width>16384 || info.height>16384 || uint64_t(info.width)*info.height>64000000)
            throw std::invalid_argument("Invalid corner preview dimensions");
    } else if(strip) {
        if(info.width<1 || info.height<1 || info.width>16384 || info.height>512 || uint64_t(info.width)*info.height>8388608)
            throw std::invalid_argument("Raster strips must be at most 512 rows and 8 megapixels");
    } else dimensions(int(info.width),int(info.height));
    if(info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888 || info.stride<uint64_t(info.width)*4) throw std::invalid_argument("Bitmap must use ARGB_8888");
    unsigned step=thumbnail ? std::max(1u,(std::max(info.width,info.height)+799)/800) : 1;
    Pixels lock(env,bitmap);luma::Image image(int((info.width+step-1)/step),int((info.height+step-1)/step));
    for(int y=0;y<image.height;y++) {
        auto src=static_cast<const uint8_t*>(lock.data)+size_t(y)*step*info.stride;
        for(int x=0;x<image.width;x++) for(int c=0;c<3;c++) {
            // Camera frames are opaque; imported transparent images are flattened on white.
            int alpha=src[4*x*step+3],value=src[4*x*step+c];
            if((info.flags&ANDROID_BITMAP_FLAGS_ALPHA_MASK)==ANDROID_BITMAP_FLAGS_ALPHA_PREMUL) value=std::min(255,value+255-alpha);
            else value=(value*alpha+255*(255-alpha)+127)/255;
            image.rgb[(size_t(y)*image.width+x)*3+c]=uint8_t(value);
        }
    }
    return image;
}
jobject bitmap(JNIEnv* env,const luma::Image& image) {
    jclass bitmapClass=env->FindClass("android/graphics/Bitmap");
    jclass configClass=env->FindClass("android/graphics/Bitmap$Config");
    if(!bitmapClass || !configClass) throw std::runtime_error("Bitmap classes unavailable");
    jfieldID configField=env->GetStaticFieldID(configClass,"ARGB_8888","Landroid/graphics/Bitmap$Config;");
    jmethodID create=env->GetStaticMethodID(bitmapClass,"createBitmap","(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    if(!configField || !create) throw std::runtime_error("Bitmap factory unavailable");
    jobject config=env->GetStaticObjectField(configClass,configField);
    jobject out=env->CallStaticObjectMethod(bitmapClass,create,image.width,image.height,config);
    env->DeleteLocalRef(config);env->DeleteLocalRef(configClass);env->DeleteLocalRef(bitmapClass);
    if(!out || env->ExceptionCheck()) throw std::runtime_error("Cannot allocate output bitmap");
    AndroidBitmapInfo info{};
    if(AndroidBitmap_getInfo(env,out,&info)!=ANDROID_BITMAP_RESULT_SUCCESS || info.stride<uint64_t(image.width)*4) throw std::runtime_error("Invalid output bitmap stride");
    Pixels lock(env,out);
    for(int y=0;y<image.height;y++) {
        auto dst=static_cast<uint8_t*>(lock.data)+size_t(y)*info.stride;
        for(int x=0;x<image.width;x++) {
            const uint8_t* src=image.rgb.data()+(size_t(y)*image.width+x)*3;
            dst[x*4]=src[0];dst[x*4+1]=src[1];dst[x*4+2]=src[2];dst[x*4+3]=255;
        }
    }
    return out;
}
jdoubleArray report(JNIEnv* env,const luma::Report& r) {
    double values[15]={double(r.status),double(r.accepted),double(r.inliers),double(r.phases),r.sharpness,r.error};
    std::copy(r.transform.begin(),r.transform.end(),values+6);
    auto out=env->NewDoubleArray(15);if(out) env->SetDoubleArrayRegion(out,0,15,values);return out;
}
const uint8_t* plane(JNIEnv* env,jobject buffer,int rows,int columns,int stride,int pixel) {
    if(!buffer || stride<=0 || pixel<=0 || pixel>16 || rows<=0 || columns<=0 || int64_t(columns-1)*pixel+1>stride)
        throw std::invalid_argument("Invalid YUV plane layout");
    auto data=static_cast<const uint8_t*>(env->GetDirectBufferAddress(buffer));
    jlong capacity=env->GetDirectBufferCapacity(buffer);
    if(!data || capacity<0) throw std::invalid_argument("YUV buffers must be direct ByteBuffers");
    jclass cls=env->GetObjectClass(buffer);
    jmethodID position=env->GetMethodID(cls,"position","()I"),limit=env->GetMethodID(cls,"limit","()I");
    if(!position || !limit) {env->DeleteLocalRef(cls);throw std::invalid_argument("Invalid ByteBuffer");}
    jint start=env->CallIntMethod(buffer,position),end=env->CallIntMethod(buffer,limit);env->DeleteLocalRef(cls);
    if(env->ExceptionCheck()) throw std::invalid_argument("Cannot read ByteBuffer bounds");
    int64_t required=int64_t(rows-1)*stride+int64_t(columns-1)*pixel+1;
    if(start<0 || end<start || end>capacity || required>int64_t(end)-start) throw std::invalid_argument("YUV plane buffer is too short for its strides");
    return data+start;
}
uint8_t clamp(int value) {return uint8_t(std::clamp(value,0,255));}
}
extern "C" JNIEXPORT void JNICALL Java_org_toit_lumascan_NativeBridge_reset(JNIEnv* env,jobject) {
    try {scanner.reset();lastMetrics={};cornerDiagnostics="{\"schema\":1,\"reason\":\"reset\",\"stage\":\"idle\"}";} catch(const std::exception& e) {fail(env,e);}
}
extern "C" JNIEXPORT jdoubleArray JNICALL Java_org_toit_lumascan_NativeBridge_addYuv(JNIEnv* env,jobject,jobject y,jobject u,jobject v,jint w,jint h,jint ys,jint us,jint vs,jint up,jint vp) {
    bool submitted=false;
    try {
        dimensions(w,h);
        const uint8_t* yp=plane(env,y,h,w,ys,1);
        const uint8_t* ub=plane(env,u,(h+1)/2,(w+1)/2,us,up);
        const uint8_t* vb=plane(env,v,(h+1)/2,(w+1)/2,vs,vp);
        luma::Image image(w,h);
        // Android YUV_420_888 camera output is conventionally limited-range BT.601.
        for(int j=0;j<h;j++) for(int i=0;i<w;i++) {
            int yy=std::max(0,int(yp[size_t(j)*ys+i])-16),uu=int(ub[size_t(j/2)*us+(i/2)*up])-128,vv=int(vb[size_t(j/2)*vs+(i/2)*vp])-128;
            size_t p=(size_t(j)*w+i)*3;
            image.rgb[p]=clamp((298*yy+409*vv+128)>>8);
            image.rgb[p+1]=clamp((298*yy-100*uu-208*vv+128)>>8);
            image.rgb[p+2]=clamp((298*yy+516*uu+128)>>8);
        }
        submitted=true;return report(env,scanner.add(std::move(image)));
    } catch(const std::exception& e) {if(!submitted)scanner.noteInputFailure(w,h);fail(env,e);return nullptr;}
}
extern "C" JNIEXPORT jdoubleArray JNICALL Java_org_toit_lumascan_NativeBridge_addBitmap(JNIEnv* env,jobject,jobject source) {
    bool submitted=false;
    try {auto image=readBitmap(env,source);submitted=true;return report(env,scanner.add(std::move(image)));}
    catch(const std::exception& e) {if(!submitted)scanner.noteInputFailure(0,0);fail(env,e);return nullptr;}
}
extern "C" JNIEXPORT jobject JNICALL Java_org_toit_lumascan_NativeBridge_frameBitmap(JNIEnv* env,jobject) {
    try {if(!scanner.count()) throw std::runtime_error("No accepted frame");return bitmap(env,scanner.loadFrame(scanner.count()-1));} catch(const std::exception& e) {fail(env,e);return nullptr;}
}
extern "C" JNIEXPORT jobject JNICALL Java_org_toit_lumascan_NativeBridge_render(JNIEnv* env,jobject,jint scale,jint maxSide) {
    try {auto result=scanner.render(scale,maxSide>0?std::min(int(maxSide),640):640);lastMetrics={result.supported,result.clipped,result.rejected};return bitmap(env,result.image);} catch(const std::exception& e) {fail(env,e);return nullptr;}
}
extern "C" JNIEXPORT jdoubleArray JNICALL Java_org_toit_lumascan_NativeBridge_metrics(JNIEnv* env,jobject) {
    auto out=env->NewDoubleArray(3);if(out) env->SetDoubleArrayRegion(out,0,3,lastMetrics.data());return out;
}
extern "C" JNIEXPORT jfloatArray JNICALL Java_org_toit_lumascan_NativeBridge_corners(JNIEnv* env,jobject,jobject source) {
    cornerDiagnostics="{\"schema\":1,\"reason\":\"invalid-input\",\"stage\":\"input-conversion\",\"fallback\":null,\"finalCorners\":null}";
    try {
        auto points=luma::documentCorners(readBitmap(env,source,true),&cornerDiagnostics);jfloat values[8];
        AndroidBitmapInfo input{};
        if(AndroidBitmap_getInfo(env,source,&input)==ANDROID_BITMAP_RESULT_SUCCESS) {
            cornerDiagnostics.pop_back();
            cornerDiagnostics+=",\"inputBitmapWidth\":"+std::to_string(input.width)+",\"inputBitmapHeight\":"+std::to_string(input.height)
                +",\"bitmapSamplingStep\":"+std::to_string(std::max(1u,(std::max(input.width,input.height)+799)/800))+"}";
        }
        for(int i=0;i<4;i++) {values[i*2]=float(points[i].x);values[i*2+1]=float(points[i].y);}
        auto out=env->NewFloatArray(8);if(out) env->SetFloatArrayRegion(out,0,8,values);return out;
    } catch(const std::exception& e) {fail(env,e);return nullptr;}
}

namespace {
std::string string(JNIEnv* env,jstring value) {
    if(!value) throw std::invalid_argument("Path is null");
    const char* utf=env->GetStringUTFChars(value,nullptr);
    if(!utf) throw std::runtime_error("Cannot read path");
    std::string result(utf);env->ReleaseStringUTFChars(value,utf);
    if(result.empty()) throw std::invalid_argument("Path is empty");
    return result;
}
}
extern "C" JNIEXPORT void JNICALL Java_org_toit_lumascan_NativeBridge_configureStorage(JNIEnv* env,jobject,jstring directory) {
    try {scanner.configureStorage(string(env,directory));lastMetrics={};cornerDiagnostics="{\"schema\":1,\"reason\":\"reset\",\"stage\":\"idle\"}";} catch(const std::exception& e) {fail(env,e);}
}
extern "C" JNIEXPORT jdoubleArray JNICALL Java_org_toit_lumascan_NativeBridge_renderToFiles(JNIEnv* env,jobject,jstring png,jstring raster,jint scale,jint maxSide) {
    try {
        auto result=scanner.renderToFiles(string(env,png),string(env,raster),scale,maxSide);
        lastMetrics={result[2],result[3],result[4]};
        auto out=env->NewDoubleArray(5);if(out) env->SetDoubleArrayRegion(out,0,5,result.data());return out;
    } catch(const std::exception& e) {fail(env,e);return nullptr;}
}
extern "C" JNIEXPORT jintArray JNICALL Java_org_toit_lumascan_NativeBridge_exportRaster(JNIEnv* env,jobject,jstring raster,jstring png,jfloatArray corners,jfloat ratio,jfloatArray gains) {
    try {
        if(!corners || env->GetArrayLength(corners)!=8 || !gains || env->GetArrayLength(gains)!=3)
            throw std::invalid_argument("Export requires eight corner coordinates and three gains");
        jfloat coordinates[8],balance[3];env->GetFloatArrayRegion(corners,0,8,coordinates);env->GetFloatArrayRegion(gains,0,3,balance);
        if(env->ExceptionCheck()) return nullptr;
        std::array<luma::Point,4> points;for(int i=0;i<4;i++) points[i]={coordinates[2*i],coordinates[2*i+1]};
        auto result=luma::exportRaster(string(env,raster),string(env,png),points,ratio,{balance[0],balance[1],balance[2]});
        jint values[2]={result[0],result[1]};auto out=env->NewIntArray(2);if(out) env->SetIntArrayRegion(out,0,2,values);return out;
    } catch(const std::exception& e) {fail(env,e);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_org_toit_lumascan_NativeBridge_writeRasterRows(JNIEnv* env,jobject,jstring path,jobject source,jint top,jint totalHeight) {
    try {luma::appendRasterRows(string(env,path),readBitmap(env,source,false,true),top,totalHeight);} catch(const std::exception& e) {fail(env,e);}
}

extern "C" JNIEXPORT jstring JNICALL Java_org_toit_lumascan_NativeBridge_lastAttemptDiagnostics(JNIEnv* env,jobject) {
    return env->NewStringUTF(scanner.lastAttemptDiagnostics().c_str());
}
extern "C" JNIEXPORT jobject JNICALL Java_org_toit_lumascan_NativeBridge_diagnosticThumbnail(JNIEnv* env,jobject) {
    try {
        const auto& image=scanner.diagnosticThumbnail();
        if(image.rgb.empty()) throw std::runtime_error("No attempted frame thumbnail");
        return bitmap(env,image);
    } catch(const std::exception& e) {fail(env,e);return nullptr;}
}
extern "C" JNIEXPORT jstring JNICALL Java_org_toit_lumascan_NativeBridge_lastCornerDiagnostics(JNIEnv* env,jobject) {
    return env->NewStringUTF(cornerDiagnostics.c_str());
}

extern "C" JNIEXPORT void JNICALL Java_org_toit_lumascan_NativeBridge_exportLastSource(JNIEnv* env,jobject,jstring png) {
    try {scanner.exportLastSource(string(env,png));} catch(const std::exception& e) {fail(env,e);}
}
