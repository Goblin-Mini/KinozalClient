#include <jni.h>
#include <cctype>

#ifndef KTV_OFFICIAL_CERT_SHA256
#error "Expected release certificate hash was not injected"
#endif

namespace {
constexpr const char* kExpected = KTV_OFFICIAL_CERT_SHA256;

bool clearException(JNIEnv* env) {
    if (!env->ExceptionCheck()) return false;
    env->ExceptionClear();
    return true;
}

bool isZeroHash() {
    for (const char* p = kExpected; *p; ++p) if (*p != '0') return false;
    return true;
}

bool verify(JNIEnv* env, jobject context) {
    if (context == nullptr || isZeroHash()) return false;

    jclass contextClass = env->GetObjectClass(context);
    jmethodID getPackageManager = env->GetMethodID(
        contextClass, "getPackageManager", "()Landroid/content/pm/PackageManager;");
    if (!getPackageManager || clearException(env)) return false;
    jobject pm = env->CallObjectMethod(context, getPackageManager);
    if (clearException(env) || !pm) return false;

    jmethodID getPackageName = env->GetMethodID(
        contextClass, "getPackageName", "()Ljava/lang/String;");
    if (!getPackageName || clearException(env)) return false;
    auto packageName = static_cast<jstring>(env->CallObjectMethod(context, getPackageName));
    if (clearException(env) || !packageName) return false;

    jclass pmClass = env->GetObjectClass(pm);
    jmethodID getPackageInfo = env->GetMethodID(
        pmClass, "getPackageInfo",
        "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;");
    if (!getPackageInfo || clearException(env)) return false;

    jclass versionClass = env->FindClass("android/os/Build$VERSION");
    jfieldID sdkField = env->GetStaticFieldID(versionClass, "SDK_INT", "I");
    if (!sdkField || clearException(env)) return false;
    const jint sdk = env->GetStaticIntField(versionClass, sdkField);
    const jint flags = sdk >= 28 ? 0x08000000 : 0x00000040;
    jobject packageInfo = env->CallObjectMethod(pm, getPackageInfo, packageName, flags);
    if (clearException(env) || !packageInfo) return false;

    jclass infoClass = env->GetObjectClass(packageInfo);
    jobjectArray signatures = nullptr;
    if (sdk >= 28) {
        jfieldID signingInfoField = env->GetFieldID(
            infoClass, "signingInfo", "Landroid/content/pm/SigningInfo;");
        if (!signingInfoField || clearException(env)) return false;
        jobject signingInfo = env->GetObjectField(packageInfo, signingInfoField);
        if (clearException(env) || !signingInfo) return false;
        jclass signingClass = env->GetObjectClass(signingInfo);
        jmethodID multiple = env->GetMethodID(signingClass, "hasMultipleSigners", "()Z");
        jmethodID current = env->GetMethodID(
            signingClass, "getApkContentsSigners", "()[Landroid/content/pm/Signature;");
        if (!multiple || !current || clearException(env)) return false;
        if (env->CallBooleanMethod(signingInfo, multiple) == JNI_TRUE ||
            clearException(env)) return false;
        signatures = static_cast<jobjectArray>(env->CallObjectMethod(signingInfo, current));
    } else {
        jfieldID signaturesField = env->GetFieldID(
            infoClass, "signatures", "[Landroid/content/pm/Signature;");
        if (!signaturesField || clearException(env)) return false;
        signatures = static_cast<jobjectArray>(env->GetObjectField(packageInfo, signaturesField));
    }
    if (clearException(env) || !signatures || env->GetArrayLength(signatures) != 1) return false;

    jobject signature = env->GetObjectArrayElement(signatures, 0);
    if (!signature || clearException(env)) return false;
    jclass signatureClass = env->GetObjectClass(signature);
    jmethodID toBytes = env->GetMethodID(signatureClass, "toByteArray", "()[B");
    if (!toBytes || clearException(env)) return false;
    auto certBytes = static_cast<jbyteArray>(env->CallObjectMethod(signature, toBytes));
    if (clearException(env) || !certBytes) return false;

    jclass digestClass = env->FindClass("java/security/MessageDigest");
    jmethodID getInstance = env->GetStaticMethodID(
        digestClass, "getInstance", "(Ljava/lang/String;)Ljava/security/MessageDigest;");
    if (!getInstance || clearException(env)) return false;
    jstring algorithm = env->NewStringUTF("SHA-256");
    jobject digest = env->CallStaticObjectMethod(digestClass, getInstance, algorithm);
    if (clearException(env) || !digest) return false;
    jmethodID digestMethod = env->GetMethodID(digestClass, "digest", "([B)[B");
    if (!digestMethod || clearException(env)) return false;
    auto hash = static_cast<jbyteArray>(env->CallObjectMethod(digest, digestMethod, certBytes));
    if (clearException(env) || !hash || env->GetArrayLength(hash) != 32) return false;

    jbyte bytes[32];
    env->GetByteArrayRegion(hash, 0, 32, bytes);
    if (clearException(env)) return false;
    static constexpr char hex[] = "0123456789ABCDEF";
    char actual[65] = {};
    for (int i = 0; i < 32; ++i) {
        const unsigned char value = static_cast<unsigned char>(bytes[i]);
        actual[i * 2] = hex[value >> 4];
        actual[i * 2 + 1] = hex[value & 0x0F];
    }
    for (int i = 0; i < 64; ++i) {
        if (std::toupper(static_cast<unsigned char>(actual[i])) !=
            std::toupper(static_cast<unsigned char>(kExpected[i]))) return false;
    }
    return kExpected[64] == '\0';
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_goblinmini_kinozalclient_NativeAppGuard_nativeVerify(
    JNIEnv* env, jclass, jobject context) {
    try {
        return verify(env, context) ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}
