// Copyright (C) 2026 Toit contributors.
package org.toit.lumascan

import kotlin.math.abs

private fun near(actual:Float,expected:Float,tolerance:Float=.0001f) {
    check(abs(actual-expected)<=tolerance) { "$actual != $expected" }
}

fun main() {
    val viewport=CropViewport().apply { configure(800f,600f,1200,900) }
    // A pinch keeps the image coordinate under its moving midpoint fixed.
    val anchorX=viewport.normalizedX(300f); val anchorY=viewport.normalizedY(250f)
    viewport.transform(300f,250f,330f,265f,2f)
    near(viewport.normalizedX(330f),anchorX); near(viewport.normalizedY(265f),anchorY)
    // Corner/neutral-point coordinates round-trip through arbitrary zoom and pan.
    viewport.pan(-75f,-40f)
    for((x,y) in listOf(.02f to .98f,.25f to .4f,.7f to .6f,1f to 0f)) {
        near(viewport.normalizedX(viewport.screenX(x)),x)
        near(viewport.normalizedY(viewport.screenY(y)),y)
    }
    val corners=floatArrayOf(.1f,.2f,.8f,.2f,.9f,.85f,.08f,.9f)
    val original=corners.copyOf()
    for(rotation in listOf(0,90,180,270)) {
        val before=FloatArray(8)
        for(i in 0..3) {
            val source=CropCoordinates.sourcePoint(corners[2*i],corners[2*i+1],rotation)
            before[2*i]=source.first; before[2*i+1]=source.second
        }
        viewport.transform(400f,300f,400f,300f,1.7f)
        viewport.pan(-18f,12f)
        for(i in 0..3) {
            val x=viewport.normalizedX(viewport.screenX(corners[2*i]))
            val y=viewport.normalizedY(viewport.screenY(corners[2*i+1]))
            val source=CropCoordinates.sourcePoint(x,y,rotation)
            near(source.first,before[2*i]); near(source.second,before[2*i+1])
        }
    }
    check(corners.contentEquals(original))
    // Pointer handoff starts at the surviving finger, never the removed finger's location.
    val pan=CropPanAnchor()
    pan.rebase(4,100f,200f); pan.rebase(9,360f,240f)
    check(pan.pointerId==9); near(pan.deltaX(360f),0f); near(pan.deltaY(240f),0f)
    near(pan.deltaX(365f),5f); near(pan.deltaY(237f),-3f)
    pan.update(365f,237f); near(pan.deltaX(368f),3f)
    pan.clear(); check(pan.pointerId == -1)
    // Region rotation and point rotation use the same export convention.
    val input=floatArrayOf(.1f,.2f,.4f,.7f)
    val expected=listOf(input,floatArrayOf(.2f,.6f,.7f,.9f),floatArrayOf(.6f,.3f,.9f,.8f),floatArrayOf(.3f,.1f,.8f,.4f))
    for((index,rotation) in listOf(0,90,180,270).withIndex()) {
        val native=CropCoordinates.sourceRegion(input[0],input[1],input[2],input[3],rotation)
        for(i in 0..3)near(native[i],expected[index][i])
        val restored=CropCoordinates.sourceRegion(native[0],native[1],native[2],native[3],360-rotation)
        for(i in 0..3)near(restored[i],input[i])
    }
    // Resize, zoom limits and Fit preserve a valid viewport and never alter corners.
    viewport.transform(400f,300f,400f,300f,100f); near(viewport.zoom,8f)
    viewport.configure(600f,800f,1200,900)
    near(viewport.normalizedX(viewport.screenX(.4f)),.4f)
    viewport.pan(-100000f,100000f)
    check(viewport.left<=0 && viewport.top<=0)
    viewport.fit(); near(viewport.zoom,1f); near(viewport.left,0f); near(viewport.top,175f)
    near(viewport.normalizedX(300f),.5f); near(viewport.normalizedY(400f),.5f)
    println("Review geometry passed: pinch anchor, normalized/export invariance, pointer handoff, all tile rotations, resize, limits and Fit.")
}
