package com.wallhub.android.core.designsystem

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.wallhub.android.R
import com.wallhub.android.core.model.WorkshopAuthorPlaceholder
import com.wallhub.android.core.model.WorkshopComment
import com.wallhub.android.core.model.WorkshopSummary

fun Context.localizedTitle(summary: WorkshopSummary): String =
    if (summary.isTitlePlaceholder) {
        getString(R.string.workshop_placeholder_title, summary.id)
    } else {
        summary.title
    }

@Composable
fun WorkshopSummary.localizedTitle(): String =
    if (isTitlePlaceholder) {
        stringResource(R.string.workshop_placeholder_title, id)
    } else {
        title
    }

@Composable
fun WorkshopSummary.localizedAuthor(): String =
    when (authorPlaceholder) {
        WorkshopAuthorPlaceholder.NONE -> author
        WorkshopAuthorPlaceholder.USER -> stringResource(R.string.workshop_placeholder_steam_user)
        WorkshopAuthorPlaceholder.USER_WITH_ID ->
            stringResource(R.string.workshop_placeholder_steam_user_with_id, creatorId.orEmpty())
        WorkshopAuthorPlaceholder.CREATOR -> stringResource(R.string.workshop_placeholder_steam_creator)
    }

@Composable
fun WorkshopComment.localizedAuthor(): String =
    if (isAuthorPlaceholder) {
        stringResource(R.string.workshop_placeholder_steam_user)
    } else {
        author
    }
