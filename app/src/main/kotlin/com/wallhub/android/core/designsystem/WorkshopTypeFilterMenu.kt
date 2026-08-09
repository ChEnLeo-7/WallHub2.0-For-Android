package com.wallhub.android.core.designsystem

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterAlt

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.wallhub.android.core.model.WorkshopType

val FilterableWorkshopTypes = setOf(WorkshopType.VIDEO, WorkshopType.SCENE, WorkshopType.WEB)

@Composable
fun WorkshopTypeFilterMenu(
    selectedTypes: Set<WorkshopType>,
    onTypeToggled: (WorkshopType) -> Unit,
    contentDescription: String,
    typeLabel: @Composable (WorkshopType) -> String,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Outlined.FilterAlt,
                contentDescription = contentDescription,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            FilterableWorkshopTypes.forEach { type ->
                DropdownMenuItem(
                    text = { Text(typeLabel(type)) },
                    leadingIcon = {
                        Checkbox(
                            checked = type in selectedTypes,
                            onCheckedChange = null,
                        )
                    },
                    onClick = { onTypeToggled(type) },
                )
            }
        }
    }
}
