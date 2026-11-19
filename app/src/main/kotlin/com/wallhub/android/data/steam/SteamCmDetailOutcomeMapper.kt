package com.wallhub.android.data.steam

import com.wallhub.android.core.model.CmPublicDetailResult
import com.wallhub.android.core.model.WorkshopDetail
import io.steamcontent.sdk.WorkshopDetailsStatus

internal fun mapCmDetailOutcome(
    status: Int,
    serviceResult: Int?,
    detail: WorkshopDetail?,
): CmPublicDetailResult =
    when {
        status == WorkshopDetailsStatus.OK && detail != null -> CmPublicDetailResult.Available(detail)
        status == WorkshopDetailsStatus.NOT_FOUND -> CmPublicDetailResult.NotFound
        else -> CmPublicDetailResult.Unavailable("CM status=$status service=$serviceResult")
    }
