package com.melonhu.indexrecord.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import com.melonhu.indexrecord.data.PlatformCatalog
import com.melonhu.indexrecord.viewmodel.LsposedStatus

@Composable
fun LsposedStatusCard(
    status: LsposedStatus,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val isActive = status is LsposedStatus.Active

    val containerColor = when (status) {
        is LsposedStatus.Active -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        is LsposedStatus.Inactive -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
        is LsposedStatus.Checking -> MaterialTheme.colorScheme.surfaceVariant
    }

    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "arrowRotation"
    )

    val cardShape = RoundedCornerShape(16.dp)

    Card(
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        onClick = { if (isActive) expanded = !expanded },
        enabled = isActive,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = when (status) {
                            is LsposedStatus.Active -> Icons.Default.CheckCircle
                            is LsposedStatus.Inactive -> Icons.Default.Cancel
                            is LsposedStatus.Checking -> Icons.Default.HourglassEmpty
                        },
                        contentDescription = null,
                        tint = when (status) {
                            is LsposedStatus.Active -> MaterialTheme.colorScheme.primary
                            is LsposedStatus.Inactive -> MaterialTheme.colorScheme.error
                            is LsposedStatus.Checking -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        val title = when (status) {
                            is LsposedStatus.Active -> "${status.frameworkName} 已激活"
                            is LsposedStatus.Inactive -> "LSPosed 模块未激活"
                            is LsposedStatus.Checking -> "正在检测模块状态..."
                        }
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium
                        )
                        val subtitle = when (status) {
                            is LsposedStatus.Active -> {
                                val versionStr = status.frameworkVersion?.let { " ($it)" } ?: ""
                                "已激活 ${status.activeScopes.size} 个作用域$versionStr"
                            }
                            is LsposedStatus.Inactive -> "请在 LSPosed 管理器中启用模块并勾选目标应用"
                            is LsposedStatus.Checking -> "正在连接框架服务..."
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }

                if (isActive) {
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "收起作用域" else "展开作用域",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.rotate(arrowRotation)
                    )
                }
            }

            if (isActive) {
                val activeScopes = (status as LsposedStatus.Active).activeScopes.toSet()
                // 支持的预设包列表 + 任何用户勾选了但不在默认列表中的额外包
                val allDisplayPackages = (PlatformCatalog.hostPackages + activeScopes).distinct()

                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(modifier = Modifier.padding(top = 16.dp)) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        allDisplayPackages.forEach { pkg ->
                            val isChecked = activeScopes.contains(pkg)
                            val appName = PlatformCatalog.packageLabel(pkg) ?: pkg
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 5.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(
                                        text = appName,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = pkg,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isChecked) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    }
                                ) {
                                    Text(
                                        text = if (isChecked) "已激活" else "未激活",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isChecked) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outline
                                        },
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
