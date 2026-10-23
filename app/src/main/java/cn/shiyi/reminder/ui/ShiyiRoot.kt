package cn.shiyi.reminder.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.shiyi.reminder.data.OccurrenceEntity
import cn.shiyi.reminder.data.OccurrenceState
import cn.shiyi.reminder.data.ReminderEntity
import cn.shiyi.reminder.system.AlarmBridge
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShiyiRoot(vm: ShiyiViewModel) {
    val data by vm.data.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val importPreview by vm.importPreview.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.events.collect { snackbar.showSnackbar(it) } }

    val editKey = editing
    if (editKey != null) {
        val source = remember(editKey) {
            val original = data.reminders.firstOrNull { it.id == editKey.removePrefix("copy:") }
            if (editKey.startsWith("copy:")) original?.copy(id = UUID.randomUUID().toString(), title = original.title.take(76) + " 副本", enabled = true, archived = false, createdAt = System.currentTimeMillis())
            else original
        }
        // Snackbar stays mounted while editing so validation/storage failures remain visible.
        Box {
            key(editKey) { EditorScreen(source, busy, { editing = null }) { vm.save(it) { editing = null } } }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
    } else {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(title = {
                    Column {
                        Text(if (tab == 0) "时宜" else listOf("今日", "提醒", "记录", "设置")[tab], fontWeight = FontWeight.Bold)
                        if (tab == 0) Text("在合适的时候，记得重要的事", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    val labels = listOf("今日", "提醒", "记录", "设置")
                    val icons = listOf(Icons.Default.Today, Icons.Default.NotificationsNone, Icons.Default.History, Icons.Default.Tune)
                    labels.forEachIndexed { index, label -> NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(icons[index], null) }, label = { Text(label) }) }
                }
            },
            floatingActionButton = {
                if (tab < 2) ExtendedFloatingActionButton(onClick = { editing = "new" }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("添加提醒") })
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
                if (!data.loaded) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else when (tab) {
                    0 -> TodayScreen(data, vm, { editing = "new" }, { tab = 3 })
                    1 -> RemindersScreen(data.reminders, busy, { editing = it }, { editing = "copy:$it" }, vm::toggle, vm::archive, vm::delete, { editing = "new" })
                    2 -> HistoryScreen(data, vm)
                    else -> SettingsScreen(vm, busy)
                }
            }
        }
    }
    importPreview?.let { preview ->
        AlertDialog(onDismissRequest = { if (!busy) vm.importPreview.value = null }, title = { Text("恢复这份备份？") },
            text = { Text("包含 ${preview.reminders} 项提醒和 ${preview.records} 条计划记录。\n\n将替换当前数据和待发提醒。恢复前会在本机保留一份原数据备份，可在设置中导出。") },
            confirmButton = { TextButton(enabled = !busy, onClick = vm::confirmImport) { Text(if (busy) "恢复中" else "替换并恢复") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { vm.importPreview.value = null }) { Text("取消") } })
    }
}

@Composable
private fun TodayScreen(data: ScreenData, vm: ShiyiViewModel, add: () -> Unit, settings: () -> Unit) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { now = Instant.now(); delay(30_000) } }
    val zone = ZoneId.systemDefault()
    val today = now.atZone(zone).toLocalDate()
    val todayItems = data.occurrences.filter { Instant.ofEpochMilli(it.plannedAt).atZone(zone).toLocalDate() == today }.sortedBy { it.plannedAt }
    val older = data.occurrences.filter { it.state in setOf(OccurrenceState.ALERTING, OccurrenceState.MISSED) && Instant.ofEpochMilli(it.plannedAt).atZone(zone).toLocalDate().isBefore(today) }
    val next = data.occurrences.filter { it.state == OccurrenceState.PENDING && it.ringAt >= now.toEpochMilli() }.minByOrNull { it.ringAt }
    val version by vm.permissionVersion.collectAsStateWithLifecycle()
    val canNotify = remember(version) { vm.bridge.canNotify() }
    val exact = remember(version) { vm.bridge.canExact() }
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 104.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text(today.format(DateTimeFormatter.ofPattern("M 月 d 日 · EEEE", Locale.CHINA)), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!canNotify || !exact) item {
            Surface(onClick = settings, color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.NotificationsActive, null); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (!canNotify) "开启通知，才能收到提醒" else "准时提醒还需要设置", fontWeight = FontWeight.Bold)
                        Text("点击检查提醒权限", style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Default.ChevronRight, null)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("今日 ${todayItems.count { it.state == OccurrenceState.DONE }} / ${todayItems.size} 已完成", style = MaterialTheme.typography.labelLarge)
                    Text(if (next == null) "给每一天，留一点从容" else formatAt(next.ringAt, "HH:mm"), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text(next?.let { "下一项 · ${it.title}" } ?: if (data.reminders.isEmpty()) "从添加第一项提醒开始" else "暂时没有待发提醒", style = MaterialTheme.typography.bodyLarge)
                    if (next != null) Text(formatAt(next.ringAt, "M 月 d 日 EEEE"), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (data.reminders.none { !it.archived }) item {
            EmptyState("把想记住的事放在这里", "自由设置时间和频率，时宜会在合适的时候提醒你。", "添加第一项提醒", add)
        } else {
            item { SectionTitle("今天的安排") }
            if (todayItems.isEmpty()) item { Text("今天没有安排，享受自己的时间。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(todayItems, key = { it.id }) { OccurrenceCard(it, vm, data.reminders.firstOrNull { r -> r.id == it.reminderId }) }
        }
        if (older.isNotEmpty()) {
            item { SectionTitle("之前待处理 · ${older.size}") }
            items(older.take(30), key = { "older:${it.id}" }) { OccurrenceCard(it, vm, data.reminders.firstOrNull { r -> r.id == it.reminderId }) }
        }
    }
}

@Composable
private fun OccurrenceCard(o: OccurrenceEntity, vm: ShiyiViewModel, r: ReminderEntity?) {
    val final = o.state == OccurrenceState.DONE || o.state == OccurrenceState.SKIPPED
    var snooze by remember { mutableStateOf(false) }
    var adjustDate by remember { mutableStateOf(false) }
    var adjustTime by remember { mutableStateOf<LocalDate?>(null) }
    var more by remember { mutableStateOf(false) }
    val snoozable = r != null && r.enabled && !r.archived && r.revision == o.revision
    val state = when (o.state) {
        OccurrenceState.DONE -> "已完成"
        OccurrenceState.SKIPPED -> "已跳过"
        OccurrenceState.ALERTING -> "待确认"
        OccurrenceState.MISSED -> "待处理"
        else -> if (o.actionAt != null) "已延后" else "待提醒"
    }
    val busy by vm.busy.collectAsStateWithLifecycle()
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatAt(o.plannedAt, "HH:mm"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(state, color = if (o.state == OccurrenceState.DONE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            }
            Text(o.title, style = MaterialTheme.typography.titleMedium)
            if (o.note.isNotBlank()) Text(o.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (o.state == OccurrenceState.PENDING && o.actionAt != null) Text("本次改为 ${formatAt(o.ringAt, "M-d HH:mm")}", style = MaterialTheme.typography.bodySmall)
            if (o.detail.isNotEmpty() && o.state == OccurrenceState.MISSED) Text(o.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            if (final && o.actionAt != null) Text("${formatAt(o.actionAt, "M-d HH:mm")} ${if (o.state == OccurrenceState.DONE) "确认完成" else "跳过"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!final) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(enabled = !busy, onClick = { vm.act(o.id, AlarmBridge.DONE) }) { Icon(Icons.Default.Check, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("完成") }
                if (snoozable) TextButton(enabled = !busy, onClick = { snooze = true }) { Text("延后") }
                TextButton(enabled = !busy, onClick = { vm.act(o.id, AlarmBridge.SKIP) }) { Text("跳过") }
                if (snoozable) Box {
                    IconButton(enabled = !busy, onClick = { more = true }) { Icon(Icons.Default.MoreVert, "更多操作") }
                    DropdownMenu(more, { more = false }) {
                        DropdownMenuItem(text = { Text("调整本次时间") }, onClick = { more = false; adjustDate = true })
                    }
                }
            }
        }
    }
    if (snooze) NumberWheelDialog("本次延后多久", r?.snoozeMinutes ?: 10, 1..1440, "分钟", { snooze = false }, { vm.act(o.id, AlarmBridge.SNOOZE, it); snooze = false })
    if (adjustDate) DateWheelDialog(maxOf(LocalDate.now(), Instant.ofEpochMilli(o.plannedAt).atZone(ZoneId.systemDefault()).toLocalDate()), { adjustDate = false }, {
        adjustDate = false; adjustTime = it
    })
    adjustTime?.let { date -> TimeWheelDialog(Instant.ofEpochMilli(o.plannedAt).atZone(ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0), { adjustTime = null }, {
        val s = cn.shiyi.reminder.domain.Schedule(type = cn.shiyi.reminder.domain.RuleType.ONCE, start = date, times = listOf(it))
        val time = cn.shiyi.reminder.domain.ScheduleEngine.onDate(s, date, ZoneId.systemDefault()).single().instant
        vm.reschedule(o.id, time); adjustTime = null
    }) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemindersScreen(reminders: List<ReminderEntity>, busy: Boolean, edit: (String) -> Unit, copy: (String) -> Unit, toggle: (String) -> Unit, archive: (String) -> Unit, delete: (String) -> Unit, add: () -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var category by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<ReminderEntity?>(null) }
    val categories = reminders.filter { it.archived == showArchived }.map { it.category.ifBlank { "未分类" } }.distinct().sorted()
    val selected = reminders.filter { it.archived == showArchived && (category.isEmpty() || category == it.category.ifBlank { "未分类" }) &&
        (it.title.contains(search, true) || it.note.contains(search, true)) }
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 104.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), placeholder = { Text("搜索提醒") }, singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) }, shape = MaterialTheme.shapes.large)
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(!showArchived, { showArchived = false; category = "" }, label = { Text("使用中") })
                Spacer(Modifier.width(8.dp))
                FilterChip(showArchived, { showArchived = true; category = "" }, label = { Text("已归档") })
                Spacer(Modifier.weight(1f)); Text("${selected.size} 项", style = MaterialTheme.typography.bodySmall)
            }
            if (categories.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(category.isEmpty(), { category = "" }, label = { Text("全部") })
                categories.forEach { cat -> FilterChip(category == cat, { category = cat }, label = { Text(cat) }) }
            }
        }
        if (selected.isEmpty()) item { EmptyState(if (showArchived) "还没有归档提醒" else "还没有找到提醒", "点击添加，按自己的习惯设置。", if (!showArchived) "添加提醒" else null, add) }
        items(selected, key = { it.id }) { r ->
            var menu by remember { mutableStateOf(false) }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.category.ifBlank { "未分类" }, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                            Text(r.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        if (!r.archived) Switch(r.enabled, { toggle(r.id) }, enabled = !busy)
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "更多操作") }
                            DropdownMenu(menu, { menu = false }) {
                                if (!r.archived) DropdownMenuItem(text = { Text("编辑") }, onClick = { menu = false; edit(r.id) })
                                DropdownMenuItem(text = { Text("复制") }, onClick = { menu = false; copy(r.id) })
                                DropdownMenuItem(text = { Text(if (r.archived) "移出归档（保持暂停）" else "归档") }, enabled = !busy, onClick = { menu = false; archive(r.id) })
                                DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) }, enabled = !busy, onClick = { menu = false; deleting = r })
                            }
                        }
                    }
                    Text(r.schedule().summary(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (r.note.isNotBlank()) Text(r.note, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                    if (!r.enabled && !r.archived) Text("已暂停", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
    deleting?.let { r -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这项提醒？") }, text = { Text("“${r.title}”的后续提醒会被取消，历史记录仍保留。") },
        confirmButton = { TextButton(onClick = { delete(r.id); deleting = null }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryScreen(data: ScreenData, vm: ShiyiViewModel) {
    var filter by rememberSaveable { mutableStateOf("全部") }
    val states = mapOf("已完成" to setOf(OccurrenceState.DONE), "已跳过" to setOf(OccurrenceState.SKIPPED), "待处理" to setOf(OccurrenceState.MISSED, OccurrenceState.ALERTING))
    val records = data.occurrences.filter { it.state != OccurrenceState.PENDING && (filter == "全部" || it.state in states[filter].orEmpty()) }.sortedByDescending { it.plannedAt }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("全部", "已完成", "已跳过", "待处理").forEach { label -> FilterChip(filter == label, { filter = label }, label = { Text(label) }) }
            }
            Text("记录由你主动确认。收到通知不代表已完成。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (records.isEmpty()) item { EmptyState("这里会留下你的记录", "提醒发生后，可以确认完成、延后或跳过。") }
        items(records.take(500), key = { it.id }) { o ->
            Text(formatAt(o.plannedAt, "yyyy 年 M 月 d 日"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OccurrenceCard(o, vm, data.reminders.firstOrNull { it.id == o.reminderId })
        }
        if (records.size > 500) item { Text("显示最近 500 条；完整记录可通过备份导出。", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun SettingsScreen(vm: ShiyiViewModel, busy: Boolean) {
    val context = LocalContext.current
    val version by vm.permissionVersion.collectAsStateWithLifecycle()
    val canNotify = remember(version) { vm.bridge.canNotify() }
    val canExact = remember(version) { vm.bridge.canExact() }
    val strong = remember(version) { vm.bridge.useAlarmClock }
    val power = remember { context.getSystemService(PowerManager::class.java) }
    val ignored = remember(version) { power.isIgnoringBatteryOptimizations(context.packageName) }
    val diagnosticText by vm.diagnosticText.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var showDiagnostics by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }
    var exportRecovery by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { if (it != null) vm.export(it, exportRecovery) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { if (it != null) vm.inspectImport(it) }
    var showExport by remember { mutableStateOf(false) }
    fun open(intent: Intent) {
        try { context.startActivity(intent) } catch (_: RuntimeException) {
            try { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
            catch (_: RuntimeException) { vm.message("请在手机设置中打开时宜的应用设置") }
        }
    }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text("让提醒，按时到来", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("完成下面的检查，再预约一次锁屏提醒，确认手机上的实际效果。", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item { SectionTitle("提醒检查") }
        item { SettingLine("通知权限与渠道", if (canNotify) "已允许" else "需要开启") {
            if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        } }
        item { SettingLine("准时提醒", if (canExact) "已允许" else "可能延迟 · 去开启") {
            if (Build.VERSION.SDK_INT >= 31) open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
            else vm.message("当前系统无需单独授权精确提醒")
        } }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("加强锁屏提醒", fontWeight = FontWeight.SemiBold)
                    Text("使用系统闹钟调度，可能显示闹钟图标；需允许准时提醒。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(strong, { vm.setStrongReminders(it) }, enabled = !busy)
            }
        }
        item { SettingLine("声音、振动与锁屏展示", "系统设置") {
            open(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, AlarmBridge.CHANNEL))
        } }
        item { SettingLine("系统电池优化", if (ignored) "已豁免" else "去检查") { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
        item { SettingLine("小米自启动与省电策略", "需手动检查") {
            val xiaomi = Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco")
            if (xiaomi) open(Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
            else open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        } }
        item {
            Text("小米 HyperOS：允许时宜自启动，并在应用详情的省电策略中选择“无限制”。如系统电池优化列表中可找到时宜，将其设为不优化。同时检查锁屏通知与勿扰模式。入口名称可能随版本变化。\n\n加强锁屏提醒也不能绕过系统强行停止或关闭通知。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = { vm.message(if (vm.bridge.test()) "已提交测试通知，请检查实际展示与声音" else "通知未获允许，请先检查权限和渠道") }) { Text("测试通知") }
                OutlinedButton(enabled = !busy, onClick = { vm.scheduleLockTest() }) { Text("预约锁屏测试") }
            }
        }
        item { SettingLine("锁屏提醒诊断", "查看或复制时间记录") { if (!busy) { vm.loadDiagnostics(); showDiagnostics = true } } }
        if (showDiagnostics) item {
            Text(diagnosticText.ifBlank { "正在读取…" }, style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = vm::loadDiagnostics, enabled = !busy) { Text("刷新") }
                TextButton(onClick = { clipboard.setText(AnnotatedString(diagnosticText)); vm.message("诊断记录已复制") }, enabled = diagnosticText.isNotBlank()) { Text("复制记录") }
                TextButton(onClick = { showDiagnostics = false }) { Text("收起") }
            }
        }
        item { SectionTitle("数据与隐私") }
        item { SettingLine("导出备份", "保存到文件") { if (!busy) { exportRecovery = false; showExport = true } } }
        item { SettingLine("恢复备份", "选择文件后预览") { if (!busy) import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) } }
        if (vm.hasRecovery()) item { SettingLine("恢复前的原数据", "导出备份") { if (!busy) { exportRecovery = true; showExport = true } } }
        item { Text("内容只保存在本机，应用不申请网络权限，也不启用系统自动备份。手动备份为明文文件，包含提醒与记录；请保存在可信位置。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { SectionTitle("关于时宜") }
        item { Text("时宜 0.1.1 · 本地试用版\n所有事项由你自行添加，时间使用 24 小时制滚轮。\n目标适配小米 17 Pro Max、小米 17 Ultra 徕卡版，实际表现需真机验证。", style = MaterialTheme.typography.bodyMedium) }
        item { Spacer(Modifier.height(24.dp)) }
    }
    if (showExport) AlertDialog(onDismissRequest = { showExport = false }, title = { Text("导出明文备份") }, text = { Text("文件将包含提醒名称、备注和历史记录。请选择可信的保存位置。") },
        confirmButton = { TextButton(onClick = { showExport = false; export.launch(if (exportRecovery) "shiyi-before-restore.json" else "shiyi-${LocalDate.now()}.json") }) { Text("选择保存位置") } },
        dismissButton = { TextButton(onClick = { showExport = false }) { Text("取消") } })
}

@Composable
private fun EmptyState(title: String, description: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 34.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.NotificationsNone, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) FilledTonalButton(onClick = onAction) { Text(action) }
    }
}

private fun formatAt(ms: Long, pattern: String) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(pattern, Locale.CHINA))
