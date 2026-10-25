#!/usr/bin/env python3
# Copyright (C) 2026 Toit contributors.
"""Compile and test the real camera geometry helpers using cached Kotlin artifacts."""
from pathlib import Path
import os
import tempfile
from test_diagnostics import cached, run, ROOT


def main():
    stdlib = cached("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.21")
    compiler = [cached(*item) for item in [
        ("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.2.21"),
        ("org.jetbrains.kotlin", "kotlin-script-runtime", "2.2.21"),
        ("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
        ("org.jetbrains.kotlin", "kotlin-daemon-embeddable", "2.2.21"),
        ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"),
        ("org.jetbrains", "annotations", "13.0"),
    ]] + [stdlib]
    with tempfile.TemporaryDirectory(prefix="lumascan-camera-") as temporary:
        work = Path(temporary)
        harness = work / "CameraGeometryTest.kt"
        harness.write_text('''package org.toit.lumascan
import kotlin.math.abs
fun main() {
    fun close(a:Float,b:Float)=check(abs(a-b)<.001f) { "$a != $b" }
    val source=CameraGeometry.Dimensions(4000,3000)
    val pair=CameraGeometry.streams(listOf(source,CameraGeometry.Dimensions(1920,1080)),
        listOf(CameraGeometry.Dimensions(1920,1080),CameraGeometry.Dimensions(1600,1200)),12_600_000)
    check(pair.first==source && pair.second==CameraGeometry.Dimensions(1600,1200))
    val alternative=CameraGeometry.streams(listOf(source,CameraGeometry.Dimensions(1920,1080)),
        listOf(CameraGeometry.Dimensions(1920,1080)),12_600_000)
    check(alternative.first==CameraGeometry.Dimensions(1920,1080))
    check(runCatching { CameraGeometry.streams(listOf(source),listOf(CameraGeometry.Dimensions(1920,1080)),12_600_000) }.isFailure)
    check(runCatching { CameraGeometry.streams(listOf(CameraGeometry.Dimensions(9000,1000)),listOf(CameraGeometry.Dimensions(900,100)),12_600_000) }.isFailure)
    check(runCatching { CameraGeometry.streams(listOf(source),listOf(CameraGeometry.Dimensions(1600,1200)),2_100_000) }.isFailure)
    for(sensor in listOf(0,90,180,270)) for(display in listOf(0,90,180,270))
        for(view in listOf(720 to 1280,1280 to 720,900 to 900)) {
            val g=CameraGeometry.fitCenter(view.first,view.second,source,sensor,display)
            check(g.left>=-.001 && g.top>=-.001 && g.right<=view.first+.001 && g.bottom<=view.second+.001)
            close(g.left,view.first-g.right); close(g.top,view.second-g.bottom)
            val swapped=(sensor-display+360)%180!=0
            val ratio=if(swapped)3f/4 else 4f/3
            close((g.right-g.left)/(g.bottom-g.top),ratio)
            check(abs(g.left)<.001 || abs(g.top)<.001)
        }
    val portrait=CameraGeometry.fitCenter(900,900,source,90,0)
    close(portrait.left,112.5f); close(portrait.right,787.5f); close(portrait.top,0f); close(portrait.bottom,900f)
    check(CameraGeometry.lensType(14.0)=="Ultra-wide")
    check(CameraGeometry.lensType(26.0)=="Wide")
    check(CameraGeometry.lensType(100.0)=="Telephoto")
    check(CameraGeometry.lensType(null)=="Rear camera")
    check(CameraFocusPolicy.mode(intArrayOf(0,1,3,4),false)==4)
    check(CameraFocusPolicy.mode(intArrayOf(0,1,3),false)==3)
    check(CameraFocusPolicy.mode(intArrayOf(0,1),false)==1)
    check(CameraFocusPolicy.mode(intArrayOf(0,1,4),true)==0)
    check(CameraFocusPolicy.settled(4,2,0,false))
    check(!CameraFocusPolicy.settled(4,2,1,false))
    check(!CameraFocusPolicy.settled(4,4,0,false))
    check(!CameraFocusPolicy.settled(4,5,0,false))
    check(!CameraFocusPolicy.settled(4,6,0,false))
    check(!CameraFocusPolicy.settled(4,null,0,false))
    check(CameraFocusPolicy.settled(1,4,0,false))
    check(!CameraFocusPolicy.settled(1,5,0,false))
    check(!CameraFocusPolicy.settled(0,0,0,false))
    check(CameraFocusPolicy.settled(0,null,null,true))
    check(!CameraFocusPolicy.settled(0,5,0,true))
    check(CameraFocusPolicy.closestFocusCm(2f,1)==50.0)
    check(CameraFocusPolicy.closestFocusCm(2f,0)==null)
    check(CameraFocusPolicy.closestFocusCm(0f,2)==null)
    check(CameraFocusPolicy.nearLimit(7.0f,7.1f))
    check(!CameraFocusPolicy.nearLimit(1f,7.1f))
    class Buffer(val timestamp:Long):AutoCloseable { var closes=0; override fun close(){closes++;check(closes==1)} }
    fun matcher()=CameraFrameMatcher<Buffer,Long>({it.timestamp},{it})
    val early=matcher(); val first=Buffer(123)
    early.receiveImage(first); check(early.takeMatched()==null && first.closes==0)
    early.receiveResult(123); val matched=early.takeMatched()!!
    check(matched.first===first && matched.second==123L && first.closes==0)
    early.close(); check(first.closes==0); matched.first.close()
    val resultFirst=matcher(); resultFirst.receiveResult(234); val second=Buffer(234)
    resultFirst.receiveImage(second); check(resultFirst.takeMatched()!!.first===second); second.close()
    val stale=matcher(); val old=Buffer(100); stale.receiveImage(old); stale.receiveResult(200)
    check(stale.takeMatched()==null && old.closes==1 && stale.hasResult)
    val correct=Buffer(200); stale.receiveImage(correct); check(stale.takeMatched()!!.first===correct); correct.close()
    val bounded=matcher(); val discarded=Buffer(1); val retained=Buffer(2)
    bounded.receiveImage(discarded); bounded.receiveImage(retained)
    check(discarded.closes==1 && retained.closes==0)
    val lateOld=Buffer(0); bounded.receiveImage(lateOld)
    check(lateOld.closes==1 && retained.closes==0)
    bounded.receiveResult(2); check(bounded.takeMatched()!!.first===retained); retained.close()
    val closedOnTimeout=Buffer(8); bounded.receiveImage(closedOnTimeout); bounded.close(); check(closedOnTimeout.closes==1)
    val missing=CameraFrameMatcher<Buffer,String>({it.timestamp},{null}); val unknown=Buffer(5)
    missing.receiveImage(unknown); missing.receiveResult("missing timestamp")
    check(missing.takeMatched()==null && unknown.closes==1)
    check(CameraTimeoutPolicy.action(1,true)==CameraTimeoutPolicy.Action.RETRY)
    check(CameraTimeoutPolicy.action(2,false)==CameraTimeoutPolicy.Action.DRAIN)
    check(CameraTimeoutPolicy.action(3,true)==CameraTimeoutPolicy.Action.FAIL)
    check(CameraTimeoutPolicy.action(3,false)==CameraTimeoutPolicy.Action.FAIL)
    println("Camera geometry/focus/matching: rotations, focus gates, early/late images, stale disposal, ownership and bounded timeout recovery passed")
}
''')
        classes = work / "classes"
        classes.mkdir()
        run("java", "-Xmx1g", "-cp", os.pathsep.join(map(str, compiler)),
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
            "-jvm-target", "17", "-classpath", stdlib, "-d", classes,
            ROOT / "app/src/main/java/org/toit/lumascan/CameraGeometry.kt",
            ROOT / "app/src/main/java/org/toit/lumascan/CameraFocusPolicy.kt",
            ROOT / "app/src/main/java/org/toit/lumascan/CameraFrameMatcher.kt", harness)
        run("java", "-cp", os.pathsep.join(map(str, [classes, stdlib])), "org.toit.lumascan.CameraGeometryTestKt")


if __name__ == "__main__":
    main()
