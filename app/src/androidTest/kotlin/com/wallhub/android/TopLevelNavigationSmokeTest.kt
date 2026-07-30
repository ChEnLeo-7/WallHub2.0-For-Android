package com.wallhub.android

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/** Verifies the device-facing top-level navigation and the Settings secondary back stack. */
@RunWith(AndroidJUnit4::class)
class TopLevelNavigationSmokeTest {
    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun topLevelNavigationAndSteamBackStackAreReachable() {
        ActivityScenario.launch(MainActivity::class.java).use {
            assertVisible("发现|Discover")

            click("管理|Management")
            assertVisible("暂无下载任务|No download tasks")

            click("设置|Settings")
            assertVisible("基本设置|General settings")

            click("Steam")
            assertVisible("Steam 账户|Steam account")

            device.pressBack()
            assertVisible("基本设置|General settings")
        }
    }

    @Test
    fun topLevelDestinationSurvivesActivityRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertVisible("发现|Discover")

            click("管理|Management")
            assertVisible("暂无下载任务|No download tasks")

            scenario.recreate()

            assertVisible("暂无下载任务|No download tasks")
        }
    }

    @Test
    fun topLevelDestinationSurvivesProcessRestart() {
        ActivityScenario.launch(MainActivity::class.java).use {
            assertVisible("发现|Discover")

            click("管理|Management")
            assertVisible("暂无下载任务|No download tasks")

            device.executeShellCommand("am kill ${targetPackageName()}")
            device.executeShellCommand("am start -n ${targetPackageName()}/.MainActivity")

            assertVisible("暂无下载任务|No download tasks")
        }
    }

    private fun click(textPattern: String) {
        val target =
            device.wait(
                Until.findObject(By.text(Pattern.compile(textPattern))),
                UI_TIMEOUT_MILLIS,
            )
        checkNotNull(target) { "Timed out waiting for $textPattern" }.click()
    }

    private fun assertVisible(textPattern: String) {
        assertTrue(
            "Timed out waiting for $textPattern",
            device.wait(Until.hasObject(By.text(Pattern.compile(textPattern))), UI_TIMEOUT_MILLIS),
        )
    }

    private fun targetPackageName(): String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .packageName

    private companion object {
        const val UI_TIMEOUT_MILLIS = 8_000L
    }
}
