// Phase 0/1 stub JNI entry point. Confirms the Kotlin shell <-> native/
// (C++) boundary links and loads correctly on-device before any real engine
// code (networking/protocol/renderer/etc.) is written there.
//
// Real usage starts at Phase 3 (map connection) per
// docs/FATE_MMO_MOBILE_ROADMAP.md, when networking migrates from the Kotlin
// PoC (android/app/.../net/LoginClient.kt) into native/networking +
// native/protocol.

#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_com_fatemmo_mobile_util_NativeBridge_stubEngineVersion(JNIEnv* env, jobject /* this */) {
    std::string version = "fatemmo-native-stub-0.0.1";
    return env->NewStringUTF(version.c_str());
}
