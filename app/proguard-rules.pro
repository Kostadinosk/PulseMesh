# Add project specific ProGuard rules here.
# Preserve line numbers for debugging stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Preserve Kotlin metadata and annotations
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep PulseMesh JSON & serialization models
-keep class com.example.model.** { *; }
-keep class com.example.service.WireMeshPacket { *; }
-keep class com.example.service.MeshPacketPayload { *; }
-keep class com.example.service.PulseMeshWebServer** { *; }
-keep class com.example.service.WebPortalStatus { *; }
-keep class com.example.service.WebClientRegistration { *; }
-keep class com.example.service.WebSubmittedPacket { *; }
-keep class com.example.util.QrCodeGenerator { *; }
-keep class com.example.crypto.UsernameClaimRecord { *; }
-keep class com.example.crypto.TacticalE2eeManager** { *; }
-keep class com.example.util.NativeTileMapManager$CachedTileChunk { *; }

# Keep Android org.json and coroutines classes used reflectively
-keep class org.json.** { *; }
-dontwarn kotlinx.coroutines.**
