#include <jni.h>
#include <string>
#include <cstring>

namespace {
constexpr char kOfficialSha256[] =
        "C3CEC28612A951F18A0016CD193B5CAD5404905712EE0A4BFC593FDFE0E83281";

bool clear_exception(JNIEnv* env) {
    if (!env->ExceptionCheck()) return false;
    env->ExceptionClear();
    return true;
}

jobject get_signatures(JNIEnv* env, jobject context) {
    jclass context_class = env->GetObjectClass(context);
    jmethodID get_pm = env->GetMethodID(context_class, "getPackageManager",
                                         "()Landroid/content/pm/PackageManager;");
    jmethodID get_package = env->GetMethodID(context_class, "getPackageName",
                                              "()Ljava/lang/String;");
    if (!get_pm || !get_package || clear_exception(env)) return nullptr;

    jobject pm = env->CallObjectMethod(context, get_pm);
    jstring package_name = static_cast<jstring>(env->CallObjectMethod(context, get_package));
    if (clear_exception(env) || !pm || !package_name) return nullptr;

    jclass build_version = env->FindClass("android/os/Build$VERSION");
    jfieldID sdk_field = env->GetStaticFieldID(build_version, "SDK_INT", "I");
    jint sdk = env->GetStaticIntField(build_version, sdk_field);

    jclass pm_class = env->GetObjectClass(pm);
    jmethodID get_info = env->GetMethodID(pm_class, "getPackageInfo",
                                           "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;");
    if (!get_info) return nullptr;
    const jint flags = sdk >= 28 ? 0x08000000 : 0x00000040;
    jobject info = env->CallObjectMethod(pm, get_info, package_name, flags);
    if (clear_exception(env) || !info) return nullptr;

    jclass info_class = env->GetObjectClass(info);
    if (sdk >= 28) {
        jfieldID signing_field = env->GetFieldID(info_class, "signingInfo",
                                                  "Landroid/content/pm/SigningInfo;");
        if (!signing_field) return nullptr;
        jobject signing_info = env->GetObjectField(info, signing_field);
        if (!signing_info || clear_exception(env)) return nullptr;
        jclass signing_class = env->GetObjectClass(signing_info);
        jmethodID multiple = env->GetMethodID(signing_class, "hasMultipleSigners", "()Z");
        jmethodID current = env->GetMethodID(signing_class, "getApkContentsSigners",
                "()[Landroid/content/pm/Signature;");
        if (!multiple || !current || env->CallBooleanMethod(signing_info, multiple) == JNI_TRUE
                || clear_exception(env)) return nullptr;
        return env->CallObjectMethod(signing_info, current);
    }

    jfieldID signatures_field = env->GetFieldID(info_class, "signatures",
            "[Landroid/content/pm/Signature;");
    if (!signatures_field) return nullptr;
    return env->GetObjectField(info, signatures_field);
}

std::string sha256_hex(JNIEnv* env, jbyteArray bytes) {
    jclass digest_class = env->FindClass("java/security/MessageDigest");
    jmethodID get_instance = env->GetStaticMethodID(digest_class, "getInstance",
            "(Ljava/lang/String;)Ljava/security/MessageDigest;");
    jstring algorithm = env->NewStringUTF("SHA-256");
    jobject digest = env->CallStaticObjectMethod(digest_class, get_instance, algorithm);
    if (!digest || clear_exception(env)) return {};
    jmethodID digest_method = env->GetMethodID(digest_class, "digest", "([B)[B");
    auto result = static_cast<jbyteArray>(env->CallObjectMethod(digest, digest_method, bytes));
    if (!result || clear_exception(env)) return {};

    const jsize length = env->GetArrayLength(result);
    jbyte* data = env->GetByteArrayElements(result, nullptr);
    static const char hex[] = "0123456789ABCDEF";
    std::string output;
    output.reserve(static_cast<size_t>(length) * 2);
    for (jsize i = 0; i < length; ++i) {
        unsigned char value = static_cast<unsigned char>(data[i]);
        output.push_back(hex[value >> 4]);
        output.push_back(hex[value & 0x0F]);
    }
    env->ReleaseByteArrayElements(result, data, JNI_ABORT);
    return output;
}
} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_goblinmini_kinozalclient_NativeSignatureGuard_verifyNative(
        JNIEnv* env, jclass, jobject context) {
    if (!context) return JNI_FALSE;
    jobject signatures = get_signatures(env, context);
    if (!signatures || clear_exception(env)) return JNI_FALSE;

    jclass array_class = env->GetObjectClass(signatures);
    jmethodID length_method = env->GetMethodID(array_class, "getClass", "()Ljava/lang/Class;");
    (void) length_method;
    jclass signature_class = env->FindClass("android/content/pm/Signature");
    jmethodID to_bytes = env->GetMethodID(signature_class, "toByteArray", "()[B");
    if (!to_bytes || env->GetArrayLength(static_cast<jarray>(signatures)) != 1) return JNI_FALSE;

    jobject signature = env->GetObjectArrayElement(static_cast<jobjectArray>(signatures), 0);
    auto bytes = static_cast<jbyteArray>(env->CallObjectMethod(signature, to_bytes));
    if (!bytes || clear_exception(env)) return JNI_FALSE;
    const std::string actual = sha256_hex(env, bytes);
    return actual.size() == 64 && actual == kOfficialSha256 ? JNI_TRUE : JNI_FALSE;
}
