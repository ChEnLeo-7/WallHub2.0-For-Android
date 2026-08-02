package com.wallhub.android.core.designsystem

import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VerticalAlignTop
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons as MaterialIcons

/** Official Material Design 3 icon aliases used throughout WallHub. */
object WallHubIcons {
    object Outlined {
        val Add: ImageVector get() = MaterialIcons.Outlined.Add
        val ArrowDownward: ImageVector get() = MaterialIcons.Outlined.ArrowDownward
        val ArrowUpward: ImageVector get() = MaterialIcons.Outlined.ArrowUpward
        val BookmarkBorder: ImageVector get() = MaterialIcons.Outlined.BookmarkBorder
        val Bookmarks: ImageVector get() = MaterialIcons.Outlined.Bookmarks
        val Cancel: ImageVector get() = MaterialIcons.Outlined.Cancel
        val Check: ImageVector get() = MaterialIcons.Outlined.Check
        val ContentCopy: ImageVector get() = MaterialIcons.Outlined.ContentCopy
        val DarkMode: ImageVector get() = MaterialIcons.Outlined.DarkMode
        val Delete: ImageVector get() = MaterialIcons.Outlined.Delete
        val DeleteSweep: ImageVector get() = MaterialIcons.Outlined.DeleteSweep
        val Download: ImageVector get() = MaterialIcons.Outlined.Download
        val DragIndicator: ImageVector get() = MaterialIcons.Outlined.DragIndicator
        val Edit: ImageVector get() = MaterialIcons.Outlined.Edit
        val Explore: ImageVector get() = MaterialIcons.Outlined.Explore
        val FavoriteBorder: ImageVector get() = MaterialIcons.Outlined.FavoriteBorder
        val FileUpload: ImageVector get() = MaterialIcons.Outlined.FileUpload
        val FilterList: ImageVector get() = MaterialIcons.Outlined.FilterList
        val FolderOpen: ImageVector get() = MaterialIcons.Outlined.FolderOpen
        val Fullscreen: ImageVector get() = MaterialIcons.Outlined.Fullscreen
        val FullscreenExit: ImageVector get() = MaterialIcons.Outlined.FullscreenExit
        val GridView: ImageVector get() = MaterialIcons.Outlined.GridView
        val ImageNotSupported: ImageVector get() = MaterialIcons.Outlined.ImageNotSupported
        val Info: ImageVector get() = MaterialIcons.Outlined.Info
        val KeyboardArrowDown: ImageVector get() = MaterialIcons.Outlined.KeyboardArrowDown
        val Language: ImageVector get() = MaterialIcons.Outlined.Language
        val Lock: ImageVector get() = MaterialIcons.Outlined.Lock
        val MoreVert: ImageVector get() = MaterialIcons.Outlined.MoreVert
        val Notifications: ImageVector get() = MaterialIcons.Outlined.Notifications
        val OpenInNew: ImageVector get() = MaterialIcons.AutoMirrored.Outlined.OpenInNew
        val Palette: ImageVector get() = MaterialIcons.Outlined.Palette
        val Pause: ImageVector get() = MaterialIcons.Outlined.Pause
        val PersonOutline: ImageVector get() = MaterialIcons.Outlined.PersonOutline
        val PhoneAndroid: ImageVector get() = MaterialIcons.Outlined.PhoneAndroid
        val PlayArrow: ImageVector get() = MaterialIcons.Outlined.PlayArrow
        val Refresh: ImageVector get() = MaterialIcons.Outlined.Refresh
        val Schedule: ImageVector get() = MaterialIcons.Outlined.Schedule
        val Send: ImageVector get() = MaterialIcons.AutoMirrored.Outlined.Send
        val Search: ImageVector get() = MaterialIcons.Outlined.Search
        val Settings: ImageVector get() = MaterialIcons.Outlined.Settings
        val StarBorder: ImageVector get() = MaterialIcons.Outlined.StarBorder
        val ChatBubbleOutline: ImageVector get() = MaterialIcons.Outlined.ChatBubbleOutline
        val Tune: ImageVector get() = MaterialIcons.Outlined.Tune
        val VerticalAlignTop: ImageVector get() = MaterialIcons.Outlined.VerticalAlignTop
        val ViewList: ImageVector get() = MaterialIcons.AutoMirrored.Outlined.ViewList
        val Visibility: ImageVector get() = MaterialIcons.Outlined.Visibility
        val VisibilityOff: ImageVector get() = MaterialIcons.Outlined.VisibilityOff
    }

    object Filled {
        val Bookmarks: ImageVector get() = MaterialIcons.Filled.Bookmarks
        val Download: ImageVector get() = MaterialIcons.Filled.Download
        val Explore: ImageVector get() = MaterialIcons.Filled.Explore
        val Favorite: ImageVector get() = MaterialIcons.Filled.Favorite
        val FolderOpen: ImageVector get() = MaterialSymbolFolderOpenFilled
        val Settings: ImageVector get() = MaterialIcons.Filled.Settings
    }

    object AutoMirrored {
        object Filled {
            val Send: ImageVector get() = MaterialIcons.AutoMirrored.Filled.Send
        }

        object Outlined {
            val ArrowBack: ImageVector get() = MaterialIcons.AutoMirrored.Outlined.ArrowBack
            val KeyboardArrowRight: ImageVector get() = MaterialIcons.AutoMirrored.Outlined.KeyboardArrowRight
            val Logout: ImageVector get() = MaterialIcons.AutoMirrored.Outlined.Logout
        }
    }
}

// Material Symbols "folder_open", FILL=1, from fonts.google.com/icons.
private val MaterialSymbolFolderOpenFilled: ImageVector by lazy {
    ImageVector
        .Builder(
            name = "MaterialSymbolFolderOpenFilled",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero,
            ) {
                moveTo(4f, 20f)
                quadToRelative(-0.825f, 0f, -1.4125f, -0.5875f)
                quadTo(2f, 18.825f, 2f, 18f)
                verticalLineTo(6f)
                quadToRelative(0f, -0.825f, 0.5875f, -1.4125f)
                quadTo(3.175f, 4f, 4f, 4f)
                horizontalLineToRelative(6f)
                lineToRelative(2f, 2f)
                horizontalLineToRelative(8f)
                quadToRelative(0.825f, 0f, 1.4125f, 0.5875f)
                quadTo(22f, 7.175f, 22f, 8f)
                horizontalLineTo(4f)
                verticalLineToRelative(10f)
                lineToRelative(2.4f, -8f)
                horizontalLineToRelative(17.1f)
                lineTo(20.925f, 18.575f)
                quadToRelative(-0.2f, 0.65f, -0.7375f, 1.0375f)
                quadTo(19.65f, 20f, 19f, 20f)
                horizontalLineTo(4f)
                close()
            }
        }.build()
}
