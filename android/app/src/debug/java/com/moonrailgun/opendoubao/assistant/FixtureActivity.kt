package com.moonrailgun.opendoubao.assistant

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.*

/** Isolated native controls for device tests; never included in release builds. */
class FixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("secure", false)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        column.addView(TextView(this).apply { text = "Native bridge fixture"; textSize = 24f })
        val prefs = getSharedPreferences("fixture", MODE_PRIVATE)
        val note = EditText(this).apply { id = R.id.fixture_note; hint = "Draft text"; setText(prefs.getString("draft", "")) }
        column.addView(note)
        val status = TextView(this).apply { id = R.id.fixture_status; text = "Ready"; textSize = 18f }
        column.addView(Button(this).apply { id = R.id.fixture_apply; text = "Apply draft"; setOnClickListener {
            prefs.edit().putString("draft", note.text.toString()).apply(); status.text = "Draft saved: ${note.text}"
        } })
        column.addView(Switch(this).apply { id = R.id.fixture_setting; text = "Read mode" })
        column.addView(status)
        if (intent.getBooleanExtra("canvas", false)) column.addView(View(this).apply {
            id = R.id.fixture_canvas
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setBackgroundColor(android.graphics.Color.GREEN)
            setOnTouchListener { view, event ->
                if (event.action == android.view.MotionEvent.ACTION_UP) {
                    view.performClick()
                    prefs.edit().putInt("canvas_taps", prefs.getInt("canvas_taps", 0) + 1).apply()
                }
                true
            }
        }, LinearLayout.LayoutParams(-1, 160))
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL
            for (i in 1..50) addView(TextView(context).apply { text = "Reading line $i"; textSize = 18f; setPadding(0, 25, 0, 25) }) }
        column.addView(ScrollView(this).apply {
            addView(rows); isFocusable = false
            isScrollbarFadingEnabled = false
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        column.isFocusableInTouchMode = true
        setContentView(column)
        column.requestFocus()
    }
}
