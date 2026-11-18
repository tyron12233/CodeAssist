package dev.codeassist.csharp

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * This app's own screen, and the activity whose intent filter makes the app discoverable as a
 * CodeAssist plugin. The code that matters runs inside the IDE, not here.
 */
class PluginInfoActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101014"))
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }
        body.addView(text(getString(R.string.app_name), 28f))
        body.addView(
            text(
                "A CodeAssist plugin.\n\n" +
                    "Install this app, then open CodeAssist and look under " +
                    "Settings > Plugins > Installed. It loads on the IDE's next launch.\n\n" +
                    "Entry point: CSharpPlugin",
                15f,
            )
        )
        setContentView(
            ScrollView(this).apply {
                addView(body, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        )
    }

    private fun text(value: String, size: Float) = TextView(this).apply {
        this.text = value
        setTextColor(Color.parseColor("#F2F2F7"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
