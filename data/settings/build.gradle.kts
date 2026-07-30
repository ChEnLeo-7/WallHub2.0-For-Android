plugins {
    id("wallhub.android.library")
}

android {
    namespace = "com.wallhub.android.data.settings"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(libs.javax.inject)
}
