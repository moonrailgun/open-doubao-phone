package com.openodoubao.assistant.remote

import android.os.Bundle
import android.view.WindowManager
import com.journeyapps.barcodescanner.CaptureActivity

class PairingCaptureActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The camera preview contains the desktop's pairing credential.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
