package com.dylr.recorder.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dylr.recorder.ui.screens.HistoryScreen
import com.dylr.recorder.ui.screens.HomeScreen
import com.dylr.recorder.ui.screens.SettingsScreen

private const val TAB_RECORD = 0
private const val TAB_HISTORY = 1
private const val TAB_ABOUT = 2

@Composable
fun MainScreen(vm: MainViewModel) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(TAB_RECORD) }
    val message by vm.message.collectAsStateWithLifecycle()

    LaunchedEffect(message) {
        val text = message
        if (text != null) {
            runCatching { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
            vm.consumeMessage()
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == TAB_RECORD,
                    onClick = { tab = TAB_RECORD },
                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = "录制") },
                    label = { Text("录制") }
                )
                NavigationBarItem(
                    selected = tab == TAB_HISTORY,
                    onClick = { tab = TAB_HISTORY },
                    icon = { Icon(Icons.Default.List, contentDescription = "历史") },
                    label = { Text("历史") }
                )
                NavigationBarItem(
                    selected = tab == TAB_ABOUT,
                    onClick = { tab = TAB_ABOUT },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "关于") },
                    label = { Text("关于") }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (tab) {
                TAB_HISTORY -> HistoryScreen(vm)
                TAB_ABOUT -> SettingsScreen()
                else -> HomeScreen(vm)
            }
        }
    }
}
