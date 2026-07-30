plugins {
    id("wallhub.android.library")
}

android {
    namespace = "com.wallhub.android.data.diagnostics"
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.hilt.android)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
