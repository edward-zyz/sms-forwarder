package com.kidbuddy.smsforwarder

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** 原生控件的共同排版与主题，颜色跟随系统日夜模式。 */
class Ui(private val context: Context) {
    fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    fun color(id: Int) = context.getColor(id)
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun round(fill: Int, radius: Int = 20, border: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radius).toFloat()
        setColor(color(fill))
        border?.let { setStroke(dp(1), color(it)) }
    }
    fun text(value: String, size: Float = 16f, tint: Int = R.color.ink, bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color(tint))
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    fun card(fill: Int = R.color.surface) = column().apply {
        setPadding(dp(20), dp(20), dp(20), dp(20))
        background = round(fill)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) }
    }
    fun gap(parent: LinearLayout, height: Int) { parent.addView(View(context), LinearLayout.LayoutParams(1, dp(height))) }
    fun divider(parent: LinearLayout) {
        parent.addView(View(context).apply { setBackgroundColor(color(R.color.line)) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(16); bottomMargin = dp(16) })
    }
    fun button(value: String, primary: Boolean = false, danger: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = value
        textSize = 15f
        isAllCaps = false
        gravity = Gravity.CENTER
        minHeight = dp(48)
        minimumHeight = dp(48)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        val foreground = when { primary -> R.color.on_accent; danger -> R.color.danger; else -> R.color.accent }
        val fill = if (primary) R.color.accent else R.color.accent_surface
        background = RippleDrawable(ColorStateList.valueOf(color(R.color.ripple)), round(fill, 12), null)
        setTextColor(color(foreground))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }
    fun enabled(view: View, enabled: Boolean) { view.isEnabled = enabled; view.alpha = if (enabled) 1f else .4f }
}

/** 为原生 Activity 统一应用外观选择，不影响后台服务的配置。 */
object AppAppearance {
    fun wrap(context: Context): Context {
        val mode = context.getSharedPreferences("appearance", Context.MODE_PRIVATE).getString("mode", "system")
        val night = when (mode) {
            "light" -> Configuration.UI_MODE_NIGHT_NO
            "dark" -> Configuration.UI_MODE_NIGHT_YES
            else -> return context
        }
        return context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        })
    }
}
