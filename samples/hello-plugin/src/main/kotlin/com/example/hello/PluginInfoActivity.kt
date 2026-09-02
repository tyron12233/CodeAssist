package com.example.hello

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * This app's own screen, and the activity whose intent filter makes the app discoverable as a CodeAssist
 * plugin. Deliberately dependency-free: the plugin code that matters runs inside the IDE, not here.
 */
class PluginInfoActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101014"))
            val pad = dp(24)
            setPadding(pad, dp(48), pad, pad)
        }
        body.addView(text("Hello Plugin", size = 28f, bold = true))
        body.addView(text("A CodeAssist plugin shipped as its own app.", size = 15f, color = "#B0B0BC"))
        body.addView(
            text(
                "\nInstall CodeAssist, then open Settings › Plugins › Installed. " +
                    "This app appears there, and the plugin loads on the IDE's next launch.\n\n" +
                    "It adds:\n" +
                    "  • a “Hello: say hello” command in the command palette and the More menu\n" +
                    "  • a “Hello Plugin” category in Settings\n" +
                    "  • log lines attributed to com.example.hello in the Logs screen",
                size = 14f,
                color = "#D8D8E2",
            )
        )
        body.addView(text("\nEntry point\ncom.example.hello.HelloPlugin", size = 13f, color = "#7E7E8C"))

        setContentView(ScrollView(this).apply {
            addView(body, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
    }

    private fun text(value: String, size: Float, bold: Boolean = false, color: String = "#F2F2F7") =
        TextView(this).apply {
            this.text = value
            setTextColor(Color.parseColor(color))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            gravity = Gravity.START
            setPadding(0, dp(4), 0, dp(4))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
