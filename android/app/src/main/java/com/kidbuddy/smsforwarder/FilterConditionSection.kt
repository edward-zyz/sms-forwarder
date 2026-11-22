package com.kidbuddy.smsforwarder

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * 可复用的触发条件编辑组件（纯 Kotlin View）。
 * 包含：
 * 1) 顶层 3 选 1 匹配模式（只转验证码 / 按关键词转发 / 转发全部短信），内嵌平台 Chip 多选与简洁关键词输入框；
 * 2) 默认折叠的「高级选项」卡片，收纳排除关键词、允许发件号码、AND/OR 关系与验证码字样/4-6位数字细项。
 * 供 FilterActivity 与新建规则向导直接复用。
 */
class FilterConditionSection @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val ui = Ui(context)
    private var editor: FilterEditor = FilterEditor.forNewRule()
    private var onChangeListener: ((FilterEditor) -> Unit)? = null
    private var binding = false
    private var advancedExpanded = false

    private val sectionHeaderContainer: LinearLayout
    private val modeButtons: Map<FilterEditor.Mode, MaterialRadioButton>
    private val verificationPanel: LinearLayout
    private val keywordsPanel: LinearLayout
    private val platformChips = mutableMapOf<String, Chip>()

    private val keywordsLayout: TextInputLayout
    private val keywordsInput: TextInputEditText

    private val advancedCard: MaterialCardView
    private val advancedTitleView: TextView
    private val advancedSummaryView: TextView
    private val advancedChevron: ImageView
    private val advancedBody: LinearLayout

    private val excludeLayout: TextInputLayout
    private val excludeInput: TextInputEditText
    private val sendersLayout: TextInputLayout
    private val sendersInput: TextInputEditText
    private val matchRelationGroup: RadioGroup
    private val matchAnyRadio: MaterialRadioButton
    private val matchAllRadio: MaterialRadioButton
    private val codeWordCheck: MaterialCheckBox
    private val digitsCheck: MaterialCheckBox

    init {
        orientation = VERTICAL
        ui.enableMotion(this)

        sectionHeaderContainer = ui.column()
        ui.sectionTitle(sectionHeaderContainer, "匹配方式")
        addView(sectionHeaderContainer)

        val verificationBtn = ui.optionRadio(
            "只转验证码 · 推荐\n精准转发验证码与安全码，自动过滤常见营销短信",
            R.id.filter_preset_verification,
        ).apply {
            setOnClickListener { selectMode(FilterEditor.Mode.VERIFICATION) }
        }
        verificationPanel = buildVerificationPanel()

        val keywordsBtn = ui.optionRadio(
            "按关键词转发\n正文包含指定词时转发（默认任一匹配）",
            R.id.filter_preset_either,
        ).apply {
            setOnClickListener { selectMode(FilterEditor.Mode.KEYWORDS) }
        }

        val (kl, ki) = ui.textField(
            stableId = R.id.filter_include,
            label = "包含关键词",
            placeholder = "例如：取餐码, 快递, 告警",
            helper = "多个词可用逗号或换行分隔，满足任意一个即转发。",
            singleLine = false,
        )
        keywordsLayout = kl
        keywordsInput = ki
        keywordsPanel = ui.column().apply {
            setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6))
            addView(keywordsLayout)
        }

        val allBtn = ui.optionRadio(
            "转发全部短信\n收到的每一条新短信都直接转发",
            R.id.filter_preset_everything,
        ).apply {
            setOnClickListener { selectMode(FilterEditor.Mode.ALL) }
        }

        modeButtons = mapOf(
            FilterEditor.Mode.VERIFICATION to verificationBtn,
            FilterEditor.Mode.KEYWORDS to keywordsBtn,
            FilterEditor.Mode.ALL to allBtn,
        )

        val modeCard = ui.card(padH = 14, padV = 14) {
            ui.enableMotion(this)
            addView(verificationBtn)
            addView(verificationPanel)
            ui.gap(this, 6)
            addView(keywordsBtn)
            addView(keywordsPanel)
            ui.gap(this, 6)
            addView(allBtn)
        }
        addView(modeCard)

        // 高级选项卡片（默认折叠）
        advancedTitleView = ui.text("高级选项", 15f, bold = true)
        advancedSummaryView = ui.text("", 13f, R.color.muted)
        advancedChevron = ui.chevronDown(R.color.accent)

        val (el, ei) = ui.textField(
            stableId = R.id.filter_exclude,
            label = "排除关键词 · 选填",
            placeholder = "例如：银行, 营销（每行一个，或用逗号分隔）",
            helper = "只要包含任意一个排除词就不转发，优先级最高。",
            singleLine = false,
        )
        excludeLayout = el
        excludeInput = ei

        val (sl, si) = ui.textField(
            stableId = R.id.filter_senders,
            label = "允许的发件号码 · 选填",
            placeholder = "每行一个号码，也可用逗号分隔",
            helper = "填完整号码，或用 106* 表示以 106 开头；留空表示不限号码。",
            singleLine = false,
        )
        sendersLayout = sl
        sendersInput = si

        matchAnyRadio = ui.simpleRadio("满足其中一项即可（或）", R.id.filter_any)
        matchAllRadio = ui.simpleRadio("必须全部同时满足（且）", R.id.filter_all)
        matchRelationGroup = RadioGroup(context).apply {
            isSaveEnabled = false
            addView(matchAnyRadio)
            addView(matchAllRadio)
        }

        codeWordCheck = ui.checkBox(R.id.filter_code_word, "包含验证码字样（防营销短信误转）", false)
        digitsCheck = ui.checkBox(R.id.filter_digits, "包含连续 4–6 位数字", false)

        advancedBody = ui.column().apply {
            setPadding(ui.dp(18), ui.dp(2), ui.dp(18), ui.dp(16))
            ui.insetDivider(this, startDp = 0, endDp = 0, top = 0, bottom = 12)

            addView(excludeLayout)
            ui.gap(this, 10)
            addView(sendersLayout)
            addView(ui.ghostButton("号码怎么填写？") {
                ui.dialog().setTitle("号码填写说明")
                    .setMessage("• 完整号码：只匹配这个号码，不按尾号匹配。\n• 前缀通配：如 106* 匹配所有以 106 开头的号码；* 只能放末尾，不能单独填写。\n• 格式兼容：支持空格、横线、括号、全角数字，以及中国手机号 +86 / 0086 格式。")
                    .setPositiveButton("知道了", null)
                    .show()
            }.apply { minHeight = ui.dp(44) })

            ui.divider(this, top = 8, bottom = 12)
            addView(ui.text("匹配细项与组合关系", 13.5f, R.color.muted, bold = true))
            ui.gap(this, 6)
            addView(codeWordCheck)
            addView(ui.text("要求正文包含“验证码、动态码、校验码、安全码、确认码、登录码、code”之一。", 12f, R.color.subtle))
            ui.gap(this, 6)
            addView(digitsCheck)
            addView(ui.text("仅匹配完整数字串，7 位及以上数字（如长订单号）不计入。", 12f, R.color.subtle))
            ui.gap(this, 10)
            addView(ui.text("多项正文条件的关系", 13f, R.color.muted, bold = true))
            ui.gap(this, 4)
            addView(matchRelationGroup)
        }

        advancedCard = ui.listCard {
            ui.enableMotion(this)
            val header = ui.row().apply {
                minimumHeight = ui.dp(56)
                setPadding(ui.dp(18), ui.dp(13), ui.dp(18), ui.dp(13))
                background = ui.transparentRipple(0)
                addView(ui.column().apply {
                    addView(advancedTitleView)
                    ui.gap(this, 2)
                    addView(advancedSummaryView)
                }, LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) })
                addView(advancedChevron)
                setOnClickListener {
                    ui.haptic(this)
                    setAdvancedExpanded(!advancedExpanded)
                }
            }
            addView(header)
            addView(advancedBody)
        }
        addView(advancedCard)

        setupListeners()
        syncFromEditor(syncTextFields = true)
    }

    /** 控制是否显示顶部的「匹配方式」标题（供嵌入步骤向导时按需隐藏）。 */
    fun setShowSectionTitle(show: Boolean) {
        sectionHeaderContainer.visibility = if (show) View.VISIBLE else View.GONE
    }

    /** 绑定外部构造好的 FilterEditor，并刷新界面。 */
    fun bind(editor: FilterEditor, advancedExpanded: Boolean = this.advancedExpanded) {
        this.editor = editor
        this.advancedExpanded = advancedExpanded
        syncFromEditor(syncTextFields = true)
    }

    /** 便捷方法：直接按规则初始化并绑定编辑状态。 */
    fun setRules(
        saved: FilterRules,
        initial: FilterRules = saved,
        mode: FilterEditor.Mode = FilterEditor.identify(initial),
        selectedPlatforms: Set<String> = FilterEditor.initialPlatformSelection(initial, mode),
        advancedExpanded: Boolean = false,
    ) {
        bind(FilterEditor(saved, initial, mode, selectedPlatforms), advancedExpanded)
    }

    fun setOnChangeListener(listener: ((FilterEditor) -> Unit)?) {
        onChangeListener = listener
    }

    fun setAdvancedExpanded(expanded: Boolean) {
        if (advancedExpanded == expanded) return
        advancedExpanded = expanded
        renderState()
    }

    fun isAdvancedExpanded(): Boolean = advancedExpanded

    fun currentEditor(): FilterEditor = editor

    fun currentRules(): FilterRules = editor.rules

    fun validationError(): String? = editor.rules.validationError()

    fun saveInstanceState(): Bundle = Bundle().apply {
        putBundle("savedRules", writeRules(editor.saved))
        putBundle("draftRules", writeRules(editor.rules))
        putString("mode", editor.mode.name)
        putStringArrayList("selectedPlatforms", ArrayList(editor.selectedPlatforms))
        putBoolean("advancedExpanded", advancedExpanded)
        putString("keywordsRaw", keywordsInput.text?.toString().orEmpty())
        putString("excludeRaw", excludeInput.text?.toString().orEmpty())
        putString("sendersRaw", sendersInput.text?.toString().orEmpty())
    }

    fun restoreInstanceState(state: Bundle) {
        val saved = state.getBundle("savedRules")?.let(::readRules) ?: FilterRules()
        val draft = state.getBundle("draftRules")?.let(::readRules) ?: saved
        val mode = state.getString("mode")?.let { runCatching { FilterEditor.Mode.valueOf(it) }.getOrNull() }
            ?: FilterEditor.identify(draft)
        val platforms = state.getStringArrayList("selectedPlatforms")?.toSet()
            ?: FilterEditor.initialPlatformSelection(draft, mode)
        val expanded = state.getBoolean("advancedExpanded", false)
        bind(FilterEditor(saved, draft, mode, platforms), expanded)

        binding = true
        state.getString("keywordsRaw")?.let { keywordsInput.setText(it) }
        state.getString("excludeRaw")?.let { excludeInput.setText(it) }
        state.getString("sendersRaw")?.let { sendersInput.setText(it) }
        binding = false
        renderState()
    }

    private fun buildVerificationPanel(): LinearLayout = ui.column().apply {
        setPadding(ui.dp(6), ui.dp(8), ui.dp(6), ui.dp(6))
        addView(ui.text("适用平台 · 点选即生效", 13f, R.color.muted, bold = true))
        ui.gap(this, 2)
        addView(ui.text("默认转发全部验证码；点选商家平台后仅转发对应平台验证码，自动拦截营销短信。", 12f, R.color.subtle))
        ui.gap(this, 8)

        val generalGroup = createChipGroup()
        PlatformTemplates.inCategory(PlatformCategory.GENERAL).forEach { template ->
            val chip = createPlatformChip(template)
            platformChips[template.id] = chip
            generalGroup.addView(chip)
        }
        addView(generalGroup)

        listOf(PlatformCategory.LOCAL_LIFE, PlatformCategory.ECOMMERCE).forEach { category ->
            ui.gap(this, 10)
            addView(ui.text(category.displayName, 12f, R.color.muted, bold = true).apply {
                setPadding(ui.dp(2), 0, 0, ui.dp(4))
            })
            val group = createChipGroup()
            PlatformTemplates.inCategory(category).forEach { template ->
                val chip = createPlatformChip(template)
                platformChips[template.id] = chip
                group.addView(chip)
            }
            addView(group)
        }
    }

    private fun createChipGroup() = ChipGroup(context).apply {
        chipSpacingHorizontal = ui.dp(8)
        chipSpacingVertical = ui.dp(6)
        layoutParams = LayoutParams(-1, -2)
    }

    private fun createPlatformChip(template: PlatformTemplate): Chip {
        val checkedStates = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(),
        )
        val bgColors = ColorStateList(
            checkedStates,
            intArrayOf(ui.color(R.color.accent_surface), ui.color(R.color.field)),
        )
        val strokeColors = ColorStateList(
            checkedStates,
            intArrayOf(ui.color(R.color.accent), ui.color(R.color.line)),
        )
        val textColors = ColorStateList(
            checkedStates,
            intArrayOf(ui.color(R.color.accent), ui.color(R.color.ink)),
        )
        return Chip(context).apply {
            text = template.name
            textSize = 13f
            isCheckable = true
            isCheckedIconVisible = true
            checkedIconTint = ui.colorList(R.color.accent)
            chipBackgroundColor = bgColors
            chipStrokeColor = strokeColors
            chipStrokeWidth = ui.dp(1).toFloat()
            setTextColor(textColors)
            shapeAppearanceModel = shapeAppearanceModel.toBuilder()
                .setAllCornerSizes(ui.dp(12).toFloat())
                .build()
            chipMinHeight = ui.dp(40).toFloat()
            minHeight = ui.dp(44)
            setEnsureMinTouchTargetSize(true)
            isSaveEnabled = false
            setOnCheckedChangeListener { _, checked ->
                if (!binding) {
                    ui.haptic(this)
                    editor.togglePlatform(template.id, checked)
                    syncFromEditor(syncTextFields = true)
                    notifyChanged()
                }
            }
        }
    }

    private fun setupListeners() {
        keywordsInput.watch {
            editor.include(FilterEditor.parseList(it))
            renderState()
            notifyChanged()
        }
        excludeInput.watch {
            editor.exclude(FilterEditor.parseList(it))
            renderState()
            notifyChanged()
        }
        sendersInput.watch {
            editor.senders(FilterEditor.parseList(it))
            renderState()
            notifyChanged()
        }
        codeWordCheck.setOnCheckedChangeListener { _, checked ->
            if (!binding) {
                ui.haptic(codeWordCheck)
                editor.requireCodeWord(checked)
                renderState()
                notifyChanged()
            }
        }
        digitsCheck.setOnCheckedChangeListener { _, checked ->
            if (!binding) {
                ui.haptic(digitsCheck)
                editor.digits(checked)
                renderState()
                notifyChanged()
            }
        }
        matchRelationGroup.setOnCheckedChangeListener { _, checkedId ->
            if (!binding) {
                editor.matchAll(checkedId == R.id.filter_all)
                renderState()
                notifyChanged()
            }
        }
    }

    private fun selectMode(target: FilterEditor.Mode) {
        ui.haptic(this)
        if (editor.mode == target) {
            renderState()
            return
        }
        editor.select(target)
        syncFromEditor(syncTextFields = true)
        notifyChanged()
    }

    private fun syncFromEditor(syncTextFields: Boolean) {
        binding = true
        if (syncTextFields) {
            val includeText = editor.rules.includeKeywords.joinToString("\n")
            if (keywordsInput.text?.toString() != includeText) {
                keywordsInput.setText(includeText)
            }
            val excludeText = editor.rules.excludeKeywords.joinToString("\n")
            if (excludeInput.text?.toString() != excludeText) {
                excludeInput.setText(excludeText)
            }
            val sendersText = editor.rules.allowedSenders.joinToString("\n")
            if (sendersInput.text?.toString() != sendersText) {
                sendersInput.setText(sendersText)
            }
        }
        codeWordCheck.isChecked = editor.rules.requireCodeWord
        digitsCheck.isChecked = editor.rules.requireDigits
        matchRelationGroup.check(if (editor.rules.matchAll) R.id.filter_all else R.id.filter_any)
        platformChips.forEach { (id, chip) ->
            val checked = id in editor.selectedPlatforms
            if (chip.isChecked != checked) chip.isChecked = checked
        }
        binding = false
        renderState()
    }

    private fun renderState() {
        val mode = editor.mode
        binding = true
        modeButtons.forEach { (choice, button) ->
            val selected = choice == mode
            if (button.isChecked != selected) button.isChecked = selected
            button.background = if (selected) {
                ui.round(R.color.accent_surface, 12, R.color.accent)
            } else {
                ui.round(R.color.field, 12, R.color.line)
            }
        }
        platformChips.forEach { (id, chip) ->
            val checked = id in editor.selectedPlatforms
            if (chip.isChecked != checked) chip.isChecked = checked
        }
        binding = false

        verificationPanel.visibility = if (mode == FilterEditor.Mode.VERIFICATION) View.VISIBLE else View.GONE
        keywordsPanel.visibility = if (mode == FilterEditor.Mode.KEYWORDS) View.VISIBLE else View.GONE

        excludeLayout.helperText = if (mode == FilterEditor.Mode.VERIFICATION) {
            "只要包含任意排除词就不转发；验证码模式默认内置退订、优惠、银行等常见防营销词，可自由增删。"
        } else {
            "只要包含任意一个排除词就不转发，优先级最高。"
        }

        val adv = editor.advancedSummary()
        val showAdvancedCard = mode != FilterEditor.Mode.ALL || adv.count > 0
        advancedCard.visibility = if (showAdvancedCard) View.VISIBLE else View.GONE
        advancedBody.visibility = if (advancedExpanded) View.VISIBLE else View.GONE
        ui.rotateChevron(advancedChevron, advancedExpanded)

        advancedTitleView.text = when {
            adv.count == 0 -> "高级选项"
            mode == FilterEditor.Mode.ALL -> "高级选项 · 已保留 ${adv.count} 项"
            else -> "高级选项 · 已配置 ${adv.count} 项"
        }
        advancedSummaryView.text = when {
            adv.count > 0 && mode == FilterEditor.Mode.ALL ->
                "当前为转发全部，已保留：${adv.configuredLabels.joinToString("、")}（切回筛选后生效）"
            adv.count > 0 ->
                "已配置：${adv.configuredLabels.joinToString("、")}"
            mode == FilterEditor.Mode.VERIFICATION ->
                "排除关键词（已含默认防营销词）、发件号码与匹配细项"
            else ->
                "排除关键词、允许发件号码与匹配细项"
        }
        advancedSummaryView.setTextColor(
            ui.color(
                when {
                    adv.count > 0 && mode == FilterEditor.Mode.ALL -> R.color.warning
                    adv.count > 0 -> R.color.accent
                    else -> R.color.muted
                },
            ),
        )
    }

    private fun notifyChanged() {
        onChangeListener?.invoke(editor)
    }

    private fun EditText.watch(changed: (String) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!binding) changed(s.toString())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    companion object {
        fun writeRules(rules: FilterRules): Bundle = Bundle().apply {
            putBoolean("enabled", rules.enabled)
            putBoolean("digits", rules.requireDigits)
            putBoolean("all", rules.matchAll)
            putBoolean("codeWord", rules.requireCodeWord)
            putStringArrayList("include", ArrayList(rules.includeKeywords))
            putStringArrayList("exclude", ArrayList(rules.excludeKeywords))
            putStringArrayList("senders", ArrayList(rules.allowedSenders))
        }

        fun readRules(bundle: Bundle): FilterRules = FilterRules(
            enabled = bundle.getBoolean("enabled"),
            includeKeywords = bundle.getStringArrayList("include").orEmpty(),
            requireDigits = bundle.getBoolean("digits"),
            matchAll = bundle.getBoolean("all"),
            excludeKeywords = bundle.getStringArrayList("exclude").orEmpty(),
            allowedSenders = bundle.getStringArrayList("senders").orEmpty(),
            requireCodeWord = bundle.getBoolean("codeWord", false),
        )
    }
}
