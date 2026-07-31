import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate

plugins {
    id("wallhub.android.application")
    id("wallhub.android.compose")
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.protobuf)
}

val releaseStoreFile = providers.environmentVariable("WALLHUB_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("WALLHUB_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("WALLHUB_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("WALLHUB_RELEASE_KEY_PASSWORD").orNull
val releaseSigningValues =
    listOf(
        releaseStoreFile,
        releaseStorePassword,
        releaseKeyAlias,
        releaseKeyPassword,
    )
val hasReleaseSigning = releaseSigningValues.all { !it.isNullOrBlank() }
val publishAbiApks = providers.gradleProperty("wallhub.publishAbiApks").orNull == "true"
val requireReleaseSigning = providers.gradleProperty("wallhub.requireReleaseSigning").orNull == "true"

fun loadReleaseCertificate(): X509Certificate {
    val keyStoreFile = file(requireNotNull(releaseStoreFile))
    check(keyStoreFile.isFile) { "Release keystore does not exist: $keyStoreFile" }
    val failures = mutableListOf<Exception>()
    listOf(KeyStore.getDefaultType(), "JKS", "PKCS12")
        .distinct()
        .forEach { storeType ->
            try {
                val keyStore = KeyStore.getInstance(storeType)
                keyStoreFile.inputStream().use { input ->
                    keyStore.load(input, requireNotNull(releaseStorePassword).toCharArray())
                }
                val certificate = keyStore.getCertificate(requireNotNull(releaseKeyAlias))
                if (certificate is X509Certificate) return certificate
            } catch (exception: Exception) {
                failures += exception
            }
        }
    error(
        "Cannot read an X.509 Release certificate for alias $releaseKeyAlias: " +
            failures.lastOrNull()?.message,
    )
}

check(releaseSigningValues.all { it.isNullOrBlank() } || hasReleaseSigning) {
    "Configure all WALLHUB_RELEASE_* signing variables or none of them."
}
check(!requireReleaseSigning || hasReleaseSigning) {
    "A signed Release was requested, but WALLHUB_RELEASE_* signing variables are incomplete."
}
if (hasReleaseSigning) {
    val releaseCertificate = loadReleaseCertificate()
    releaseCertificate.checkValidity()
    val releaseSubject = releaseCertificate.subjectX500Principal.name
    val releaseCertificateSha256 =
        MessageDigest
            .getInstance("SHA-256")
            .digest(releaseCertificate.encoded)
            .joinToString("") { byte -> "%02X".format(byte) }
    val expectedReleaseCertificateSha256 =
        rootProject
            .file("config/release-signing-certificate.sha256")
            .readText()
            .trim()
            .uppercase()
    check(expectedReleaseCertificateSha256.matches(Regex("[0-9A-F]{64}"))) {
        "Pinned Release certificate SHA-256 is invalid."
    }
    check(releaseCertificateSha256 == expectedReleaseCertificateSha256) {
        "Release signing certificate does not match the pinned published identity: " +
            releaseCertificateSha256
    }
    if (releaseSubject.contains("CN=Android Debug", ignoreCase = true)) {
        logger.warn(
            "Using the pinned legacy Release certificate with Android Debug DN; " +
                "rotate it only with an Android signing lineage.",
        )
    }
}

android {
    namespace = "com.wallhub.android"
    defaultConfig {
        applicationId = "com.wallhub.android"
        targetSdk = 35
        versionCode = 35
        versionName = "0.8.25"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        javaCompileOptions {
            annotationProcessorOptions {
                arguments["room.schemaLocation"] = "$projectDir/schemas"
                arguments["room.incremental"] = "true"
            }
        }
    }

    splits {
        abi {
            isEnable = publishAbiApks
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = publishAbiApks
        }
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
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig =
                if (hasReleaseSigning) {
                    signingConfigs.getByName("releaseSigning")
                } else {
                    null
                }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    }
}

kotlin {
    sourceSets.named("test") {
        kotlin.setSrcDirs(listOf("src/test/kotlin"))
        kotlin.include(
            "com/wallhub/android/testutil/MainDispatcherRule.kt",
            "com/wallhub/android/data/settings/SteamApiCredentialRepositoryTest.kt",
            "com/wallhub/android/data/steamaccess/SteamAccessRoutesTest.kt",
            "com/wallhub/android/data/update/GitHubReleaseParserTest.kt",
            "com/wallhub/android/feature/local/LocalWallpaperViewModelTest.kt",
        )
    }
    sourceSets.named("androidTest") {
        kotlin.setSrcDirs(listOf("src/androidTest/kotlin"))
        kotlin.include(
            "com/wallhub/android/core/database/FormalTaskDatabaseMigrationTest.kt",
        )
    }
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
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.navigation)
    implementation(libs.androidx.compose.runtime.saveable)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.coil)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.haze)
    implementation(libs.hilt.android)
    implementation(libs.javasteam) {
        exclude(group = "com.squareup.okhttp3", module = "okhttp-jvm")
    }
    implementation(libs.javax.inject)
    implementation(libs.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.jdk8)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.lz4.java)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.material)
    implementation(libs.okhttp.android)
    implementation(libs.protobuf.java)
    implementation(libs.spongycastle.prov)
    implementation(libs.xz)
    implementation("com.github.luben:zstd-jni:${libs.versions.zstd.get()}@aar")

    kapt(libs.hilt.compiler)
    kapt(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)

    debugImplementation(libs.androidx.compose.ui.tooling)
}

val lintSourceWarningBudget = 43
val dependencyUpgradeLintIssues =
    setOf(
        "AndroidGradlePluginVersion",
        "GradleDependency",
        "KaptUsageInsteadOfKsp",
        "OldTargetApi",
        "UseTomlInstead",
    )

tasks.register("verifyLintSourceWarningBudget") {
    group = "verification"
    description = "Fails when main-source Lint warnings exceed the reviewed budget."
    dependsOn("lintDebug")

    val reportFile = layout.buildDirectory.file("reports/lint-results-debug.xml")
    inputs.file(reportFile)
    doLast {
        val report = reportFile.get().asFile
        check(report.isFile) { "Missing Lint XML report: $report" }
        val document =
            javax.xml.parsers.DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(report)
        val issues = document.getElementsByTagName("issue")
        val warningCount =
            (0 until issues.length).count { index ->
                val issue = issues.item(index) as org.w3c.dom.Element
                if (issue.getAttribute("severity") != "Warning") return@count false
                if (issue.getAttribute("id") in dependencyUpgradeLintIssues) return@count false
                val locations = issue.getElementsByTagName("location")
                (0 until locations.length).any { locationIndex ->
                    val location = locations.item(locationIndex) as org.w3c.dom.Element
                    location
                        .getAttribute("file")
                        .replace('\\', '/')
                        .contains("/src/main/")
                }
            }
        check(warningCount <= lintSourceWarningBudget) {
            "Main-source Lint warnings increased to $warningCount (budget: $lintSourceWarningBudget)."
        }
        logger.lifecycle("Main-source Lint warnings: $warningCount/$lintSourceWarningBudget")
    }
}
