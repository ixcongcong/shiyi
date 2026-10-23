package cn.shiyi.reminder.ui

import android.os.Build
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.NumberPicker
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

@Composable
private fun Wheel(value: Int, range: IntRange, label: String, pad: Boolean = false, onValue: (Int) -> Unit) {
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AndroidView(
            modifier = Modifier.width(82.dp).height(160.dp),
            factory = { context -> NumberPicker(context).apply {
                minValue = range.first; maxValue = range.last
                wrapSelectorWheel = range.first == 0
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                contentDescription = label
                if (pad) setFormatter { "%02d".format(it) }
            } },
            update = { picker ->
                // Update maximum first when growing, minimum first when shrinking.
                if (range.last > picker.maxValue) picker.maxValue = range.last
                picker.minValue = range.first; picker.maxValue = range.last
                picker.value = value.coerceIn(range)
                if (Build.VERSION.SDK_INT >= 29) {
                    picker.textColor = color
                    picker.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 22f, picker.resources.displayMetrics)
                    picker.selectionDividerHeight = 2
                }
                picker.setOnValueChangedListener { _, _, next -> onValue(next) }
            },
        )
    }
}

@Composable
fun TimeWheelDialog(initial: LocalTime, onDismiss: () -> Unit, onConfirm: (LocalTime) -> Unit) {
    var hour by remember { mutableIntStateOf(initial.hour) }
    var minute by remember { mutableIntStateOf(initial.minute) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择时间") },
        text = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Wheel(hour, 0..23, "时", true) { hour = it }
                Wheel(minute, 0..59, "分", true) { minute = it }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(LocalTime.of(hour, minute)) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
fun DateWheelDialog(initial: LocalDate, onDismiss: () -> Unit, onConfirm: (LocalDate) -> Unit) {
    var year by remember { mutableIntStateOf(initial.year) }
    var month by remember { mutableIntStateOf(initial.monthValue) }
    var day by remember { mutableIntStateOf(initial.dayOfMonth) }
    val maxDay = YearMonth.of(year, month).lengthOfMonth()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择日期") },
        text = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Wheel(year, 2000..2199, "年") { year = it; day = minOf(day, YearMonth.of(year, month).lengthOfMonth()) }
                Wheel(month, 1..12, "月") { month = it; day = minOf(day, YearMonth.of(year, month).lengthOfMonth()) }
                Wheel(day.coerceAtMost(maxDay), 1..maxDay, "日") { day = it }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(LocalDate.of(year, month, minOf(day, maxDay))) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
fun NumberWheelDialog(title: String, initial: Int, range: IntRange, unit: String, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var value by remember { mutableIntStateOf(initial.coerceIn(range)) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { Wheel(value, range, unit) { value = it } } },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
