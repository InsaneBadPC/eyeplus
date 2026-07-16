# Keep Gemini/ML Kit models
-keep class com.google.mlkit.** { *; }
-keep class com.google.firebase.** { *; }

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.eyeplus.**$$serializer { *; }
-keepclassmembers class com.eyeplus.** {
    *** Companion;
}
-keepclasseswithmembers class com.eyeplus.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep Media3
-keep class androidx.media3.** { *; }
