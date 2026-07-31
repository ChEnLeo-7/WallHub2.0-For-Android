# Retain runtime metadata consumed by Hilt, Room, Kotlin serialization and JavaSteam.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# JavaSteam resolves generated protobuf/RPC types and callbacks dynamically.
-keep class in.dragonbra.javasteam.protobufs.** { *; }
-keep class in.dragonbra.javasteam.rpc.** { *; }
-keep class in.dragonbra.javasteam.steam.handlers.**.callback.** { *; }

# Navigation route serializers are generated from these annotations.
-if @kotlinx.serialization.Serializable class **
-keep,allowoptimization,allowshrinking class <1>$$serializer { *; }
-keepclassmembers class ** {
    *** Companion;
}
