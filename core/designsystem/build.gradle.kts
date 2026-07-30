plugins {
    id("wallhub.android.library")
    id("wallhub.android.compose")
    alias(libs.plugins.paparazzi)
}

android {
    namespace = "com.wallhub.android.core.designsystem"
}

dependencies {
    implementation(project(":core:model"))
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.material.icons.extended)
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.tooling.preview)
    api(libs.androidx.compose.runtime.saveable)
    api(libs.haze)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.material)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
