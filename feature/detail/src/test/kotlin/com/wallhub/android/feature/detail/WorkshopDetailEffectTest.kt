package com.wallhub.android.feature.detail

import com.wallhub.android.core.model.ExportFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class WorkshopDetailEffectTest {
    @Test
    fun `download and conversion requests resolve permission first`() {
        assertEquals(
            WorkshopDetailPendingOperation.Download,
            assertIs<WorkshopDetailEffect.ResolveLegacyStoragePermission>(
                WorkshopDetailAction
                    .RequestOperation(
                        WorkshopDetailPendingOperation.Download,
                    ).immediateEffect(),
            ).operation,
        )
        assertEquals(
            ExportFormat.MPKG,
            assertIs<WorkshopDetailPendingOperation.ConvertExisting>(
                assertIs<WorkshopDetailEffect.ResolveLegacyStoragePermission>(
                    WorkshopDetailAction
                        .RequestOperation(
                            WorkshopDetailPendingOperation.ConvertExisting(ExportFormat.MPKG),
                        ).immediateEffect(),
                ).operation,
            ).format,
        )
    }

    @Test
    fun `navigation copy and external actions preserve payloads`() {
        assertIs<WorkshopDetailEffect.Back>(WorkshopDetailAction.Back.immediateEffect())
        assertEquals(
            "creator",
            assertIs<WorkshopDetailEffect.SearchAuthor>(
                WorkshopDetailAction.SearchAuthor("creator").immediateEffect(),
            ).author,
        )
        assertEquals(
            "copied",
            assertIs<WorkshopDetailEffect.CopyText>(
                WorkshopDetailAction.CopyText("value", "copied").immediateEffect(),
            ).message,
        )
        assertEquals(
            42L,
            assertIs<WorkshopDetailEffect.OpenSteam>(
                WorkshopDetailAction.OpenSteam(42L).immediateEffect(),
            ).workshopId,
        )
    }

    @Test
    fun `permission result remains a view model stateful action`() {
        assertNull(
            WorkshopDetailAction
                .LegacyStoragePermissionResult(
                    WorkshopDetailPendingOperation.Download,
                    granted = false,
                ).immediateEffect(),
        )
    }
}
