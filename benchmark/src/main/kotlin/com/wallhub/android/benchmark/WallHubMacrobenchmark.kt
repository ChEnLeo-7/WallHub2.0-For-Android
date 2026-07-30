package com.wallhub.android.benchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WallHubMacrobenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartup() =
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(StartupTimingMetric()),
            iterations = 5,
            startupMode = StartupMode.COLD,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait()
        }

    @Test
    fun homeScroll() =
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric()),
            iterations = 5,
            startupMode = StartupMode.WARM,
            setupBlock = {
                pressHome()
                startActivityAndWait()
            },
        ) {
            repeat(3) {
                val scrollable =
                    device.wait(
                        Until.findObject(By.scrollable(true)),
                        UI_TIMEOUT_MILLIS,
                    ) ?: return@measureRepeated
                try {
                    scrollable.fling(Direction.DOWN)
                } catch (_: StaleObjectException) {
                    // The result grid can recompose while UiAutomator holds the prior node.
                }
                device.waitForIdle()
            }
        }

    private fun MacrobenchmarkScope.pressHome() {
        device.pressHome()
    }

    private companion object {
        const val PACKAGE_NAME = "com.wallhub.android"
        const val UI_TIMEOUT_MILLIS = 5_000L
    }
}
