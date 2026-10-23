package cn.shiyi.reminder.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.shiyi.reminder.data.ReminderEntity
import cn.shiyi.reminder.data.ScheduleJson
import cn.shiyi.reminder.domain.RuleType
import cn.shiyi.reminder.domain.Schedule
import cn.shiyi.reminder.domain.ScheduleEngine
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(existing: ReminderEntity?, busy: Boolean, onBack: () -> Unit, onSave: (ReminderEntity) -> Unit) {
    val initial = remember(existing?.id) { existing?.schedule() ?: Schedule() }
    val id = rememberSaveable { existing?.id ?: UUID.randomUUID().toString() }
    var title by rememberSaveable { mutableStateOf(existing?.title ?: "") }
    var note by rememberSaveable { mutableStateOf(existing?.note ?: "") }
    var category by rememberSaveable { mutableStateOf(existing?.category ?: "") }
    var ruleName by rememberSaveable { mutableStateOf(initial.type.name) }
    var startText by rememberSaveable { mutableStateOf(initial.start.toString()) }
    var endText by rememberSaveable { mutableStateOf(initial.end?.toString() ?: "") }
    var timesText by rememberSaveable { mutableStateOf(initial.times.joinToString(",")) }
    var weekdaysText by rememberSaveable { mutableStateOf(initial.weekdays.sorted().joinToString(",")) }
    var interval by rememberSaveable { mutableIntStateOf(initial.interval) }
    var cycleText by rememberSaveable { mutableStateOf(initial.cycle.joinToString(",")) }
    var relative by rememberSaveable { mutableStateOf(initial.anchorName.isNotBlank()) }
    var anchorName by rememberSaveable { mutableStateOf(initial.anchorName) }
    var anchorText by rememberSaveable { mutableStateOf(initial.anchorTime.toString()) }
    var offset by rememberSaveable { mutableIntStateOf(initial.offsetMinutes) }
    var privacy by rememberSaveable { mutableStateOf(existing?.privateOnLockscreen ?: true) }
    var snooze by rememberSaveable { mutableIntStateOf(existing?.snoozeMinutes ?: 10) }
    var repeat by rememberSaveable { mutableIntStateOf(existing?.repeatCount ?: 0) }
    var repeatMinutes by rememberSaveable { mutableIntStateOf(existing?.repeatMinutes ?: 10) }
    var timePicker by remember { mutableStateOf<Int?>(null) }
    var datePicker by remember { mutableStateOf<String?>(null) }
    var numberPicker by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var exitConfirm by remember { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }

    val times = timesText.split(',').map { LocalTime.parse(it) }
    val weekdays = weekdaysText.split(',').filter { it.isNotBlank() }.map { it.toInt() }.toSet()
    val cycle = cycleText.split(',').map { it.toInt() }
    val rule = RuleType.valueOf(ruleName)
    val schedule = Schedule(rule, LocalDate.parse(startText), endText.takeIf { it.isNotBlank() }?.let(LocalDate::parse),
        times, weekdays, interval, cycle, if (relative) anchorName else "", LocalTime.parse(anchorText), offset)
    val changed = title != (existing?.title ?: "") || note != (existing?.note ?: "") || category != (existing?.category ?: "") ||
        schedule != initial || privacy != (existing?.privateOnLockscreen ?: true) || snooze != (existing?.snoozeMinutes ?: 10) ||
        repeat != (existing?.repeatCount ?: 0) || repeatMinutes != (existing?.repeatMinutes ?: 10)
    val goBack = { if (changed) exitConfirm = true else onBack() }
    BackHandler(enabled = !busy) { goBack() }

    Scaffold(topBar = {
        TopAppBar(title = { Text(if (existing == null) "添加提醒" else "编辑提醒") },
            navigationIcon = { IconButton(onClick = { if (!busy) goBack() }) { Icon(Icons.Default.ArrowBack, "返回") } },
            actions = { TextButton(enabled = !busy, onClick = {
                try {
                    require(!relative || anchorName.isNotBlank()) { "请填写参照日程名称" }
                    schedule.validate()
                    require(ScheduleEngine.next(schedule, Instant.now(), ZoneId.systemDefault(), 1).isNotEmpty()) { "没有未来的提醒时间，请调整日期或时刻" }
                    val entity = ReminderEntity(id = id, title = title.trim(), note = note.trim(), category = category.trim(),
                        scheduleJson = ScheduleJson.encode(schedule), enabled = existing?.enabled ?: true,
                        archived = existing?.archived ?: false, privateOnLockscreen = privacy,
                        snoozeMinutes = snooze, repeatCount = repeat, repeatMinutes = repeatMinutes,
                        createdAt = existing?.createdAt ?: System.currentTimeMillis())
                    entity.validate(); error = null; onSave(entity)
                } catch (e: Exception) { error = e.message ?: "请检查设置" }
            }) { Text(if (busy) "保存中" else "保存", fontWeight = FontWeight.Bold) } })
    }) { padding ->
        Column(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("把要记住的事，交给时宜。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(title, { if (it.length <= 80) title = it }, Modifier.fillMaxWidth(), label = { Text("提醒名称 *") }, placeholder = { Text("输入你想提醒的事情") }, singleLine = true)
            OutlinedTextField(category, { if (it.length <= 30) category = it }, Modifier.fillMaxWidth(), label = { Text("分类（选填）") }, singleLine = true)
            OutlinedTextField(note, { if (it.length <= 1000) note = it }, Modifier.fillMaxWidth(), label = { Text("备注（选填）") }, minLines = 2, maxLines = 5)
            SectionTitle("重复规则")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuleType.entries.forEach { type -> FilterChip(selected = type == rule, onClick = { ruleName = type.name }, label = { Text(type.label) }) }
            }
            if (rule == RuleType.WEEKLY) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..7).forEach { day ->
                        FilterChip(selected = day in weekdays, onClick = {
                            weekdaysText = (if (day in weekdays) weekdays - day else weekdays + day).sorted().joinToString(",")
                        }, label = { Text("周${"一二三四五六日"[day - 1]}") })
                    }
                }
            }
            if (rule == RuleType.EVERY_N_DAYS) {
                SettingLine("间隔天数", "每 $interval 天一次") { numberPicker = "interval" }
                Text("从开始日期起算。例如每 2 天：1 日、3 日、5 日。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (rule == RuleType.CYCLE) {
                Text("依次间隔以下天数，走完后循环。", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    cycle.forEachIndexed { index, days ->
                        InputChip(selected = false, onClick = { numberPicker = "cycle:$index" }, label = { Text("$days 天") }, trailingIcon = {
                            if (cycle.size > 1) Icon(Icons.Default.Close, "删除第 ${index + 1} 段", Modifier.size(18.dp).clickable {
                                cycleText = cycle.filterIndexed { i, _ -> i != index }.joinToString(",")
                            })
                        })
                    }
                    if (cycle.size < 12) AssistChip(onClick = { cycleText += ",2" }, label = { Text("添加一段") }, leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(16.dp)) })
                }
            }
            SettingLine(if (rule == RuleType.ONCE) "提醒日期" else "开始日期", startText) { datePicker = "start" }
            if (rule != RuleType.ONCE) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("设置结束日期", Modifier.weight(1f))
                    Switch(endText.isNotEmpty(), { endText = if (it) maxOf(LocalDate.now(), LocalDate.parse(startText)).toString() else "" })
                }
                if (endText.isNotBlank()) SettingLine("结束日期", endText) { datePicker = "end" }
            }
            SectionTitle("提醒时间")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("相对日程"); Text("按你设置的参照时间提前或延后", style = MaterialTheme.typography.bodySmall) }
                Switch(relative, { relative = it })
            }
            if (relative) {
                OutlinedTextField(anchorName, { if (it.length <= 40) anchorName = it }, Modifier.fillMaxWidth(), label = { Text("参照日程名称 *") }, singleLine = true)
                SettingLine("参照时间", anchorText) { timePicker = -2 }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(offset <= 0, { offset = -kotlin.math.abs(offset) }, label = { Text("提前") })
                    FilterChip(offset > 0, { offset = kotlin.math.abs(offset).coerceAtLeast(1) }, label = { Text("延后") })
                    TextButton(onClick = { numberPicker = "offset" }) { Text("${kotlin.math.abs(offset)} 分钟 · 滚轮选择") }
                }
            } else {
                times.forEachIndexed { index, time ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { timePicker = index }, Modifier.weight(1f)) { Text(time.toString(), style = MaterialTheme.typography.titleLarge) }
                        if (times.size > 1) IconButton(onClick = { timesText = times.filterIndexed { i, _ -> i != index }.joinToString(",") }) { Icon(Icons.Default.Close, "删除时间 $time") }
                    }
                }
                if (times.size < 12) TextButton(onClick = { timePicker = -1 }) { Icon(Icons.Default.Add, null); Text("添加一个时间") }
            }
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起提示设置" else "提示设置 · 延后 / 重复 / 锁屏隐私") }
            if (advanced) {
                SettingLine("默认延后", "$snooze 分钟") { numberPicker = "snooze" }
                SettingLine("未确认时再次提示", if (repeat == 0) "关闭" else "最多 $repeat 次") { numberPicker = "repeat" }
                if (repeat > 0) SettingLine("再次提示间隔", "$repeatMinutes 分钟") { numberPicker = "repeatMinutes" }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("锁屏隐藏名称与备注", Modifier.weight(1f)); Switch(privacy, { privacy = it })
                }
                Text("声音与振动在系统的“事项提醒”通知渠道中设置。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SectionTitle("未来时间预览")
            val preview = runCatching { if (relative && anchorName.isBlank()) emptyList() else ScheduleEngine.next(schedule, Instant.now(), ZoneId.systemDefault(), 5) }.getOrDefault(emptyList())
            if (preview.isEmpty()) Text("请完善规则，或调整为未来的日期与时间。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            preview.forEach { p -> Text("· " + p.instant.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd E HH:mm", java.util.Locale.CHINA)), style = MaterialTheme.typography.bodyMedium) }
            Text("跟随手机当地时间。保存编辑会更新后续安排，已产生的完成记录保留。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(24.dp))
        }
    }
    timePicker?.let { index ->
        TimeWheelDialog(if (index == -2) LocalTime.parse(anchorText) else times.getOrNull(index) ?: LocalTime.now().withSecond(0).withNano(0),
            { timePicker = null }, { selected ->
                if (index == -2) anchorText = selected.toString()
                else {
                    val next = times.toMutableList()
                    if (index == -1) next.add(selected) else next[index] = selected
                    if (next.distinct().size != next.size) error = "这个时间已经添加过了" else { timesText = next.sorted().joinToString(","); error = null }
                }
                timePicker = null
            })
    }
    datePicker?.let { which -> DateWheelDialog(LocalDate.parse(if (which == "start") startText else endText), { datePicker = null }, {
        if (which == "start") startText = it.toString() else endText = it.toString(); datePicker = null
    }) }
    numberPicker?.let { which ->
        val initialNumber = when {
            which == "interval" -> interval
            which.startsWith("cycle:") -> cycle[which.substringAfter(':').toInt()]
            which == "offset" -> kotlin.math.abs(offset)
            which == "snooze" -> snooze
            which == "repeat" -> repeat
            else -> repeatMinutes
        }
        val range = when (which) { "offset" -> 0..1440; "snooze" -> 1..1440; "repeat" -> 0..5; "repeatMinutes" -> 5..120; else -> 1..3650 }
        NumberWheelDialog("滚动选择", initialNumber, range, if (which == "repeat") "次（0 为关闭）" else if (which in setOf("snooze", "offset", "repeatMinutes")) "分钟" else "天", { numberPicker = null }, {
            when {
                which == "interval" -> interval = it
                which == "offset" -> offset = if (offset <= 0) -it else it
                which == "snooze" -> snooze = it
                which == "repeat" -> repeat = it
                which == "repeatMinutes" -> repeatMinutes = it
                else -> { val next = cycle.toMutableList(); next[which.substringAfter(':').toInt()] = it; cycleText = next.joinToString(",") }
            }
            numberPicker = null
        })
    }
    if (exitConfirm) AlertDialog(onDismissRequest = { exitConfirm = false }, title = { Text("放弃这次修改？") }, text = { Text("尚未保存的内容会丢失。") },
        confirmButton = { TextButton(onClick = onBack) { Text("放弃修改") } }, dismissButton = { TextButton(onClick = { exitConfirm = false }) { Text("继续编辑") } })
}

@Composable
fun SectionTitle(title: String) { Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }

@Composable
fun SettingLine(title: String, value: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(value, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
