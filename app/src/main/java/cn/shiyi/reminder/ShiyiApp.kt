package cn.shiyi.reminder

import android.app.Application
import androidx.room.Room
import cn.shiyi.reminder.data.ReminderStore
import cn.shiyi.reminder.data.ShiyiDatabase
import cn.shiyi.reminder.system.AlarmBridge
import cn.shiyi.reminder.system.ReminderDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ShiyiApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val diagnostics: ReminderDiagnostics by lazy { ReminderDiagnostics(this) }
    val store: ReminderStore by lazy {
        ReminderStore(Room.databaseBuilder(this, ShiyiDatabase::class.java, "shiyi.db").build(), AlarmBridge(this, diagnostics))
    }
}
