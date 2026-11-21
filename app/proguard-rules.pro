# Preserve runtime annotations and generic signatures used by Android and libraries.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# JavaScript bridge methods are discovered by WebView at runtime.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# WorkManager persists worker class names and instantiates workers reflectively.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Keep Parcelable creators used by Android framework serialization.
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# Keep source/line metadata in the mapping file for crash retracing.
-keepattributes SourceFile,LineNumberTable
