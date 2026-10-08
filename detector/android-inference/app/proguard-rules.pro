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
