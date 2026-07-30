plugins {
    id("wallhub.android.library")
    alias(libs.plugins.kotlin.kapt)
}

android {
    namespace = "com.wallhub.android.core.database"
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.androidx.datastore.preferences)
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.kotlinx.coroutines.android)

    kapt(libs.androidx.room.compiler)
}
