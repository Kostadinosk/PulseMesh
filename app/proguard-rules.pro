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
-keep,includedescriptorclasses class net.zetetic.database.** { *; }
-keep,includedescriptorclasses interface net.zetetic.database.** { *; }
-dontwarn net.zetetic.database.**

# Keep WebRTC core classes, Chromium jni_zero runtime & JNI hooks (full flavor)
-keep,includedescriptorclasses class org.jni_zero.** { *; }
-keep,includedescriptorclasses interface org.jni_zero.** { *; }
-dontwarn org.jni_zero.**
-keep,includedescriptorclasses class org.webrtc.** { *; }
-keep,includedescriptorclasses interface org.webrtc.** { *; }
-dontwarn org.webrtc.**
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keepclassmembers,includedescriptorclasses class * {
    @org.jni_zero.CalledByNative *;
    @org.jni_zero.AccessedByNative *;
    @org.webrtc.CalledByNative *;
}
-keep class io.getstream.webrtc.android.** { *; }
-dontwarn io.getstream.webrtc.android.**

# Keep MapLibre Native JNI classes (full flavor), CameraX & ZXing
-keep,includedescriptorclasses class org.maplibre.** { *; }
-keep,includedescriptorclasses interface org.maplibre.** { *; }
-dontwarn org.maplibre.**
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# Keep Android org.json and coroutines classes used reflectively
-keep class org.json.** { *; }
-dontwarn kotlinx.coroutines.**
