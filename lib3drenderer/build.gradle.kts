plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.infusory.lib3drenderer"
    compileSdk = 34

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("com.google.android.filament:filament-utils-android:1.43.1")
    implementation("com.google.android.filament:filament-android:1.43.1")
    implementation("com.google.android.filament:gltfio-android:1.43.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation ("com.squareup.retrofit2:converter-gson:2.3.0")
    implementation("com.google.android.material:material:1.13.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}