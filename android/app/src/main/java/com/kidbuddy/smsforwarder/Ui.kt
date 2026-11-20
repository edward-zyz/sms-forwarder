package com.kidbuddy.smsforwarder

import android.animation.LayoutTransition
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.provider.Settings
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDragHandleView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Material 3 组件工厂与统一视觉规范，完整适配浅色/深色主题与无障碍标准。 */
class Ui(private val context: Context) {
    enum class Tone(val ink: Int, val surface: Int) {
        NEUTRAL(R.color.muted, R.color.neutral_surface),
        ACCENT(R.color.accent, R.color.accent_surface),
        SUCCESS(R.color.success, R.color.success_surface),
        WARNING(R.color.warning, R.color.warning_surface),
        DANGER(R.color.danger, R.color.danger_surface)
    }

    fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    fun color(id: Int) = context.getColor(id)
    fun colorList(id: Int) = ColorStateList.valueOf(color(id))
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    fun motionEnabled(): Boolean = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }.getOrDefault(true)

    fun enableMotion(container: ViewGroup) {
        if (!motionEnabled()) return
        container.layoutTransition = LayoutTransition().apply {
            setDuration(200L)
            enableTransitionType(LayoutTransition.CHANGING)
        }
    }

    fun haptic(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    fun round(fill: Int, radius: Int = 18, border: Int? = null, strokeWidthDp: Int = 1) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radius).toFloat()
        setColor(color(fill))
        border?.let { setStroke(dp(strokeWidthDp), color(it)) }
    }

    fun ripple(fill: Int, radius: Int = 12, border: Int? = null) =
        RippleDrawable(colorList(R.color.ripple), round(fill, radius, border), null)

    fun text(value: String, size: Float = 15f, tint: Int = R.color.ink, bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color(tint))
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun fieldLabel(title: String, required: Boolean = false, optional: Boolean = false) = TextView(context).apply {
        textSize = 13f
        setTextColor(color(R.color.muted))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        text = when {
            required -> SpannableString("$title *").apply {
                setSpan(ForegroundColorSpan(color(R.color.danger)), title.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            optional -> SpannableString("$title · 选填").apply {
                setSpan(ForegroundColorSpan(color(R.color.subtle)), title.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            else -> title
        }
    }

    fun icon(resId: Int, sizeDp: Int = 18, tint: Int = R.color.subtle) = ImageView(context).apply {
        setImageResource(resId)
        imageTintList = colorList(tint)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    fun chevronRight(tint: Int = R.color.subtle) = icon(R.drawable.ic_chevron_right, 18, tint).apply {
        layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(4) }
    }

    fun chevronDown(tint: Int = R.color.accent) = icon(R.drawable.ic_chevron_down, 18, tint).apply {
        layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(4) }
    }

    fun rotateChevron(view: ImageView, expanded: Boolean) {
        val target = if (expanded) 180f else 0f
        if (view.rotation == target) return
        if (motionEnabled()) view.animate().rotation(target).setDuration(180L).start()
        else view.rotation = target
    }

    /** Material 3 现代卡片容器，支持圆角、极细描边与自动内边距。 */
    fun card(
        fill: Int = R.color.surface,
        border: Int? = R.color.card_stroke,
        padH: Int = 18,
        padV: Int = 18,
        bottomMargin: Int = 14,
        block: LinearLayout.() -> Unit = {}
    ): MaterialCardView {
        val inner = column().apply {
            setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
            block()
        }
        return MaterialCardView(context).apply {
            radius = dp(20).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(color(fill))
            strokeWidth = if (border != null) dp(1) else 0
            border?.let { strokeColor = color(it) }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { this.bottomMargin = dp(bottomMargin) }
            addView(inner, ViewGroup.LayoutParams(-1, -2))
        }
    }

    fun styleCard(card: MaterialCardView, fill: Int, border: Int? = R.color.card_stroke) {
        card.setCardBackgroundColor(color(fill))
        card.strokeWidth = if (border != null) dp(1) else 0
        border?.let { card.strokeColor = color(it) }
    }

    fun sectionTitle(parent: LinearLayout, title: String, subtitle: String? = null) {
        parent.addView(text(title, 13f, R.color.muted, bold = true).apply {
            isAccessibilityHeading = true
            setPadding(dp(4), dp(6), dp(4), dp(if (subtitle == null) 8 else 2))
        })
        if (subtitle != null) {
            parent.addView(text(subtitle, 13f, R.color.subtle).apply {
                setPadding(dp(4), 0, dp(4), dp(8))
            })
        }
    }

    /** 子页面统一的顶部导航栏：48dp 触控区的矢量返回按钮 + 无障碍标题。 */
    fun topBar(title: String, onBack: () -> Unit) = row().apply {
        setPadding(0, 0, 0, dp(12))
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_back)
            imageTintList = colorList(R.color.ink)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "返回"
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = ripple(R.color.surface, 14, R.color.card_stroke)
            setOnClickListener {
                haptic(this)
                onBack()
            }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) }
        })
        addView(text(title, 22f, bold = true).apply {
            isAccessibilityHeading = true
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    /** 紧凑语义状态胶囊，内置状态缓存避免轮询刷新时的重复绘制。 */
    fun badge(label: String = "", tone: Tone = Tone.NEUTRAL) = TextView(context).apply {
        textSize = 12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(4), dp(10), dp(4))
        layoutParams = LinearLayout.LayoutParams(-2, -2)
        styleBadge(this, label, tone)
    }

    fun styleBadge(view: TextView, label: String, tone: Tone) {
        val stateKey = "$label|${tone.name}"
        if (view.tag == stateKey) return
        view.tag = stateKey
        view.text = label
        view.setTextColor(color(tone.ink))
        view.background = round(tone.surface, 99)
    }

    /** Material 3 现代药丸开关。 */
    fun switch(checked: Boolean, description: String, stableId: Int? = null) = MaterialSwitch(context).apply {
        stableId?.let { id = it }
        contentDescription = description
        isSaveEnabled = false
        isChecked = checked
        minHeight = dp(48)
    }

    fun checkBox(stableId: Int, label: String, checked: Boolean) = MaterialCheckBox(context).apply {
        id = stableId
        text = label
        textSize = 15f
        setTextColor(color(R.color.ink))
        minHeight = dp(48)
        isChecked = checked
        isSaveEnabled = false
    }

    fun optionRadio(label: String, stableId: Int) = MaterialRadioButton(context).apply {
        id = stableId
        textSize = 15f
        setTextColor(color(R.color.ink))
        val split = label.indexOf('\n')
        text = SpannableString(label).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, if (split < 0) length else split, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (split >= 0) {
                setSpan(RelativeSizeSpan(.84f), split + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(color(R.color.muted)), split + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        minHeight = dp(56)
        isSaveEnabled = false
    }

    fun simpleRadio(label: String, stableId: Int) = MaterialRadioButton(context).apply {
        id = stableId
        text = label
        textSize = 15f
        setTextColor(color(R.color.ink))
        minHeight = dp(48)
        isSaveEnabled = false
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    /** Material 3 OutlinedBox 输入框：支持浮动标签、辅助说明、内联错误与密码明密文眼睛图标。 */
    fun textField(
        stableId: Int? = null,
        label: String,
        placeholder: String = "",
        value: String = "",
        helper: String? = null,
        singleLine: Boolean = true,
        password: Boolean = false
    ): Pair<TextInputLayout, TextInputEditText> {
        val layout = TextInputLayout(context).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat())
            boxBackgroundColor = color(R.color.field)
            boxStrokeColor = color(R.color.accent)
            boxStrokeWidth = dp(1)
            boxStrokeWidthFocused = dp(2)
            hint = label
            placeholderText = placeholder.ifEmpty { null }
            helperText = helper
            isHintEnabled = true
            isErrorEnabled = true
            if (password) {
                endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
                setEndIconTintList(colorList(R.color.muted))
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        val edit = TextInputEditText(layout.context).apply {
            stableId?.let { id = it }
            textSize = 15f
            setTextColor(color(R.color.ink))
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            inputType = when {
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                singleLine -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
            if (singleLine) setSingleLine(true) else { minLines = 2; maxLines = 5 }
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            isSaveEnabled = false
            setText(value)
        }
        layout.addView(edit, LinearLayout.LayoutParams(-1, -2))
        return layout to edit
    }

    fun gap(parent: LinearLayout, height: Int) {
        parent.addView(View(context), LinearLayout.LayoutParams(1, dp(height)))
    }

    fun divider(parent: LinearLayout, top: Int = 14, bottom: Int = 14) {
        parent.addView(
            View(context).apply { setBackgroundColor(color(R.color.line)) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(top); bottomMargin = dp(bottom) }
        )
    }

    /** Material 3 标准按钮（Filled / Tonal / Danger / Compact）。 */
    fun button(
        value: String,
        primary: Boolean = false,
        danger: Boolean = false,
        compact: Boolean = false,
        action: () -> Unit
    ) = MaterialButton(context).apply {
        text = value
        textSize = if (compact) 13f else 15f
        isAllCaps = false
        insetTop = 0
        insetBottom = 0
        cornerRadius = dp(if (compact) 12 else 14)
        elevation = 0f
        stateListAnimator = null
        val heightDp = if (compact) 44 else 48
        minHeight = dp(heightDp)
        minimumHeight = dp(heightDp)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(if (compact) 16 else 18), dp(8), dp(if (compact) 16 else 18), dp(8))
        val foreground = when {
            primary -> R.color.on_accent
            danger -> R.color.danger
            else -> R.color.accent
        }
        val fill = when {
            primary -> R.color.accent
            danger -> R.color.danger_surface
            else -> R.color.accent_surface
        }
        backgroundTintList = colorList(fill)
        setTextColor(color(foreground))
        rippleColor = colorList(R.color.ripple)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setOnClickListener {
            haptic(this)
            action()
        }
        layoutParams = LinearLayout.LayoutParams(if (compact) -2 else -1, -2)
    }

    /** 轻量文字按钮：保持 44dp 触控高度，不抢占主操作视觉权重。 */
    fun ghostButton(value: String, tint: Int = R.color.accent, action: () -> Unit) = TextView(context).apply {
        text = value
        textSize = 14f
        setTextColor(color(tint))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(44)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        background = ripple(R.color.surface, 10)
        setOnClickListener {
            haptic(this)
            action()
        }
        layoutParams = LinearLayout.LayoutParams(-2, -2)
    }

    fun dialog() = MaterialAlertDialogBuilder(context)

    /** Material 3 底部抽屉，适合试算、单选等高频轻交互。 */
    fun bottomSheet(title: String, subtitle: String? = null, buildContent: (LinearLayout, BottomSheetDialog, ScrollView) -> Unit): BottomSheetDialog {
        val dialog = BottomSheetDialog(context)
        val root = column().apply {
            background = round(R.color.surface, 24)
            addView(BottomSheetDragHandleView(context), LinearLayout.LayoutParams(-1, -2))
            addView(text(title, 20f, bold = true).apply {
                isAccessibilityHeading = true
                setPadding(dp(20), 0, dp(20), dp(if (subtitle == null) 12 else 4))
            })
            if (subtitle != null) {
                addView(text(subtitle, 13f, R.color.muted).apply {
                    setPadding(dp(20), 0, dp(20), dp(12))
                })
            }
        }
        val body = column().apply { setPadding(dp(20), dp(4), dp(20), dp(24)) }
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(body)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        buildContent(body, dialog, scroll)
        dialog.setContentView(root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        return dialog
    }

    fun enabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else .38f
    }
}

/** 通过 AppCompatDelegate 统一应用浅色/深色/跟随系统外观。 */
object AppAppearance {
    fun apply(context: Context) {
        val mode = context.getSharedPreferences("appearance", Context.MODE_PRIVATE).getString("mode", "system")
        val target = when (mode) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (AppCompatDelegate.getDefaultNightMode() != target) {
            AppCompatDelegate.setDefaultNightMode(target)
        }
    }
}
