# Retain runtime metadata consumed by Hilt, Room and Kotlin serialization.
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

# The app-owned ETC2 encoder resolves this native method from libwallhub_etc2.so; R8 must
# not rename it or remove the class that declares it.
-keep class com.wallhub.prototype.mpkg.Etc2Compressor { *; }

# lz4-java instantiates its JNI/Unsafe/Safe implementations reflectively from LZ4Factory.
-keep class net.jpountz.** { *; }
-dontwarn net.jpountz.**
