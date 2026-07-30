plugins {
    id("wallhub.android.library")
}

android {
    namespace = "com.wallhub.android.core.model"
}

dependencies {
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
