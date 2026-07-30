import com.google.protobuf.gradle.*

plugins {
    id("wallhub.android.library")
    alias(libs.plugins.protobuf)
}

android {
    namespace = "com.wallhub.android.data.steam"
}

val protobufVersion =
    libs.versions.protobuf.java
        .get()

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                create("java")
            }
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":data:steamaccess"))
    implementation(libs.hilt.android)
    implementation(libs.javasteam) {
        exclude(group = "com.squareup.okhttp3", module = "okhttp-jvm")
    }
    implementation(libs.okhttp.android)
    implementation(libs.protobuf.java)
    implementation(libs.spongycastle.prov)
    implementation(libs.xz)
    implementation("com.github.luben:zstd-jni:${libs.versions.zstd.get()}@aar")
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.jdk8)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
