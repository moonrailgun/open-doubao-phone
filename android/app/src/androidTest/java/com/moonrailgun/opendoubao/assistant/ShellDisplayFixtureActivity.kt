package com.moonrailgun.opendoubao.assistant

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Runs under the test APK's distinct UID, as a normal third-party application. */
class ShellDisplayFixtureActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 80, 40, 40) }
        layout.addView(TextView(this).apply { text = "Virtual display fixture"; textSize = 24f })
        layout.addView(EditText(this).apply { hint = "Virtual input"; contentDescription = "Virtual input" })
        layout.addView(Button(this).apply { text = "Open details"; setOnClickListener {
            setContentView(TextView(this@ShellDisplayFixtureActivity).apply { text = "Virtual details"; textSize = 28f })
        } })
        layout.addView(Button(this).apply { text = "Protected page"; setOnClickListener {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            setContentView(TextView(this@ShellDisplayFixtureActivity).apply { text = "Protected content" })
        } })
        setContentView(layout)
    }
}
