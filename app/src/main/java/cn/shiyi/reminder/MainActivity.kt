package cn.shiyi.reminder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.shiyi.reminder.ui.ShiyiRoot
import cn.shiyi.reminder.ui.ShiyiTheme
import cn.shiyi.reminder.ui.ShiyiViewModel

class MainActivity : ComponentActivity() {
    private val model: ShiyiViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val theme by model.themeMode.collectAsStateWithLifecycle()
            ShiyiTheme(theme) { ShiyiRoot(model) }
        }
    }
    override fun onResume() { super.onResume(); model.refresh() }
}
