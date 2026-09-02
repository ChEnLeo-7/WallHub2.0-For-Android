# Retain runtime metadata consumed by Hilt, Room, Kotlin serialization and JavaSteam.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# JavaSteam uses reflection and generated dispatch code across its runtime. Partial keeps leave
# post-logon handlers callable but can still break their internal message decoding under R8.
-keep class in.dragonbra.javasteam.** { *; }
# SpongyCastle registers the provider and RSA cipher implementations by class name.
-keep class org.spongycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.spongycastle.jce.provider.BouncyCastleProvider$* { *; }
-keep class org.spongycastle.jcajce.provider.asymmetric.RSA { *; }
-keep class org.spongycastle.jcajce.provider.asymmetric.RSA$Mappings { *; }
-keep class org.spongycastle.jcajce.provider.asymmetric.rsa.** { *; }
# JavaSteam decrypts Steam depot manifests through dynamically registered AES ciphers.
-keep class org.spongycastle.jcajce.provider.symmetric.AES { *; }
-keep class org.spongycastle.jcajce.provider.symmetric.AES$Mappings { *; }
-keep class org.spongycastle.jcajce.provider.symmetric.AES$* { *; }
-keep class org.spongycastle.jcajce.provider.symmetric.util.** { *; }
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

# wallhub-rust resolves these native methods from libwallhub_rust.so; R8 must not rename them.
-keepclasseswithmembernames class com.wallhub.android.data.downloads.WallHubRust {
    native <methods>;
}
