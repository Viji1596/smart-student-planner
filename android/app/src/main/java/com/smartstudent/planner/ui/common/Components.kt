@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.smartstudent.planner.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A white rounded card, optionally tappable. */
@Composable
fun WhiteCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = MaterialTheme.colorScheme.surface) {
            content()
        }
    } else {
        Surface(modifier = modifier, shape = shape, color = MaterialTheme.colorScheme.surface) { content() }
    }
}

/** Round white button holding an icon (settings, back, add...). */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(46.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** The small rounded label used for dates and durations: dark or light. */
@Composable
fun Pill(text: String, dark: Boolean = false, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(if (dark) scheme.inverseSurface else scheme.surfaceVariant)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (dark) scheme.inverseOnSurface else scheme.onSurface,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
        )
    }
}

/** A filter pill: dark when selected, white when not (All Tasks / In Progress / ...). */
@Composable
fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) scheme.inverseSurface else scheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) scheme.inverseOnSurface else scheme.onSurface,
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
        )
    }
}

/** A tile with a small icon, label, and a big number (the 2x2 grid on Home). */
@Composable
fun StatTile(
    icon: ImageVector,
    label: String,
    value: String,
    suffix: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
    onClick: (() -> Unit)? = null,
) {
    val shape = MaterialTheme.shapes.large
    val body: @Composable () -> Unit = {
        Column(Modifier.padding(16.dp).fillMaxWidth().height(100.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(label, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, fontSize = 38.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.width(6.dp))
                Text(
                    suffix,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = container) { body() }
    } else {
        Surface(modifier = modifier, shape = shape, color = container) { body() }
    }
}

/** A weekday pill with the date number under it (like the week strip in the design). */
@Composable
fun DayPill(weekday: String, dayNumber: Int, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .width(50.dp)
            .clip(RoundedCornerShape(25.dp))
            .background(scheme.surface.copy(alpha = if (selected) 0.9f else 0.5f))
            .clickable(onClick = onClick)
            .padding(top = 10.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(weekday, fontSize = 12.sp, color = scheme.onBackground.copy(alpha = 0.7f))
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(if (selected) scheme.inverseSurface else scheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                dayNumber.toString(),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (selected) scheme.inverseOnSurface else scheme.onSurface,
            )
        }
    }
}

/** A big screen title, with an optional back button and trailing buttons on the same row. */
@Composable
fun ScreenTitle(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (onBack != null) RoundIconButton(Icons.Default.ArrowBack, "Back", onBack)
            Spacer(Modifier.weight(1f))
            trailing()
        }
        Spacer(Modifier.height(14.dp))
        Text(
            title,
            fontSize = 34.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/** Rounded vertical bars (the "Your Progress" chart). The highlighted bar is solid. */
@Composable
fun PillBarChart(
    values: List<Float>,
    labels: List<String>,
    highlight: Int,
    modifier: Modifier = Modifier,
    chartHeight: androidx.compose.ui.unit.Dp = 170.dp,
) {
    val scheme = MaterialTheme.colorScheme
    val maxValue = (values.maxOrNull() ?: 0f).coerceAtLeast(1f)
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Bottom,
    ) {
        values.forEachIndexed { index, value ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.height(chartHeight).width(38.dp), contentAlignment = Alignment.BottomCenter) {
                    // Even an empty day gets a small stub so the row of bars stays readable.
                    val fraction = (value / maxValue).coerceIn(0.12f, 1f)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(chartHeight * fraction)
                            .clip(RoundedCornerShape(19.dp))
                            .background(
                                if (index == highlight) scheme.primary
                                else scheme.primary.copy(alpha = if (value > 0f) 0.32f else 0.14f)
                            )
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    labels.getOrElse(index) { "" },
                    fontSize = 13.sp,
                    color = scheme.onSurface.copy(alpha = if (index == highlight) 1f else 0.65f),
                    fontWeight = if (index == highlight) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/** Three little bars used as the Progress tab icon (there's no ready-made one in the core icon set). */
@Composable
fun BarsIcon(tint: Color, modifier: Modifier = Modifier) {
    Row(
        modifier.size(24.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.Bottom,
    ) {
        listOf(10.dp, 18.dp, 13.dp).forEach { h ->
            Box(Modifier.width(4.dp).height(h).clip(RoundedCornerShape(2.dp)).background(tint))
        }
    }
}
