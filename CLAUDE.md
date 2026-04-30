# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

ThreeDRenderer is an Android SDK library (`lib3drenderer`) for rendering interactive 3D GLB models with touch controls, label overlays, and on-device encrypted-model support. The SDK is offline-only — all models are loaded from local storage (`filesDir`, external app-private storage, or `assets`); there is no network code in the library. The `app` module is a demo application.

## Build Commands

```bash
./gradlew assembleDebug           # Build debug APK
./gradlew assembleRelease         # Build release APK
./gradlew :lib3drenderer:assembleRelease  # Build library AAR
./gradlew test                    # Unit tests
./gradlew connectedAndroidTest    # Instrumented tests (device required)
./gradlew lint                    # Lint checks
```

The native C++ (`render_bridge.cpp`) is compiled via CMake automatically during the Gradle build. NDK must be installed; ABI targets are `armeabi-v7a` and `arm64-v8a`.

## Architecture

### Module Structure
- **`lib3drenderer`** — The SDK. All library code lives here.
- **`app`** — Demo app consuming the SDK via direct module dependency.

### SDK Entry Point: `Tutar` (Singleton)
`Tutar.kt` is the public API facade. Callers call `Tutar.initialize(context, callback)` once, then `Tutar.createContainer(...)` to get `Container3D` views. Initialization state is tracked via LiveData (`NOT_INITIALIZED → INITIALIZING → READY/FAILED`). Callbacks queued during init are flushed on completion.

### Filament Engine Architecture
The SDK uses a **shared single Filament Engine/Renderer/ResourceManager** to reduce memory overhead, but each `Container3D` has its **own Scene and Camera**:

```
FilamentEngineManager (facade)
  ├── FilamentEngineProvider  — shared Engine + Renderer (singleton)
  ├── FilamentResourceManager — shared materials (UbershaderProvider), asset loader, KTX lighting
  ├── FilamentRenderLoop      — single render loop dispatching to all containers
  └── per-container:
        Container3DRenderer   — owns Scene, View, Camera, AnimationController, CameraController
```

`FilamentEngineManager` and `FilamentEngineProvider` are singletons initialized once. The shared `ResourceManager` caches materials so they are not duplicated per container.

### Container3D Widget
`Container3D.kt` (~1200 lines) is a `FrameLayout` that wraps a `SurfaceView` and the full interaction layer:
- **Touch**: drag to move, pinch to scale, corner handles to resize
- **Resize optimization**: pauses the Filament renderer and shows a cached bitmap snapshot during resize to avoid surface conflicts
- **Controls panel**: auto-hides after 4 seconds; buttons for animation toggle, labels, recenter, close
- **Model search order**: `filesDir/models/`, external storage, `assets/`, encrypted folders

### Model Security (Split-Key Design)
Encrypted `.glb` files use AES/CBC with OpenSSL-compatible EVP_BytesToKey derivation. The decryption password is split across two layers:
- **Kotlin** (`NativeKeyProvider.kt`): holds a 16-byte mask fragment derived via arithmetic
- **Native** (`render_bridge.cpp`): holds XOR-encoded 32-byte blob + its own 16-byte mask

The JNI call combines both halves, XORs the blob, and returns the plaintext password. Neither layer alone is sufficient. Sensitive stack data is zeroed after use.

### Label System
Labels are extracted from GLB node metadata (`GlbLabelExtractor`) and displayed as overlay `View`s positioned by projecting 3D world coordinates to 2D screen space. `LabelOverlayManager` updates positions per-frame (throttled at ~66 ms). Connector lines are drawn via `LabelLineView`.

### Model Loading (Offline Only)
The SDK has **no network code**. Models must be present on disk before `Container3D` is asked to load them. `Container3D.resolveAndDecryptInBackground` searches, in order:
1. `{filesDir}/models/{filename}`
2. `{externalFilesDir}/models/{filename}`
3. The full path passed by the caller
4. `{filesDir}/3d_models/{filename}`
5. `{filesDir}/encrypted_models/{filename}`
6. `assets/models/{filename}` and `assets/{filename}`

Encrypted files are detected by the `Salted__` prefix and routed through `ModelDecryptionUtil`; everything else is loaded as a plain GLB. Resolution + decryption run on a single-thread executor (`Container3D.LOAD_EXECUTOR`); the result is posted back to the main thread for `Container3DRenderer.loadModel`.

## Key Constraints

- **Filament operations must run on the main thread.** Use `Handler(Looper.getMainLooper())` for any Filament calls originating off-thread.
- **Single Engine instance**: `FilamentEngineProvider` is a singleton. Do not create additional Engine instances; destroying it affects all containers.
- **Container lifecycle**: always call `Tutar.pauseAll()` / `resumeAll()` in `Activity.onPause` / `onResume`. Failing to pause will crash when the surface is destroyed.
- **NDK ABIs**: only `armeabi-v7a` and `arm64-v8a` are configured. Do not add x86/x86_64 without updating `abiFilters` in `lib3drenderer/build.gradle.kts`.
- **Min SDK 24**: avoid APIs above 24 without version guards.

## Dependencies & Versions

| Dependency | Version |
|---|---|
| Google Filament (`filament-android`, `filament-utils-android`, `gltfio-android`) | 1.43.1 |
| Android Gradle Plugin | 8.2.1 |
| Kotlin | 1.9.0 |
| compileSdk / targetSdk | 34 |
| minSdk | 24 |
