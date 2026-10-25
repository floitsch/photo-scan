#!/usr/bin/env python3
# Copyright (C) 2026 Toit contributors.
"""Run diagnostic cap/recovery/concurrency tests using cached Kotlin and tiny Android stubs.

The real DiagnosticsStore is compiled unchanged. Stubs isolate storage policy from
Android's clock/logger/JPEG codec; device checks must verify actual JPEG rendering.
"""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path.home() / ".gradle/caches/modules-2/files-2.1"


def cached(group, artifact, version):
    files = sorted((CACHE / group / artifact / version).glob("*/*.jar"))
    if not files:
        raise SystemExit(f"Missing cached dependency: {group}:{artifact}:{version}")
    return files[0]


def run(*args):
    subprocess.run([str(x) for x in args], check=True)


def main():
    stdlib = cached("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.21")
    json = cached("org.json", "json", "20240303")
    compiler = [cached(*item) for item in [
        ("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.2.21"),
        ("org.jetbrains.kotlin", "kotlin-script-runtime", "2.2.21"),
        ("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
        ("org.jetbrains.kotlin", "kotlin-daemon-embeddable", "2.2.21"),
        ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"),
        ("org.jetbrains", "annotations", "13.0"),
    ]] + [stdlib]
    with tempfile.TemporaryDirectory(prefix="lumascan-diagnostics-") as temporary:
        work = Path(temporary)
        classes = work / "classes"
        classes.mkdir()
        stubs = {
            "android/os/SystemClock.java": "package android.os; public class SystemClock { public static long elapsedRealtime() { return System.nanoTime()/1000000L; } }",
            "android/util/Log.java": "package android.util; public class Log { public static int d(String tag,String text){return 0;} public static int w(String tag,String text){return 0;} }",
            "android/graphics/Bitmap.java": """package android.graphics;
                public class Bitmap {
                  int width,height,bytes; public boolean recycled=false;
                  public static int encodedWidth,encodedHeight;
                  public Bitmap(int w,int h,int b){width=w;height=h;bytes=b;}
                  public int getWidth(){return width;} public int getHeight(){return height;}
                  public enum CompressFormat { JPEG, PNG }
                  public void recycle(){recycled=true;}
                  public boolean compress(CompressFormat f,int quality,java.io.OutputStream out){
                    encodedWidth=width;encodedHeight=height;
                    try {out.write(new byte[bytes]);return true;}catch(java.io.IOException e){return false;}}
                  public static Bitmap createScaledBitmap(Bitmap source,int w,int h,boolean filter){return new Bitmap(w,h,source.bytes);}
                }""",
        }
        stubs.update({
            "android/content/Context.java": "package android.content; public class Context { private final java.io.File root; public Context(String path){root=new java.io.File(path);} public java.io.File getFilesDir(){return root;} public String getPackageName(){return \"host.test\";} public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();} }",
            "android/content/pm/PackageManager.java": "package android.content.pm; public class PackageManager { public PackageInfo getPackageInfo(String name,int flags){return new PackageInfo();} }",
            "android/content/pm/PackageInfo.java": "package android.content.pm; public class PackageInfo { public String versionName=\"0.3\"; public long getLongVersionCode(){return 3;} }",
            "android/os/Build.java": "package android.os; public class Build { public static final String BRAND=\"host\",MANUFACTURER=\"host\",MODEL=\"host\",DEVICE=\"host\",ID=\"test\",FINGERPRINT=\"test\"; public static class VERSION {public static final String RELEASE=\"host\"; public static final int SDK_INT=35;} }",
            "android/graphics/BitmapFactory.java": """package android.graphics; public class BitmapFactory {
                public static class Options { public boolean inJustDecodeBounds; public int outWidth=-1,outHeight=-1; public String outMimeType; }
                public static Bitmap decodeFile(String path,Options options){
                    try {java.awt.image.BufferedImage image=javax.imageio.ImageIO.read(new java.io.File(path));
                    if(image!=null){options.outWidth=image.getWidth();options.outHeight=image.getHeight();
                    byte[] bytes=java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path));
                    options.outMimeType=bytes.length>4 && bytes[1]=='P' && bytes[2]=='N' && bytes[3]=='G'?"image/png":null;}}
                    catch(java.io.IOException ignored){} return null; }
                }""",
        })
        sources = []
        for name, source in stubs.items():
            path = work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source)
            sources.append(path)
        run("javac", "-d", classes, *sources)
        harness = work / "DiagnosticsTest.kt"
        harness.write_text('''package org.toit.lumascan
import android.graphics.Bitmap
import android.content.Context
import org.json.JSONObject
import java.util.zip.ZipInputStream
import java.io.File
fun main(args: Array<String>) {
    val root=File(args[0]); root.mkdirs()
    fun session(name:String)=File(root,name).apply { mkdirs() }
    fun summary(dir:File)=JSONObject(File(dir,"diagnostic-summary.json").readText())
    val storage=DiagnosticsStore()
    val capped=session("capped")
    repeat(2050) { storage.logEvent(capped,"frame_attempt",JSONObject().put("reason","blur").put("status",2)) }
    storage.flush(capped)
    var s=summary(capped)
    check(s.getLong("storedEvents")==2000L && s.getLong("droppedEvents")==50L)
    check(s.getJSONObject("attemptReasons").getLong("blur")==2050L)
    check(File(capped,"diagnostics.jsonl").readLines().size==2000)
    val first=JSONObject(File(capped,"diagnostics.jsonl").useLines { it.first() })
    check(first.getString("timestampUtc").endsWith("Z") && first.getLong("elapsedRealtimeMs")>0)
    val bytes=session("byte-cap")
    val detail=JSONObject().put("data", "x".repeat(8192))
    repeat(300) { storage.logEvent(bytes,"large",detail) }; storage.flush(bytes)
    s=summary(bytes)
    check(s.getLong("storedEvents")<300 && s.getLong("droppedEvents")>0)
    check(File(bytes,"diagnostics.jsonl").length()<=DiagnosticsStore.MAX_LOG_BYTES)
    val previews=session("previews")
    val original=Bitmap(1920,960,10)
    check(storage.savePreview(previews,"scaled",original)!=null)
    check(Bitmap.encodedWidth==960 && Bitmap.encodedHeight==480 && !original.recycled)
    repeat(11) { check(storage.savePreview(previews,"p$it",Bitmap(100,100,10))!=null) }
    check(storage.savePreview(previews,"thirteenth",original)==null)
    check(storage.savePreview(previews,"scaled",original)!=null)
    val budget=session("preview-bytes")
    repeat(5) { check(storage.savePreview(budget,"p$it",Bitmap(100,100,700000))!=null) }
    check(storage.savePreview(budget,"too-big",Bitmap(100,100,700000))==null)
    val parallel=session("parallel")
    val threads=(0..3).map { Thread { repeat(100) { storage.logEvent(parallel,"frame_attempt",JSONObject().put("reason","motion").put("status",3)) } }.apply { start() } }
    threads.forEach { it.join() }; storage.flush(parallel)
    check(summary(parallel).getLong("totalEvents")==400L)
    val recovered=DiagnosticsStore()
    recovered.logEvent(parallel,"frame_attempt",JSONObject().put("reason","motion").put("status",3)); recovered.flush(parallel)
    check(summary(parallel).getJSONObject("attemptReasons").getLong("motion")==401L)
    val tail=session("unflushed-tail")
    repeat(3) { storage.logEvent(tail,"frame_attempt",JSONObject().put("reason","tail").put("status",1)) }
    val restart=DiagnosticsStore()
    restart.logEvent(tail,"frame_attempt",JSONObject().put("reason","tail").put("status",1)); restart.flush(tail)
    check(summary(tail).getLong("totalEvents")==4L)
    check(summary(tail).getJSONObject("attemptReasons").getLong("tail")==4L)
    val unavailable=File(root,"not-a-directory").apply { writeText("blocked") }
    storage.logEvent(unavailable,"frame_attempt",JSONObject().put("reason","storage"))
    check(storage.savePreview(unavailable,"failed",original)==null)
    storage.flush(unavailable)
    check(unavailable.delete()); check(unavailable.mkdir())
    storage.logEvent(unavailable,"frame_attempt",JSONObject().put("reason","storage")); storage.flush(unavailable)
    check(summary(unavailable).getJSONObject("attemptReasons").getLong("storage")==2L)
    check(summary(unavailable).getLong("diagnosticFailures")>0L)
    val store=SessionStore(Context(root.path))
    val scan=store.create(JSONObject().put("width",2).put("height",2))
    val fixture=File(root,"native-source.png")
    fixture.writeBytes(java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAEklEQVR4nGP4z8DAAMIM/4EAAB/uBfsL2WiLAAAAAElFTkSuQmCC"))
    val rgb=fixture.readBytes()
    val metadata=JSONObject().put("width",2).put("height",2)
    val report=DoubleArray(15).apply { this[1]=1.0;this[6]=1.0;this[10]=1.0;this[14]=1.0 }
    store.saveFrameFile(scan,1,fixture,report,metadata)
    val archived=File(scan,"frame-00001.png")
    check(archived.readBytes().contentEquals(rgb) && store.frames(scan)==listOf(archived))
    store.saveFrameFile(scan,1,archived,report,metadata)
    check(archived.readBytes().contentEquals(rgb))
    check(JSONObject(File(scan,"manifest.json").readText()).getJSONArray("frames").length()==1)
    store.updateCameraInfo(scan,JSONObject().put("cameraId","test-lens").put("actualCaptureMode","detail"))
    val refreshed=JSONObject(File(scan,"manifest.json").readText())
    check(refreshed.getJSONObject("camera").getString("cameraId")=="test-lens")
    check(refreshed.getJSONArray("frames").length()==1 && refreshed.has("environment"))
    check(JSONObject(File(scan,"frame-00001.json").readText()).getJSONArray("referenceToSourceHomography").length()==9)
    check(runCatching { store.saveFrameFile(scan,1,fixture,report,JSONObject().put("width",3).put("height",2)) }.isFailure)
    check(archived.readBytes().contentEquals(rgb))
    store.finishFile(scan,archived,2,2,doubleArrayOf(0.0,0.0,0.0),90,1)
    val result=store.result(scan)
    check(result.readBytes().contentEquals(rgb))
    store.finishFile(scan,result,2,2,doubleArrayOf(0.0,0.0,0.0),90,1)
    val manifest=JSONObject(File(scan,"manifest.json").readText())
    check(manifest.getString("resultOrientation")=="sensor" && manifest.getInt("acceptedSourceCount")==1)
    File(scan,"archive.dng").writeText("exclude")
    File(scan,"camera-preview.json").writeText("""{"packets":[]}""")
    File(scan,"camera-preflight.json").writeText("""{"packets":[]}""")
    val zip=java.io.ByteArrayOutputStream()
    store.exportDiagnosticsZip(scan,zip)
    val names=mutableSetOf<String>()
    ZipInputStream(zip.toByteArray().inputStream()).use { input ->
        while(true){val entry=input.nextEntry ?: break;names.add(entry.name);input.readBytes()}
    }
    check("frame-00001.json" in names && "manifest.json" in names && "diagnostic-summary.json" in names)
    check("camera-preview.json" in names)
    check("camera-preflight.json" in names)
    check(names.none { it.endsWith(".png") || it.endsWith(".dng") })
    println("Session storage: native source install/copy, same-path reuse, dimensions, metadata and compact ZIP exclusions passed")
    println("Diagnostics: event/byte/preview caps, ownership, concurrency, recovery and failure containment passed")
}
''')
        classpath = os.pathsep.join(map(str, [classes, stdlib, json]))
        run("java", "-Xmx1g", "-cp", os.pathsep.join(map(str, compiler)),
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
            "-jvm-target", "17", "-classpath", classpath, "-d", classes,
            ROOT / "app/src/main/java/org/toit/lumascan/DiagnosticsStore.kt",
            ROOT / "app/src/main/java/org/toit/lumascan/SessionStore.kt", harness)
        run("java", "-cp", classpath, "org.toit.lumascan.DiagnosticsTestKt", work / "sessions")


if __name__ == "__main__":
    main()
