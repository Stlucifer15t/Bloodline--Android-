# Project specific ProGuard / R8 rules.
#
# No shrinking is enabled yet (isMinifyEnabled = false). The rules below are
# placeholders for the point where the compatibility runtime is integrated:
# Wine, Box86/Box64 and the Mesa graphics drivers are native libraries, and
# their JNI entry points must be preserved once code shrinking is switched on.

# Keep any JNI entry points used by the future native runtime.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep the names of classes that the native runtime looks up reflectively
# from C/C++ (added when the runtime bridge is implemented).
# -keep class com.bloodline.android.runtime.bridge.** { *; }
