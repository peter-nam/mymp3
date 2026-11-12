# Add project specific ProGuard rules here.

# --- Kotlin / kotlinx.serialization -----------------------------------------
# The serialization plugin generates serializers; keep the ones for our models.
-keep class com.pnam.mymp3.data.** { *; }
-keepclassmembers class **$$serializer {
    public static ** INSTANCE;
}

# --- Media3 ------------------------------------------------------------------
# Consumer rules ship with the media3 artefacts; nothing extra needed today.

# Keep the MediaSessionService entry point referenced only from the manifest.
-keep class com.pnam.mymp3.player.AudioService
