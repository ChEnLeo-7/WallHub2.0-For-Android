package com.wallhub.android.data.steam

import com.wallhub.android.core.model.WorkshopSummary
import io.steamcontent.sdk.WorkshopItemField
import org.junit.Test
import kotlin.test.assertEquals

class SdkWorkshopMapperTest {
    @Test
    fun `summary keeps precise update time from sdk page row`() {
        val updatedAt = 1_712_345_678L
        val fields = LongArray(SdkSummaryFields.ORDINALS.size)
        fields[SdkSummaryFields.ORDINALS.indexOf(WorkshopItemField.ITEM)] = 42L
        fields[SdkSummaryFields.ORDINALS.indexOf(WorkshopItemField.UPDATED_AT)] = updatedAt
        val summary =
            WorkshopSummary(
                id = fields[SdkSummaryFields.ORDINALS.indexOf(WorkshopItemField.ITEM)],
                title = "Workshop item",
                author = "Creator",
                updatedAt = SdkSummaryFields(fields).updatedAtOrNull(),
            )

        assertEquals(updatedAt, summary.updatedAt)
    }
}
