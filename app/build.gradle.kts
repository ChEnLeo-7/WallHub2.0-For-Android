plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
}

val releaseStoreFile = providers.environmentVariable("WALLHUB_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("WALLHUB_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("WALLHUB_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("WALLHUB_RELEASE_KEY_PASSWORD").orNull
val releaseSigningValues = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
)
val hasReleaseSigning = releaseSigningValues.all { !it.isNullOrBlank() }

check(releaseSigningValues.all { it.isNullOrBlank() } || hasReleaseSigning) {
    "Configure all WALLHUB_RELEASE_* signing variables or none of them."
}

android {
    namespace = "com.wallhub.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wallhub.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 35
        versionName = "0.8.25"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    if (hasReleaseSigning) {
        signingConfigs.create("releaseSigning") {
            storeFile = file(requireNotNull(releaseStoreFile))
            storePassword = requireNotNull(releaseStorePassword)
            keyAlias = requireNotNull(releaseKeyAlias)
            keyPassword = requireNotNull(releaseKeyPassword)
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("releaseSigning")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:database"))
    implementation(project(":data:settings"))
    implementation(project(":data:steamaccess"))
    implementation(project(":data:steam"))
    implementation(project(":data:workshop"))
    implementation(project(":data:downloads"))
    implementation(project(":data:diagnostics"))
    implementation(project(":feature:home"))
    implementation(project(":feature:detail"))
    implementation(project(":feature:downloads"))
    implementation(project(":feature:library"))
    implementation(project(":feature:local"))
    implementation(project(":feature:settings"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.material)
    implementation(libs.hilt.android)
    implementation(libs.coil)
    implementation(libs.coil.gif)

    kapt(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
