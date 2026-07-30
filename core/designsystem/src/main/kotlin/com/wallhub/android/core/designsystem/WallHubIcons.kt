package com.wallhub.android.core.designsystem

import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
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
    val Add: ImageVector get() = MaterialIcons.Outlined.Add
    val ArrowDown: ImageVector get() = MaterialIcons.Outlined.ArrowDownward
    val ArrowLeft: ImageVector get() = MaterialIcons.AutoMirrored.Outlined.ArrowBack
    val ArrowUp: ImageVector get() = MaterialIcons.Outlined.ArrowUpward
    val Bell: ImageVector get() = MaterialIcons.Outlined.Notifications
    val Bookmark: ImageVector get() = MaterialIcons.Outlined.BookmarkBorder
    val Check: ImageVector get() = MaterialIcons.Outlined.Check
    val ChevronDown: ImageVector get() = MaterialIcons.Outlined.KeyboardArrowDown
    val ChevronLeft: ImageVector get() = MaterialIcons.AutoMirrored.Outlined.KeyboardArrowLeft
    val ChevronRight: ImageVector get() = MaterialIcons.AutoMirrored.Outlined.KeyboardArrowRight
    val CircleX: ImageVector get() = MaterialIcons.Outlined.Cancel
    val Compass: ImageVector get() = MaterialIcons.Outlined.Explore
    val Copy: ImageVector get() = MaterialIcons.Outlined.ContentCopy
    val Delete: ImageVector get() = MaterialIcons.Outlined.Delete
    val Download: ImageVector get() = MaterialIcons.Outlined.Download
    val DragHandle: ImageVector get() = MaterialIcons.Outlined.DragIndicator
    val Edit: ImageVector get() = MaterialIcons.Outlined.Edit
    val ExternalLink: ImageVector get() = MaterialIcons.AutoMirrored.Outlined.OpenInNew
    val Filter: ImageVector get() = MaterialIcons.Outlined.FilterList
    val FolderOpen: ImageVector get() = MaterialIcons.Outlined.FolderOpen
    val Grid3X3: ImageVector get() = MaterialIcons.Outlined.GridView
    val Heart: ImageVector get() = MaterialIcons.Outlined.FavoriteBorder
    val ImageOff: ImageVector get() = MaterialIcons.Outlined.ImageNotSupported
    val Info: ImageVector get() = MaterialIcons.Outlined.Info
    val Languages: ImageVector get() = MaterialIcons.Outlined.Language
    val List: ImageVector get() = MaterialIcons.AutoMirrored.Outlined.ViewList
    val LockKeyhole: ImageVector get() = MaterialIcons.Outlined.Lock
    val LogOut: ImageVector get() = MaterialIcons.AutoMirrored.Outlined.Logout
    val Maximize2: ImageVector get() = MaterialIcons.Outlined.Fullscreen
    val Minimize2: ImageVector get() = MaterialIcons.Outlined.FullscreenExit
    val Moon: ImageVector get() = MaterialIcons.Outlined.DarkMode
    val MoreVertical: ImageVector get() = MaterialIcons.Outlined.MoreVert
    val Palette: ImageVector get() = MaterialIcons.Outlined.Palette
    val Pause: ImageVector get() = MaterialIcons.Outlined.Pause
    val Play: ImageVector get() = MaterialIcons.Outlined.PlayArrow
    val RotateCw: ImageVector get() = MaterialIcons.Outlined.Refresh
    val Schedule: ImageVector get() = MaterialIcons.Outlined.Schedule
    val Send: ImageVector get() = MaterialIcons.AutoMirrored.Outlined.Send
    val Search: ImageVector get() = MaterialIcons.Outlined.Search
    val Settings: ImageVector get() = MaterialIcons.Outlined.Settings
    val SlidersHorizontal: ImageVector get() = MaterialIcons.Outlined.Tune
    val Smartphone: ImageVector get() = MaterialIcons.Outlined.PhoneAndroid
    val Star: ImageVector get() = MaterialIcons.Outlined.StarBorder
    val MessageCircle: ImageVector get() = MaterialIcons.Outlined.ChatBubbleOutline
    val Trash2: ImageVector get() = MaterialIcons.Outlined.DeleteSweep
    val Upload: ImageVector get() = MaterialIcons.Outlined.FileUpload
    val UserRound: ImageVector get() = MaterialIcons.Outlined.PersonOutline
    val VerticalAlignTop: ImageVector get() = MaterialIcons.Outlined.VerticalAlignTop
    val Visibility: ImageVector get() = MaterialIcons.Outlined.Visibility
    val VisibilityOff: ImageVector get() = MaterialIcons.Outlined.VisibilityOff

    object Outlined {
        val Add: ImageVector get() = WallHubIcons.Add
        val ArrowDownward: ImageVector get() = WallHubIcons.ArrowDown
        val ArrowUpward: ImageVector get() = WallHubIcons.ArrowUp
        val BookmarkBorder: ImageVector get() = WallHubIcons.Bookmark
        val Bookmarks: ImageVector get() = MaterialIcons.Outlined.Bookmarks
        val Cancel: ImageVector get() = WallHubIcons.CircleX
        val Check: ImageVector get() = WallHubIcons.Check
        val ContentCopy: ImageVector get() = WallHubIcons.Copy
        val DarkMode: ImageVector get() = WallHubIcons.Moon
        val Delete: ImageVector get() = WallHubIcons.Delete
        val DeleteSweep: ImageVector get() = WallHubIcons.Trash2
        val Download: ImageVector get() = WallHubIcons.Download
        val DragIndicator: ImageVector get() = WallHubIcons.DragHandle
        val Edit: ImageVector get() = WallHubIcons.Edit
        val Explore: ImageVector get() = WallHubIcons.Compass
        val FavoriteBorder: ImageVector get() = WallHubIcons.Heart
        val FileUpload: ImageVector get() = WallHubIcons.Upload
        val FilterList: ImageVector get() = WallHubIcons.Filter
        val FolderOpen: ImageVector get() = WallHubIcons.FolderOpen
        val Fullscreen: ImageVector get() = WallHubIcons.Maximize2
        val FullscreenExit: ImageVector get() = WallHubIcons.Minimize2
        val GridView: ImageVector get() = WallHubIcons.Grid3X3
        val ImageNotSupported: ImageVector get() = WallHubIcons.ImageOff
        val Info: ImageVector get() = WallHubIcons.Info
        val KeyboardArrowDown: ImageVector get() = WallHubIcons.ChevronDown
        val Language: ImageVector get() = WallHubIcons.Languages
        val Lock: ImageVector get() = WallHubIcons.LockKeyhole
        val MoreVert: ImageVector get() = WallHubIcons.MoreVertical
        val Notifications: ImageVector get() = WallHubIcons.Bell
        val OpenInNew: ImageVector get() = WallHubIcons.ExternalLink
        val Palette: ImageVector get() = WallHubIcons.Palette
        val Pause: ImageVector get() = WallHubIcons.Pause
        val PersonOutline: ImageVector get() = WallHubIcons.UserRound
        val PhoneAndroid: ImageVector get() = WallHubIcons.Smartphone
        val PlayArrow: ImageVector get() = WallHubIcons.Play
        val Refresh: ImageVector get() = WallHubIcons.RotateCw
        val Schedule: ImageVector get() = WallHubIcons.Schedule
        val Send: ImageVector get() = WallHubIcons.Send
        val Search: ImageVector get() = WallHubIcons.Search
        val Settings: ImageVector get() = WallHubIcons.Settings
        val StarBorder: ImageVector get() = WallHubIcons.Star
        val ChatBubbleOutline: ImageVector get() = WallHubIcons.MessageCircle
        val Tune: ImageVector get() = WallHubIcons.SlidersHorizontal
        val VerticalAlignTop: ImageVector get() = WallHubIcons.VerticalAlignTop
        val ViewList: ImageVector get() = WallHubIcons.List
        val Visibility: ImageVector get() = WallHubIcons.Visibility
        val VisibilityOff: ImageVector get() = WallHubIcons.VisibilityOff
    }

    object Filled {
        val Bookmarks: ImageVector get() = MaterialIcons.Filled.Bookmarks
        val Download: ImageVector get() = MaterialIcons.Filled.Download
        val Explore: ImageVector get() = MaterialIcons.Filled.Explore
        val FolderOpen: ImageVector get() = MaterialSymbolFolderOpenFilled
        val Settings: ImageVector get() = MaterialIcons.Filled.Settings
    }

    object AutoMirrored {
        object Outlined {
            val ArrowBack: ImageVector get() = WallHubIcons.ArrowLeft
            val KeyboardArrowRight: ImageVector get() = WallHubIcons.ChevronRight
            val Logout: ImageVector get() = WallHubIcons.LogOut
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
