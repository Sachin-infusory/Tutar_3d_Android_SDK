# =====================================================================
# ProGuard rules applied to lib3drenderer's own minification step
# (release build with isMinifyEnabled = true). Mirrors consumer-rules.pro
# so the AAR ships with the same surface kept whether the consumer
# minifies or not.
# =====================================================================

# Preserve line numbers in stack traces; hide the original source filename.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------- Public SDK API ----------
-keep public class com.infusory.tutar3d.Tutar { *; }
-keep public class com.infusory.tutar3d.Tutar$* { *; }

-keep public class com.infusory.tutar3d.InitCallback { *; }
-keep public class com.infusory.tutar3d.LoadingCallback { *; }
-keep public class com.infusory.tutar3d.LoadedCallback { *; }
-keep public class com.infusory.tutar3d.ErrorCallback { *; }
-keep public class com.infusory.tutar3d.RemoveCallback { *; }

-keep public class com.infusory.tutar3d.containerview.Container3D { *; }
-keep public class com.infusory.tutar3d.containerview.Container3D$* { *; }

# ---------- JNI ----------
# Bound by C++ symbol name; renaming breaks System.loadLibrary lookup.
-keep class com.infusory.tutar3d.containerview.NativeKeyProvider { *; }

# Safety net for any other native method.
-keepclasseswithmembernames class * {
    native <methods>;
}

# ---------- Kotlin / AndroidX ----------
-keep class kotlin.Metadata { *; }

-keep class androidx.lifecycle.** { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel { *; }

-keepattributes *Annotation*
