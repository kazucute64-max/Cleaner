# StorageSweep ProGuard/R8 rules for release builds.

# Keep Shizuku API/AIDL-related classes — reflection + binder marshalling relies on exact names.
-keep class rikka.shizuku.** { *; }
-keep class dev.rikka.shizuku.** { *; }
-keep interface com.storagesweep.app.shizuku.IPrivilegedFileService { *; }
-keep class com.storagesweep.app.shizuku.IPrivilegedFileService$* { *; }

# Keep AIDL-generated Parcelable/Stub classes in general.
-keep class * extends android.os.Binder
-keep class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Kotlin coroutines / kotlinx.serialization-style reflection safety (defensive, low-cost).
-dontwarn kotlinx.coroutines.**
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

# Jetpack Compose keeps its own consumer rules via the AAR; nothing extra needed here.

# DataStore preferences use protobuf-lite internally via AndroidX; keep generated schema classes.
-keep class androidx.datastore.** { *; }
