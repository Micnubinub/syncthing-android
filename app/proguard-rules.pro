-repackageclasses ''
-overloadaggressively

# Strip verbose/debug/info/warn logging from release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}

# -allowaccessmodification and the Signature / InnerClasses / EnclosingMethod /
# RuntimeVisible*Annotations attributes come from proguard-android-optimize.txt.
# LineNumberTable and SourceFile are omitted intentionally to save DEX space.

# ─────────────────────────────────────────────────────────────
# Gson – no @SerializedName, so field names are the JSON keys.
# Model classes: keys must match the Syncthing REST / config JSON.
# SharedPrefsBackup: written by config export and read by import, possibly
#   from another app version, so its keys must be stable across builds.
# Class names may be obfuscated. Constructors are kept so R8 treats the
# classes as instantiated (Gson creates them reflectively).
# LocalCompletion / RemoteCompletion and the ViewModels in model/ are not
# serialised and are excluded.
# ─────────────────────────────────────────────────────────────
-keep,allowobfuscation class !com.micnubinub.syncthing.model.*Completion,!com.micnubinub.syncthing.model.*ViewModel*,com.micnubinub.syncthing.model.** {
    <init>(...);
}
-keep,allowobfuscation class com.micnubinub.syncthing.service.SyncthingService$SharedPrefsBackup {
    <init>(...);
}
-keepclassmembers class !com.micnubinub.syncthing.model.*Completion,!com.micnubinub.syncthing.model.*ViewModel*,com.micnubinub.syncthing.model.** {
    <fields>;
}
-keepclassmembers class com.micnubinub.syncthing.service.SyncthingService$SharedPrefsBackup {
    <fields>;
}

# Guava TypeToken is used with Gson. The anonymous subclasses need their generic
# Signature, which R8 full mode only keeps on kept classes. Gson's bundled rules
# cover com.google.gson.reflect.TypeToken only.
-keep,allowobfuscation,allowshrinking class * extends com.google.common.reflect.TypeToken
