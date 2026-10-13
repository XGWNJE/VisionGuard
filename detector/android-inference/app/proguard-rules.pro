# ONNX Runtime Android 通过 JNI 反射加载会话，保留其类与原生方法。
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }

# Gson 通过反射读写协议与配置对象。
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepclassmembers,allowobfuscation class com.xgwnje.visionguard.inference.** {
    <fields>;
    <init>(...);
}

# OkHttp 在 Android 上的可选平台实现。
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# WorkManager / Room：CameraX 传递引入 `androidx.work:work-runtime`，它经 androidx.startup 在
# ContentProvider 阶段初始化，并用 `WorkDatabase.canonicalName` 反射取类名，Room 生成的库实现也按类名
# 反射加载。R8 收缩会把它们删掉或改名，进程在启动 provider 时就崩：
#   java.lang.RuntimeException: Unable to get provider androidx.startup.InitializationProvider
#   Caused by: Failed to create an instance of class androidx.work.impl.WorkDatabase.canonicalName
# 这个崩溃只在 minify 的 release 包里出现（debug 包正常），所以必须靠规则保住。
-keep class androidx.work.** { *; }
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.work.**
-dontwarn androidx.room.**

# LiteRT 的原生桥（liblitert_jni.so）按硬编码类名查找 Java 类型来构造返回值与抛异常。
# R8 只自动保住带 native 方法的类（Environment / CompiledModel / TensorBuffer / TensorType），
# 这几个只被原生层按名字用到、又不在任何被保签名里的类型会被删掉或改名：
#   TensorType$ElementType、TensorType$Layout、TensorBufferRequirements、LiteRtException
# 缺类时原生层走的不是 Java 异常，而是致命 CHECK（库内 `LiteRT check failed:` 系条件串）→ 进程 abort。
# LiteRT 只在 GPU 档使用（CPU 档走 ONNX Runtime），所以症状是发行包一选 GPU 档就闪退，
# debug 包正常；AAR 自带的规则只保 @UsedByReflection 标注的成员，靠不住，必须在这里整体保留。
# 判据与真机证据见 docs/90-验证记录.md 的「Android 推理节点选 GPU 档闪退」一节。
-keep class com.google.ai.edge.litert.** { *; }
