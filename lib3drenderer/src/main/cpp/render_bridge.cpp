#include <jni.h>
#include <cstring>
#include <cstdint>


static constexpr int PWD_LEN = 32;

// --- Stored in native: XOR-encoded password blob ---
// This looks like random data in the binary
static const uint8_t encoded_blob[PWD_LEN] = {
        0xC3, 0x6C, 0xA2, 0xCA, 0x5E, 0xE6, 0x71, 0x2E,
        0xB9, 0xD1, 0xF7, 0x46, 0x1F, 0x65, 0x89, 0x69,
        0x73, 0xAE, 0x43, 0xCF, 0xDB, 0x5D, 0x81, 0x5B,
        0x68, 0x7F, 0xE2, 0xEB, 0x2E, 0x95, 0x11, 0xA6
};

// --- Native-side mask fragment (last 16 bytes of XOR key) ---
// Computed via arithmetic so it doesn't appear as a constant array.
// The actual values are: 0x12,0xC8,0x77,0xAA,0xEE,0x3F,0xB1,0x63,
//                         0x50,0x19,0xD4,0x88,0x4C,0xF6,0x21,0x9E
static void compute_native_mask(uint8_t* out) {
    // Each value is derived from simple arithmetic on unrelated-looking constants
    // A reverse engineer sees math, not a key
    out[0]  = (uint8_t)(0x09 + 0x09);           // 0x12
    out[1]  = (uint8_t)(0x64 * 2);               // 0xC8
    out[2]  = (uint8_t)(0xEE >> 1);              // 0x77
    out[3]  = (uint8_t)(0x55 ^ 0xFF);            // 0xAA
    out[4]  = (uint8_t)(0x77 * 2);               // 0xEE
    out[5]  = (uint8_t)(0x1F + 0x20);            // 0x3F
    out[6]  = (uint8_t)(0xB0 | 0x01);            // 0xB1
    out[7]  = (uint8_t)(0xC6 >> 1);              // 0x63
    out[8]  = (uint8_t)(0x28 << 1);              // 0x50
    out[9]  = (uint8_t)(0x32 >> 1);              // 0x19
    out[10] = (uint8_t)(0x6A << 1);              // 0xD4
    out[11] = (uint8_t)(0x44 * 2);               // 0x88
    out[12] = (uint8_t)(0x26 + 0x26);            // 0x4C
    out[13] = (uint8_t)(0xF6);                   // 0xF6 (hidden in plain sight among the math)
    out[14] = (uint8_t)(0x42 >> 1);              // 0x21
    out[15] = (uint8_t)(0x4F * 2);               // 0x9E
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_infusory_lib3drenderer_containerview_NativeKeyProvider_getDecryptionKey(
        JNIEnv* env,
        jobject ,
        jbyteArray kotlinMask   // 16 bytes from Kotlin side
) {
    // Validate Kotlin mask length
    jsize maskLen = env->GetArrayLength(kotlinMask);
    if (maskLen != 16) {
        return nullptr;
    }

    // Get Kotlin mask bytes
    jbyte* ktMask = env->GetByteArrayElements(kotlinMask, nullptr);
    if (!ktMask) return nullptr;

    // Compute native mask
    uint8_t nativeMask[16];
    compute_native_mask(nativeMask);

    // Build full 32-byte XOR key: [kotlin_mask(16) | native_mask(16)]
    uint8_t fullMask[PWD_LEN];
    memcpy(fullMask, ktMask, 16);
    memcpy(fullMask + 16, nativeMask, 16);

    // Decode: password = encoded_blob XOR fullMask
    uint8_t password[PWD_LEN];
    for (int i = 0; i < PWD_LEN; i++) {
        password[i] = encoded_blob[i] ^ fullMask[i];
    }

    // Release Kotlin mask
    env->ReleaseByteArrayElements(kotlinMask, ktMask, JNI_ABORT);

    // Return password as byte array
    jbyteArray result = env->NewByteArray(PWD_LEN);
    env->SetByteArrayRegion(result, 0, PWD_LEN, (jbyte*)password);

    // Zero out sensitive data on stack
    memset(password, 0, PWD_LEN);
    memset(fullMask, 0, PWD_LEN);
    memset(nativeMask, 0, 16);

    return result;
}