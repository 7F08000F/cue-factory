# Cue Factory — keep rules minimal for MVP scaffold

# Preserve domain models / enums used across modules (defensive for R8).
-keep class com.cuefactory.core.model.** { *; }
-keep class com.cuefactory.core.settings.** { *; }

# Logging
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
