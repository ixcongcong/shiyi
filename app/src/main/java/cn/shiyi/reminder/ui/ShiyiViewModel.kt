package cn.shiyi.reminder.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.shiyi.reminder.ShiyiApp
import cn.shiyi.reminder.data.BackupCodec
import cn.shiyi.reminder.data.OccurrenceEntity
import cn.shiyi.reminder.data.ReminderEntity
import cn.shiyi.reminder.data.ScheduleJson
import cn.shiyi.reminder.domain.Schedule
import cn.shiyi.reminder.domain.RuleType
import java.time.ZonedDateTime
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ScreenData(val reminders: List<ReminderEntity> = emptyList(), val occurrences: List<OccurrenceEntity> = emptyList(), val loaded: Boolean = false)
data class ImportPreview(val raw: String, val reminders: Int, val records: Int)

class ShiyiViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ShiyiApp
    private val store = app.store
    val bridge = store.bridge
    val data = combine(store.dao.observeReminders(), store.dao.observeOccurrences()) { r, o -> ScreenData(r, o, true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ScreenData())
    private val messages = Channel<String>(Channel.BUFFERED)
    val events = messages.receiveAsFlow()
    val busy = MutableStateFlow(false)
    val importPreview = MutableStateFlow<ImportPreview?>(null)
    val permissionVersion = MutableStateFlow(0)
    val diagnosticText = MutableStateFlow("")

    fun message(text: String) { viewModelScope.launch { messages.send(text) } }

    private fun work(success: String? = null, after: () -> Unit = {}, block: suspend () -> Unit) {
        viewModelScope.launch {
            if (busy.value) { message("正在处理，请稍候"); return@launch }
            busy.value = true
            try {
                withContext(Dispatchers.IO) { block() }
                if (success != null) messages.send(success)
                after()
            } catch (e: Exception) {
                messages.send(e.message?.take(160) ?: "操作未完成，请重试")
            } finally { busy.value = false; permissionVersion.value++ }
        }
    }

    fun refresh() {
        permissionVersion.value++
        work {
            app.diagnostics.record("应用打开", "检查并恢复后续提醒")
            store.reconcile(rebaseZone = true)
        }
    }
    fun setStrongReminders(enabled: Boolean) = work("提醒方式已更新") {
        bridge.useAlarmClock = enabled
        store.reconcile()
    }
    fun loadDiagnostics() = work { diagnosticText.value = app.diagnostics.report() }
    fun save(reminder: ReminderEntity, after: () -> Unit) = work("提醒已保存", after) { store.save(reminder) }
    fun toggle(id: String) = work { store.toggle(id) }
    fun archive(id: String) = work("已更新归档状态") { store.archive(id) }
    fun delete(id: String) = work("提醒已删除，历史记录已保留") { store.delete(id) }
    fun act(id: Long, action: String, minutes: Int? = null) = work { store.act(id, action, minutes) }
    fun reschedule(id: Long, time: java.time.Instant) = work("本次时间已调整，后续周期保持原计划") { store.rescheduleOnce(id, time) }

    fun scheduleLockTest() = work("已预约约 1–2 分钟后的测试，请锁屏等待") {
        val time = ZonedDateTime.now().plusMinutes(2).withSecond(0).withNano(0)
        val schedule = Schedule(type = RuleType.ONCE, start = time.toLocalDate(), times = listOf(time.toLocalTime()))
        store.save(ReminderEntity(title = "锁屏测试提醒", note = "这是你主动预约的测试，可以在提醒列表中删除。", scheduleJson = ScheduleJson.encode(schedule)))
    }

    fun export(uri: Uri, recovery: Boolean = false) = work("备份已导出，请妥善保存") {
        val text = if (recovery) app.filesDir.resolve("before-restore.json").readText() else store.exportBackup()
        require(text.toByteArray().size <= BackupCodec.MAX_BYTES) { "数据超过单次备份大小限制" }
        app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: error("无法打开所选文件")
    }
    fun inspectImport(uri: Uri) = work {
        val raw = app.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                require(output.size() + size <= BackupCodec.MAX_BYTES) { "备份超过 8 MB" }
                output.write(buffer, 0, size)
            }
            val bytes = output.toByteArray()
            require(bytes.size <= BackupCodec.MAX_BYTES) { "备份超过 8 MB" }
            bytes.toString(Charsets.UTF_8)
        } ?: error("无法读取备份")
        val backup = BackupCodec.decode(raw)
        importPreview.value = ImportPreview(raw, backup.reminders.size, backup.occurrences.size)
    }
    fun confirmImport() {
        val pending = importPreview.value ?: return
        work("已恢复备份", after = { importPreview.value = null }) {
            val recovery = store.exportBackup()
            app.filesDir.resolve("before-restore.json").writeText(recovery)
            store.restoreBackup(pending.raw)
        }
    }
    fun hasRecovery(): Boolean = app.filesDir.resolve("before-restore.json").exists()
}
