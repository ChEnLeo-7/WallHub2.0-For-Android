@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsRootPlugin

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.android.legacy.kapt) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.hilt.android) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.versions)
}

plugins.withType<WasmNodeJsRootPlugin> {
    extensions.configure<WasmNodeJsEnvSpec> {
        download.set(false)
        command.set(providers.environmentVariable("WALLHUB_NODE").orElse("node"))
    }
}

subprojects {
    plugins.withType<BinaryenPlugin> {
        extensions.configure<BinaryenEnvSpec> {
            download.set(false)
            command.set(providers.environmentVariable("WALLHUB_WASM_OPT").orElse("wasm-opt"))
        }
    }
    plugins.withType<WasmNodeJsPlugin> {
        extensions.configure<WasmNodeJsEnvSpec> {
            download.set(false)
            command.set(providers.environmentVariable("WALLHUB_NODE").orElse("node"))
        }
    }

    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        baseline =
            providers
                .gradleProperty("wallhub.detektBaseline")
                .map(::file)
                .getOrElse(file("config/detekt/baseline.xml"))
        parallel = true
    }
    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set("1.5.0")
        android.set(true)
        outputToConsole.set(true)
        ignoreFailures.set(false)
        filter {
            exclude("**/build/**")
            exclude("**/generated/**")
        }
    }
}
