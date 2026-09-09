-repackageclasses ''
-allowaccessmodification
-mergeinterfacesaggressively
-overloadaggressively

# Strip verbose/debug/info/warn logging from release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}

# Signature / InnerClasses / EnclosingMethod / RuntimeVisible*Annotations are
# kept by the AGP-generated default rules (needed for Gson generic type
# resolution, Guava TypeToken and kotlinx.serialization).
# LineNumberTable omitted intentionally – saves significant DEX space.
# Stack traces still show class + method; -renamesourcefileattribute masks file names.
-renamesourcefileattribute SourceFile

# ─────────────────────────────────────────────────────────────
# Gson models – field names must match Syncthing REST JSON keys
# (no @SerializedName; plain Kotlin data classes deserialised via
#  Gson reflectively via TypeToken<T>, so obfuscation of class
#  names is safe – the Signature attribute preserves the type.
#  The unqualified -keepclassmembers below preserves field names.)
# ─────────────────────────────────────────────────────────────
-keep,allowobfuscation class com.micnubinub.syncthing.model.** {
    <fields>;
    <init>(...);
}
-keepclassmembers class com.micnubinub.syncthing.model.** {
    <fields>;
}

# SharedPrefsBackup round-trips through Gson within the same build, so
# field names can be obfuscated, but fields must survive shrinking.
-keep,allowobfuscation class com.micnubinub.syncthing.service.SyncthingService$SharedPrefsBackup {
    <fields>;
    <init>(...);
}

# Guava TypeToken used with Gson (anonymous subclasses resolved reflectively)
-keep,allowobfuscation class com.google.common.reflect.TypeToken
-keep,allowobfuscation class * extends com.google.common.reflect.TypeToken

# ─────────────────────────────────────────────────────────────
# Reflection / platform internals
# ─────────────────────────────────────────────────────────────
-dontwarn android.os.storage.StorageVolume
-dontwarn android.app.LoadedApk
-dontwarn android.net.ProxyInfo
-dontwarn android.net.http.SslCertificate

# ─────────────────────────────────────────────────────────────
# Suppress warnings for optional / platform providers
# ─────────────────────────────────────────────────────────────
-dontwarn at.favre.lib.crypto.bcrypt.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.openjsse.**
-dontwarn sun.misc.Unsafe
