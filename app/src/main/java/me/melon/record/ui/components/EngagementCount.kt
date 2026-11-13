package me.melon.record.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun RowScope.EngagementCount(
    count: Long,
    icon: ImageVector,
    color: Color = MaterialTheme.colorScheme.outline,
    iconSize: Dp = 14.dp,
    spacing: Dp = 4.dp,
    textStyle: TextStyle = MaterialTheme.typography.labelSmall,
    label: String
) {
    Row(
        modifier = Modifier.clearAndSetSemantics { contentDescription = "$label $count" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(iconSize)
        )
        Spacer(modifier = Modifier.width(spacing))
        Text(text = count.toString(), style = textStyle, color = color)
    }
}
