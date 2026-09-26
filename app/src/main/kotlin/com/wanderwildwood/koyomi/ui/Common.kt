package com.wanderwildwood.koyomi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.switcher.SwitchMMD
import com.mudita.mmd.components.text.TextMMD
import kotlinx.coroutines.delay

@Composable
fun BarButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(48.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** A row that says what it is and what it is set to, and does something when pressed. */
@Composable
fun SettingRow(title: String, value: String?, onClick: (() -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(vertical = 14.dp),
    ) {
        TextMMD(text = title, style = MaterialTheme.typography.bodyMedium)
        if (value != null) TextMMD(text = value, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    note: String? = null,
    compact: Boolean = false,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = if (compact) 6.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            TextMMD(text = title, style = MaterialTheme.typography.bodyMedium)
            if (note != null) TextMMD(text = note, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.width(12.dp))
        SwitchMMD(checked = checked, onCheckedChange = null)
    }
}

/**
 * A row that asks once, in its own face: the first press arms it and changes what it says,
 * the second does it. It disarms itself after four seconds, so a stray press does not leave
 * a live trigger for whoever picks the phone up next.
 */
@Composable
fun ArmedRow(label: String, armedLabel: String, onConfirmed: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (!armed) return@LaunchedEffect
        delay(4000)
        armed = false
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (armed) {
                    armed = false
                    onConfirmed()
                } else {
                    armed = true
                }
            }
            .padding(vertical = 14.dp),
    ) {
        TextMMD(
            text = if (armed) armedLabel else label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (armed) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/**
 * A swipe that turns a page and stops: one step per gesture, however long the drag. The
 * panel redraws in full, so nothing follows the finger.
 */
fun Modifier.swipePages(key: Any?, onPrevious: () -> Unit, onNext: () -> Unit): Modifier = composedSwipe(key, onPrevious, onNext, horizontal = true)

fun Modifier.swipeVertical(key: Any?, onUp: () -> Unit, onDown: () -> Unit): Modifier = composedSwipe(key, onUp, onDown, horizontal = false)

private fun Modifier.composedSwipe(key: Any?, back: () -> Unit, forward: () -> Unit, horizontal: Boolean): Modifier =
    this.pointerInput(key, horizontal) {
        var total = 0f
        val threshold = 48.dp.toPx()
        if (horizontal) {
            detectHorizontalDragGestures(
                onDragStart = { total = 0f },
                onDragEnd = {
                    if (total > threshold) back() else if (total < -threshold) forward()
                },
            ) { change, amount ->
                change.consume()
                total += amount
            }
        } else {
            detectVerticalDragGestures(
                onDragStart = { total = 0f },
                onDragEnd = {
                    if (total > threshold) back() else if (total < -threshold) forward()
                },
            ) { change, amount ->
                change.consume()
                total += amount
            }
        }
    }
