# ONNX Runtime
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# OkHttp
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class com.xgwnje.visionguard.data.model.** { *; }

# CameraX
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# WebSocket (OkHttp-based)
-keep class org.java_websocket.** { *; }
-dontwarn org.java_websocket.**

# Compose (must keep)
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Keep data classes used in serialization
-keep class com.xgwnje.visionguard.** { *; }

# Remove debug logging in release
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
