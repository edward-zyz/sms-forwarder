package com.kidbuddy.smsforwarder

import android.animation.LayoutTransition
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
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
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDragHandleView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** 极简紧致的 MIUI / HyperOS 风格组件工厂（基于 Material 3 底座）。 */
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
            setDuration(180L)
            enableTransitionType(LayoutTransition.CHANGING)
        }
    }

    fun haptic(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    fun round(fill: Int, radius: Int = 16, border: Int? = null, strokeWidthDp: Int = 1) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radius).toFloat()
        setColor(color(fill))
        border?.let { setStroke(dp(strokeWidthDp), color(it)) }
    }

    fun ripple(fill: Int = R.color.surface, radius: Int = 12, border: Int? = null) =
        RippleDrawable(colorList(R.color.ripple), round(fill, radius, border), null)

    fun transparentRipple(radius: Int = 12) = RippleDrawable(
        colorList(R.color.ripple),
        null,
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radius).toFloat()
            setColor(Color.WHITE)
        }
    )

    fun text(value: String, size: Float = 15f, tint: Int = R.color.ink, bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color(tint))
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(dp(2).toFloat(), 1f)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /** 设置项右侧的纯净值文本（替代花哨彩色 tag）。 */
    fun valueText(value: String = "", tint: Int = R.color.subtle) = TextView(context).apply {
        text = value
        textSize = 13.5f
        setTextColor(color(tint))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun setValueText(view: TextView, value: String, tint: Int = R.color.subtle, bold: Boolean = false) {
        val stateKey = "$value|$tint|$bold"
        if (view.tag == stateKey) return
        view.tag = stateKey
        view.text = value
        view.setTextColor(color(tint))
        view.typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }

    /** 精致状态指示小圆点（8dp），克制清晰。 */
    fun statusDot(sizeDp: Int = 8) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { marginEnd = dp(8) }
    }

    fun styleDot(view: View, colorRes: Int) {
        if (view.tag == colorRes) return
        view.tag = colorRes
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color(colorRes))
        }
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

    /** HyperOS / iOS 设置风圆角彩色图标底块：将色彩克制收敛在左侧整齐的图标块内。 */
    fun iconTile(
        iconRes: Int,
        bgColorRes: Int,
        sizeDp: Int = 32,
        iconSizeDp: Int = 18,
        radiusDp: Int = 9
    ): LinearLayout = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        background = round(bgColorRes, radiusDp)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { marginEnd = dp(12) }
        addView(icon(iconRes, iconSizeDp, R.color.on_accent))
    }

    fun styleIconTile(tile: LinearLayout, bgColorRes: Int, radiusDp: Int = 11) {
        if (tile.tag == bgColorRes) return
        tile.tag = bgColorRes
        tile.background = round(bgColorRes, radiusDp)
    }

    fun chevronRight(tint: Int = R.color.subtle) = icon(R.drawable.ic_chevron_right, 16, tint).apply {
        layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(4) }
    }

    fun chevronDown(tint: Int = R.color.subtle) = icon(R.drawable.ic_chevron_down, 16, tint).apply {
        layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(4) }
    }

    fun rotateChevron(view: ImageView, expanded: Boolean) {
        val target = if (expanded) 180f else 0f
        if (view.rotation == target) return
        if (motionEnabled()) view.animate().rotation(target).setDuration(180L).start()
        else view.rotation = target
    }

    /** HyperOS / Apple 设置风无描边纯白大圆角卡片，舒展精致有呼吸感。 */
    fun card(
        fill: Int = R.color.surface,
        border: Int? = null,
        padH: Int = 18,
        padV: Int = 16,
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

    /** 专为可点击分组列表打造的 0 内边距纯白圆角卡，确保 cell 按压水波纹从左到右 100% 铺满并自然贴合卡片圆角。 */
    fun listCard(bottomMargin: Int = 14, block: LinearLayout.() -> Unit = {}): MaterialCardView =
        card(fill = R.color.surface, border = null, padH = 0, padV = 0, bottomMargin = bottomMargin, block = block)

    fun styleCard(card: MaterialCardView, fill: Int, border: Int? = null) {
        card.setCardBackgroundColor(color(fill))
        card.strokeWidth = if (border != null) dp(1) else 0
        border?.let { card.strokeColor = color(it) }
    }

    fun sectionTitle(parent: LinearLayout, title: String, subtitle: String? = null) {
        parent.addView(text(title, 13f, R.color.subtle, bold = false).apply {
            isAccessibilityHeading = true
            setPadding(dp(8), dp(8), dp(8), dp(if (subtitle == null) 8 else 2))
        })
        if (subtitle != null) {
            parent.addView(text(subtitle, 12.5f, R.color.subtle).apply {
                setPadding(dp(8), 0, dp(8), dp(8))
            })
        }
    }

    /** HyperOS 风格大标题顶栏：顶部无框返回按钮 + 舒展 26sp 大标题。 */
    fun topBar(title: String, onBack: () -> Unit) = column().apply {
        setPadding(0, 0, 0, dp(6))
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_back)
            imageTintList = colorList(R.color.ink)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "返回"
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = transparentRipple(99)
            setOnClickListener {
                haptic(this)
                onBack()
            }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                marginStart = -dp(12)
            }
        })
        addView(text(title, 26f, bold = true).apply {
            isAccessibilityHeading = true
            setPadding(dp(2), dp(2), 0, dp(4))
        })
    }

    /** 克制标签：默认中性灰底，仅在极少数必要场景使用。 */
    fun badge(label: String = "", tone: Tone = Tone.NEUTRAL) = TextView(context).apply {
        textSize = 11.5f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(3), dp(8), dp(3))
        layoutParams = LinearLayout.LayoutParams(-2, -2)
        styleBadge(this, label, tone)
    }

    fun styleBadge(view: TextView, label: String, tone: Tone) {
        val stateKey = "$label|${tone.name}"
        if (view.tag == stateKey) return
        view.tag = stateKey
        view.text = label
        view.setTextColor(color(tone.ink))
        view.background = round(tone.surface, 8)
    }

    /** 精致 MIUI 风格圆润开关（纯色轨道 + 纯白圆形滑块，无笨重外框）。 */
    fun switch(checked: Boolean, description: String, stableId: Int? = null) = SwitchCompat(context).apply {
        stableId?.let { id = it }
        contentDescription = description
        isSaveEnabled = false
        showText = false
        setTrackResource(R.drawable.switch_track)
        setThumbResource(R.drawable.switch_thumb)
        trackTintList = null
        thumbTintList = null
        minHeight = dp(44)
        isChecked = checked
    }

    fun checkBox(stableId: Int, label: String, checked: Boolean) = MaterialCheckBox(context).apply {
        id = stableId
        text = label
        textSize = 15f
        setTextColor(color(R.color.ink))
        minHeight = dp(46)
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
        setPadding(dp(8), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
        minHeight = dp(52)
        isSaveEnabled = false
    }

    fun simpleRadio(label: String, stableId: Int) = MaterialRadioButton(context).apply {
        id = stableId
        text = label
        textSize = 15f
        setTextColor(color(R.color.ink))
        minHeight = dp(44)
        isSaveEnabled = false
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    /** Material 3 OutlinedBox 输入框：紧致圆角 + 浮动标签 + 内置明密文切换图标。 */
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
            textSize = 14.5f
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

    /** 发丝级极细分割线（1px 物理像素）。 */
    fun divider(parent: LinearLayout, top: Int = 10, bottom: Int = 10) {
        parent.addView(
            View(context).apply { setBackgroundColor(color(R.color.line)) },
            LinearLayout.LayoutParams(-1, 1).apply { topMargin = dp(top); bottomMargin = dp(bottom) }
        )
    }

    /** 避开左侧图标底块的发丝级内缩分割线（HyperOS / iOS 设置列表灵魂细节）。 */
    fun insetDivider(parent: LinearLayout, startDp: Int = 62, endDp: Int = 18, top: Int = 0, bottom: Int = 0) {
        parent.addView(
            View(context).apply { setBackgroundColor(color(R.color.line)) },
            LinearLayout.LayoutParams(-1, 1).apply {
                marginStart = dp(startDp)
                marginEnd = dp(endDp)
                topMargin = dp(top)
                bottomMargin = dp(bottom)
            }
        )
    }

    /** MIUI 风格按钮：主按钮用纯净系统蓝，次按钮用中性浅灰底+黑字（告别满屏淡蓝）。 */
    fun button(
        value: String,
        primary: Boolean = false,
        danger: Boolean = false,
        compact: Boolean = false,
        action: () -> Unit
    ) = MaterialButton(context).apply {
        text = value
        textSize = if (compact) 13f else 14.5f
        isAllCaps = false
        insetTop = 0
        insetBottom = 0
        cornerRadius = dp(if (compact) 10 else 13)
        elevation = 0f
        stateListAnimator = null
        val heightDp = if (compact) 40 else 46
        minHeight = dp(heightDp)
        minimumHeight = dp(heightDp)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(if (compact) 14 else 16), dp(6), dp(if (compact) 14 else 16), dp(6))
        val foreground = when {
            primary -> R.color.on_accent
            danger -> R.color.danger
            else -> R.color.ink
        }
        val fill = when {
            primary -> R.color.accent
            danger -> R.color.danger_surface
            else -> R.color.neutral_surface
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

    /** 轻量文字按钮。 */
    fun ghostButton(value: String, tint: Int = R.color.muted, action: () -> Unit) = TextView(context).apply {
        text = value
        textSize = 13.5f
        setTextColor(color(tint))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(40)
        setPadding(dp(8), dp(6), dp(8), dp(6))
        background = transparentRipple(8)
        setOnClickListener {
            haptic(this)
            action()
        }
        layoutParams = LinearLayout.LayoutParams(-2, -2)
    }

    fun dialog() = MaterialAlertDialogBuilder(context)

    fun bottomSheet(title: String, subtitle: String? = null, buildContent: (LinearLayout, BottomSheetDialog, ScrollView) -> Unit): BottomSheetDialog {
        val dialog = BottomSheetDialog(context)
        val root = column().apply {
            background = round(R.color.surface, 22)
            addView(BottomSheetDragHandleView(context), LinearLayout.LayoutParams(-1, -2))
            addView(text(title, 19f, bold = true).apply {
                isAccessibilityHeading = true
                setPadding(dp(18), 0, dp(18), dp(if (subtitle == null) 10 else 4))
            })
            if (subtitle != null) {
                addView(text(subtitle, 13f, R.color.muted).apply {
                    setPadding(dp(18), 0, dp(18), dp(10))
                })
            }
        }
        val body = column().apply { setPadding(dp(18), dp(4), dp(18), dp(22)) }
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
