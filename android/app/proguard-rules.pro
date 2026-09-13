# Add project-specific ProGuard rules here.
# Keep JNI-bridge classes/methods intact — R8 must never rename anything the
# native layer looks up by name/signature.
-keepclasseswithmembernames class * {
    native <methods>;
}
