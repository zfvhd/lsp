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

-keep class de.robv.android.xposed.** {*;}
-keep class android.** { *; }
-keepclasseswithmembers class io.github.lsposed.lspd.core.Main {
    public static void forkSystemServerPost(android.os.IBinder);
    public static void forkAndSpecializePost(java.lang.String, java.lang.String, android.os.IBinder);
    public static void main(java.lang.String[]);
}
-keepnames class io.github.lsposed.lspd.hooker.HandleBindAppHooker
-keepclasseswithmembers class io.github.lsposed.lspd.nativebridge.* {
    native <methods>;
}
-keepclasseswithmembers class io.github.lsposed.lspd.nativebridge.ClassLinker {
    public static void onPostFixupStaticTrampolines(java.lang.Class);
}
-keepclasseswithmembers class io.github.lsposed.lspd.service.BridgeService {
    public static boolean execTransact(int, long, long, int);
    public static android.os.IBinder getApplicationServiceForSystemServer(android.os.IBinder, android.os.IBinder);
}
-keepclasseswithmembers class io.github.lsposed.lspd.service.ConfigManager {
    public static void main(java.lang.String[]);
}
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
}
