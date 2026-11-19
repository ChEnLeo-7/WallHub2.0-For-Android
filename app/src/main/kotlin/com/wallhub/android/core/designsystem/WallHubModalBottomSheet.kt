package com.wallhub.android.core.designsystem

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Thin Material 3 modal-bottom-sheet entry point. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallHubModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState? = null,
    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    content: @Composable ColumnScope.() -> Unit,
) {
    val style = wallHubStyle()
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState ?: rememberModalBottomSheetState(),
        sheetMaxWidth = sheetMaxWidth,
        shape = style.shapes.sheet,
        containerColor = style.surfaces.sheet,
        tonalElevation = 0.dp,
        content = content,
    )
}
