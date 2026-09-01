# Retain runtime metadata consumed by Hilt, Room, Kotlin serialization and JavaSteam.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# JavaSteam resolves generated protobuf/RPC types and callbacks dynamically.
-keep class in.dragonbra.javasteam.protobufs.** { *; }
-keep class in.dragonbra.javasteam.generated.** { *; }
-keep class in.dragonbra.javasteam.rpc.** { *; }
-keep class in.dragonbra.javasteam.steam.handlers.**.callback.** { *; }
# SpongyCastle registers the provider and RSA cipher implementations by class name.
-keep class org.spongycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.spongycastle.jce.provider.BouncyCastleProvider$* { *; }
-keep class org.spongycastle.jcajce.provider.asymmetric.rsa.** { *; }
# WallHub accesses JavaSteam's configured OkHttp client for streaming chunk reads.
-keepclassmembers class in.dragonbra.javasteam.steam.cdn.Client {
    okhttp3.OkHttpClient httpClient;
}

# Navigation route serializers are generated from these annotations.
-if @kotlinx.serialization.Serializable class **
-keep,allowoptimization,allowshrinking class <1>$$serializer { *; }
-keepclassmembers class ** {
    *** Companion;
}
