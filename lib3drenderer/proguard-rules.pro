# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# Keep your own public API and internal reflection code
-keep class com.infusory.lib3drenderer.Tutar {*;}
-keep class com.infusory.lib3drenderer.containerview.ModelData {*;}
# Keep Kotlin metadata (important for data classes and default params)
-keep class kotlin.Metadata { *; }

-keep class androidx.lifecycle.** { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel { *; }

# Keep Gson model attributes
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
# Preserve annotations (important for Retrofit)
-keepattributes *Annotation*