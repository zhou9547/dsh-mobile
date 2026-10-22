package io.github.sayach.dshmobile

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Checkable
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView

/** Uses each manifest alias's real launcher artwork rather than a second preview mapping. */
internal class LauncherIconAdapter(private val context: Context, private val labels: Array<String>) : BaseAdapter() {
    override fun getCount() = LauncherIconPolicy.icons.size
    override fun getItem(position: Int) = LauncherIconPolicy.icons[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val icon = getItem(position)
        return Row(context).apply {
            val component = ComponentName(context,
                LauncherIconPolicy.aliasClassName(MainActivity::class.java.name.substringBeforeLast('.'), icon))
            @Suppress("DEPRECATION")
            preview.setImageDrawable(context.packageManager.getActivityInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS)
                .loadIcon(context.packageManager))
            label.text = labels[position]
        }
    }

    private class Row(context: Context) : LinearLayout(context), Checkable {
        private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        val preview = ImageView(context)
        val label = TextView(context)
        private val radio = RadioButton(context)
        init {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(72)
            setPadding(dp(20), dp(8), dp(16), dp(8))
            preview.apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = GradientDrawable().apply { setColor(Color.rgb(136, 144, 153)); cornerRadius = dp(12).toFloat() }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(preview, LayoutParams(dp(48), dp(48)))
            label.apply { textSize = 16f; setTextColor(context.getColor(R.color.app_foreground)) }
            addView(label, LayoutParams(0, -2, 1f).apply { setMargins(dp(16), 0, dp(8), 0) })
            radio.apply { isClickable = false; isFocusable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            addView(radio)
        }
        override fun isChecked() = radio.isChecked
        override fun setChecked(checked: Boolean) { radio.isChecked = checked }
        override fun toggle() { radio.toggle() }
    }
}
