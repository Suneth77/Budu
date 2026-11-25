package com.budu.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.budu.app.face.Character
import com.budu.app.ui.KidScreen
import com.budu.app.ui.ParentScreen

class MainActivity : ComponentActivity() {

    private val vm: BuduViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { BuduApp(vm) }
    }

    override fun onStart() {
        super.onStart()
        vm.onAppVisible()
    }

    override fun onStop() {
        vm.onAppHidden()
        super.onStop()
    }
}

@Composable
private fun BuduApp(vm: BuduViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val usedMinutes by vm.usedMinutesToday.collectAsStateWithLifecycle()
    var inParentArea by rememberSaveable { mutableStateOf(false) }

    MaterialTheme {
        if (inParentArea) {
            ParentScreen(
                settings = settings,
                usedMinutesToday = usedMinutes,
                onSave = vm::saveSettings,
                onResetToday = vm::resetTodayUsage,
                onExit = {
                    inParentArea = false
                    vm.setParentMode(false)
                },
            )
        } else {
            KidScreen(
                ui = ui,
                character = Character.from(settings.character),
                pin = settings.pin,
                onFaceTap = vm::onFaceTapped,
                onMicGranted = vm::onMicPermissionGranted,
                onPickFriend = vm::selectCharacter,
                onClosePicture = vm::closePicture,
                onParentUnlocked = {
                    inParentArea = true
                    vm.setParentMode(true)
                },
            )
        }
    }
}
