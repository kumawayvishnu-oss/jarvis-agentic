# JARVIS Agentic — Safe R8 Shrinking & Keep Rules
-dontobfuscate
-keep class com.jarvis.assistant.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

# Keep Vosk and JNA native libraries & models
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-keep class net.java.dev.jna.** { *; }
# JNA ships optional desktop AWT bridges that are not present on Android; app code does not use them.
-dontwarn java.awt.**

# Keep Room database & Gson
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase
-keep class com.google.gson.** { *; }

