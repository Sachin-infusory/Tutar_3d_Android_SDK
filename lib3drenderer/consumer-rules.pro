# Keep your own public API and internal reflection code
# Keep your own public API and internal reflection code
-keep class com.infusory.lib3drenderer.Tutar {*;}
-keep class com.infusory.lib3drenderer.Tutar$* {*;}
-keep class com.infusory.lib3drenderer.containerview.ModelData {*;}
-keep public class com.infusory.lib3drenderer.containerview.Container3D { *; }
-keep public class com.infusory.lib3drenderer.containerview.Container3D$* { *; }
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