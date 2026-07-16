# EyePlus AI - ProGuard Rules

# Keep ONVIF data models (serialization)
-keepclassmembers class com.eyeplus.data.onvif.** { *; }

# Keep AI data models (JSON serialization)
-keep class com.eyeplus.data.ai.** { *; }

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep Media3
-dontwarn androidx.media3.**

# Keep ML Kit
-dontwarn com.google.mlkit.**

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.eyeplus.**$$serializer { *; }
-keepclassmembers class com.eyeplus.** { *** Companion; }
-keepclasseswithmembers class com.eyeplus.** { kotlinx.serialization.KSerializer serializer(...); }

# Keep Compose
-dontwarn androidx.compose.**

# Keep G.711 codec (native methods if any)
-keep class com.eyeplus.data.audio.G711Codec { *; }
