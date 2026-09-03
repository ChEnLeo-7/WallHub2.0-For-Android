# Retain runtime metadata consumed by Hilt, Room, Kotlin serialization and Wire.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# SpongyCastle registers the provider and RSA cipher implementations by class name
# (WallHub's private CA for Steam TLS bridging).
-keep class org.spongycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.spongycastle.jce.provider.BouncyCastleProvider$* { *; }
-keep class org.spongycastle.jcajce.provider.asymmetric.RSA { *; }
-keep class org.spongycastle.jcajce.provider.asymmetric.RSA$Mappings { *; }
-keep class org.spongycastle.jcajce.provider.asymmetric.rsa.** { *; }

# Navigation route serializers are generated from these annotations.
-if @kotlinx.serialization.Serializable class **
-keep,allowoptimization,allowshrinking class <1>$$serializer { *; }
-keepclassmembers class ** {
    *** Companion;
}

# kSteam persists protocol models through kotlinx.serialization and Wire reflection.
-keep class bruhcollective.itaysonlab.ksteam.** { *; }
-keep class steam.** { *; }
-dontwarn bruhcollective.itaysonlab.ksteam.**
-dontwarn steam.**

# wallhub-rust resolves these native methods from libwallhub_rust.so; R8 must not rename them.
-keepclasseswithmembernames class com.wallhub.android.data.downloads.WallHubRust {
    native <methods>;
}
