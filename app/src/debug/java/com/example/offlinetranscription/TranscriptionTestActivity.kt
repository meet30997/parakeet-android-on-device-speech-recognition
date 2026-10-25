package com.example.offlinetranscription

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity

/** Empty, model-free host for Compose instrumentation tests. */
class TranscriptionTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onCreate(savedInstanceState)
    }
}
