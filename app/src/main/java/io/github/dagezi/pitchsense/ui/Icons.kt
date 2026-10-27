package io.github.dagezi.pitchsense.ui

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

// material-icons-core lacks these, and the extended set is too large to pull in for two icons.

val StopIcon: ImageVector = materialIcon(name = "Filled.Stop") {
    materialPath {
        moveTo(6f, 6f)
        horizontalLineToRelative(12f)
        verticalLineToRelative(12f)
        horizontalLineTo(6f)
        close()
    }
}

val RecordIcon: ImageVector = materialIcon(name = "Filled.Record") {
    materialPath {
        // Circle of radius 6 centered at (12, 12).
        moveTo(12f, 6f)
        arcToRelative(6f, 6f, 0f, true, true, 0f, 12f)
        arcToRelative(6f, 6f, 0f, true, true, 0f, -12f)
        close()
    }
}
