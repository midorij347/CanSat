//
// Created by 田中伸明 on 2025/10/24.
//
// jni/native_analyzer_jni.cpp
#include <jni.h>
#include <vector>
#include <android/log.h>
#include "core/portable_image.h"
#include "core/filter_min.h"

using core::Gray;
using core::Filter;

extern "C" JNIEXPORT jint JNICALL
Java_com_example_myapplicationplp_NativeAnalyzer_processYuv420(
        JNIEnv* env, jobject /*thiz*/,
        jbyteArray yArr, jint yRowStride, jint w, jint h) {

    if(!yArr || w<=0 || h<=0) return -1;
    jbyte* yptr = env->GetByteArrayElements(yArr, nullptr);

    Gray src(w,h), amp, dir, zc;
    // Y(8bit) → float
    for(int y=0; y<h; ++y){
        const uint8_t* row = reinterpret_cast<uint8_t*>(yptr) + y*yRowStride;
        for(int x=0; x<w; ++x) *src.at(x,y) = float(row[x]);
    }

    // 勾配用バッファ
    Gray dx(w,h), dy(w,h);

// 簡単な中央差分で勾配を計算（端は 0 のまま）
    for (int y = 1; y < h-1; ++y) {
        for (int x = 1; x < w-1; ++x) {
            float cxp = *src.at(x+1, y);
            float cxm = *src.at(x-1, y);
            float cyp = *src.at(x,   y+1);
            float cym = *src.at(x,   y-1);
            *dx.at(x,y) = 0.5f * (cxp - cxm);
            *dy.at(x,y) = 0.5f * (cyp - cym);
        }
    }

    // カメラからグレースケール画像を送る
    // dx, dy を作って Filter::edge_amp_dir → zero_crossing
    Filter f;
    f.edge_amp_dir(dx, dy, amp, dir);
    f.zero_crossing(amp, dir, zc);

    // amp の平均値を返す例
    double sum = 0.0;
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x)
            sum += *amp.at(x,y);
    int avg = (int)(sum / (w * (double)h));


    env->ReleaseByteArrayElements(yArr, yptr, JNI_ABORT);
    return avg;
}
