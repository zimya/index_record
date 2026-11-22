package com.melonhu.indexrecord.ui.stats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.melonhu.indexrecord.R

@Composable
private fun animateCount(
    targetValue: Long,
    hasAnimated: Boolean,
    onAnimationEnd: () -> Unit,
    durationMillis: Int = 850
): Long {
    val animatable = remember { Animatable(if (hasAnimated) targetValue.toFloat() else 0f) }

    LaunchedEffect(targetValue, hasAnimated) {
        if (!hasAnimated) {
            if (targetValue > 0L) {
                animatable.animateTo(
                    targetValue = targetValue.toFloat(),
                    animationSpec = tween(
                        durationMillis = durationMillis,
                        easing = FastOutSlowInEasing
                    )
                )
                onAnimationEnd()
            } else {
                animatable.snapTo(0f)
            }
        } else {
            if (animatable.value != targetValue.toFloat()) {
                animatable.snapTo(targetValue.toFloat())
            }
        }
    }
    return animatable.value.toLong()
}

@Composable
internal fun TodayReadingCard(
    todayWords: Long,
    todayRecords: Int,
    hasAnimated: Boolean,
    onAnimationEnd: () -> Unit
) {
    val animatedWords = animateCount(
        targetValue = todayWords,
        hasAnimated = hasAnimated,
        onAnimationEnd = onAnimationEnd
    )
    val animatedRecords = animateCount(
        targetValue = todayRecords.toLong(),
        hasAnimated = hasAnimated,
        onAnimationEnd = onAnimationEnd
    ).toInt()

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        modifier = Modifier.size(32.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                                contentDescription = stringResource(R.string.stats_today_reading),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.stats_today_reading),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                ) {
                    Text(
                        text = if (todayRecords > 0) stringResource(R.string.stats_checked_in) else stringResource(R.string.stats_pending_read),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (todayRecords > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.stats_word_count),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = formatNumber(animatedWords),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = stringResource(R.string.stats_unit_words),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                            modifier = Modifier.padding(bottom = 4.dp, start = 2.dp)
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = stringResource(R.string.stats_record_count),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "$animatedRecords",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = stringResource(R.string.stats_unit_records),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                            modifier = Modifier.padding(bottom = 3.dp, start = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun MetricsRow(
    totalRecords: Long,
    totalWords: Long,
    currentStreak: Int,
    maxStreak: Int,
    hasAnimated: Boolean,
    onAnimationEnd: () -> Unit
) {
    val animRecords = animateCount(
        targetValue = totalRecords,
        hasAnimated = hasAnimated,
        onAnimationEnd = onAnimationEnd
    )
    val animWords = animateCount(
        targetValue = totalWords,
        hasAnimated = hasAnimated,
        onAnimationEnd = onAnimationEnd
    )
    val animCurrentStreak = animateCount(
        targetValue = currentStreak.toLong(),
        hasAnimated = hasAnimated,
        onAnimationEnd = onAnimationEnd
    ).toInt()
    val animMaxStreak = animateCount(
        targetValue = maxStreak.toLong(),
        hasAnimated = hasAnimated,
        onAnimationEnd = onAnimationEnd
    ).toInt()

    val itemUnit = stringResource(R.string.stats_unit_records).trim()
    val wordUnit = stringResource(R.string.stats_unit_words).trim()
    val dayUnit = stringResource(R.string.stats_unit_days).trim()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CompactMetricCard(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.stats_total_records),
                value = formatNumber(animRecords),
                unit = itemUnit
            )
            CompactMetricCard(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.stats_total_words),
                value = formatCompactNumber(animWords),
                unit = wordUnit
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CompactMetricCard(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.stats_current_streak),
                value = "$animCurrentStreak",
                unit = dayUnit
            )
            CompactMetricCard(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.stats_longest_streak),
                value = "$animMaxStreak",
                unit = dayUnit
            )
        }
    }
}

@Composable
private fun CompactMetricCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    unit: String
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = unit,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(bottom = 2.dp, start = 3.dp)
                )
            }
        }
    }
}
