# Add project specific ProGuard rules here.
# Preserve line numbers for debugging stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Preserve Kotlin metadata and annotations
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep PulseMesh JSON, Room, & serialization models
-keep class com.kostadinosk.pulsemesh.model.** { *; }
-keep class com.kostadinosk.pulsemesh.data.** { *; }
-keep class com.kostadinosk.pulsemesh.service.WireMeshPacket { *; }
-keep class com.kostadinosk.pulsemesh.service.MeshPacketPayload { *; }
-keep class com.kostadinosk.pulsemesh.service.PulseMeshWebServer** { *; }
-keep class com.kostadinosk.pulsemesh.service.WebPortalStatus { *; }
-keep class com.kostadinosk.pulsemesh.service.WebClientRegistration { *; }
-keep class com.kostadinosk.pulsemesh.service.WebSubmittedPacket { *; }
-keep class com.kostadinosk.pulsemesh.util.QrCodeGenerator { *; }
-keep class com.kostadinosk.pulsemesh.crypto.** { *; }
-keep class com.kostadinosk.pulsemesh.util.NativeTileMapManager$CachedTileChunk { *; }

# Keep Room & SQLCipher
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class net.zetetic.database.sqlcipher.** { *; }
-dontwarn net.zetetic.database.sqlcipher.**

# Keep WebRTC core classes & JNI hooks (full flavor)
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
-keepclasseswithmembernames class * {
    native <methods>;
}
-keepclassmembers class org.webrtc.RendererCommon { *; }
-keepclassmembers class org.webrtc.PeerConnectionFactory { *; }
-keep class io.getstream.webrtc.android.** { *; }
-dontwarn io.getstream.webrtc.android.**

# Keep Android org.json and coroutines classes used reflectively
-keep class org.json.** { *; }
-dontwarn kotlinx.coroutines.**
