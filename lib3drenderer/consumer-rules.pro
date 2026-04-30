# =====================================================================
# Consumer ProGuard rules for lib3drenderer.
# These rules are bundled into the AAR and applied automatically when a
# consumer app runs R8/ProGuard.
# =====================================================================

# ---------- Public SDK API ----------
-keep public class com.infusory.tutar3d.Tutar { *; }
-keep public class com.infusory.tutar3d.Tutar$* { *; }

# Top-level fun interfaces used by Java callers (InitCallback, LoadingCallback,
# LoadedCallback, ErrorCallback, RemoveCallback). They live at the package root.
-keep public class com.infusory.tutar3d.InitCallback { *; }
-keep public class com.infusory.tutar3d.LoadingCallback { *; }
-keep public class com.infusory.tutar3d.LoadedCallback { *; }
-keep public class com.infusory.tutar3d.ErrorCallback { *; }
-keep public class com.infusory.tutar3d.RemoveCallback { *; }

-keep public class com.infusory.tutar3d.containerview.Container3D { *; }
-keep public class com.infusory.tutar3d.containerview.Container3D$* { *; }

# ---------- JNI ----------
# NativeKeyProvider.getDecryptionKey is bound from C++ via the symbol
#   Java_com_infusory_tutar3d_containerview_NativeKeyProvider_getDecryptionKey
# Renaming the class or method would break System.loadLibrary lookup at runtime,
# so the whole class must be kept verbatim.
-keep class com.infusory.tutar3d.containerview.NativeKeyProvider { *; }

# Safety net: preserve the original name of every native method anywhere in
# the app, so any JNI binding (Filament, our own, transitive) keeps resolving.
-keepclasseswithmembernames class * {
    native <methods>;
}

# ---------- Kotlin / AndroidX ----------
-keep class kotlin.Metadata { *; }

-keep class androidx.lifecycle.** { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel { *; }

# Preserve runtime-visible annotations (Filament + AndroidX rely on these).
-keepattributes *Annotation*

# Keep the JNI source file/line metadata so native crashes remain symbolicated.
-keepattributes SourceFile,LineNumberTable
