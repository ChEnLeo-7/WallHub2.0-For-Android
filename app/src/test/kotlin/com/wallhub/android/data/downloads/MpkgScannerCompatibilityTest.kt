package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.WorkshopType
import com.wallhub.prototype.mpkg.SCENE_MPKG_MAGIC
import com.wallhub.prototype.mpkg.VIDEO_MPKG_MAGIC
import org.junit.Assert.assertEquals
import org.junit.Test

class MpkgScannerCompatibilityTest {
    @Test
    fun `scanner recognizes scene magic emitted by MPKG writer contract`() {
        assertEquals(WorkshopType.SCENE, workshopTypeForMpkgMagic(SCENE_MPKG_MAGIC))
    }

    @Test
    fun `scanner recognizes video magic emitted by MPKG writer contract`() {
        assertEquals(WorkshopType.VIDEO, workshopTypeForMpkgMagic(VIDEO_MPKG_MAGIC))
    }
}
