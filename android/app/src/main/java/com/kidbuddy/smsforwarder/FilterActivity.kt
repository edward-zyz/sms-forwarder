package com.kidbuddy.smsforwarder

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** 新建或编辑单条转发规则；表单只改内存草稿，保存才生效，本地试算不联网、不入队。 */
class FilterActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_RULE_ID = "rule_id"
    }

    private lateinit var ui: Ui
    private lateinit var editor: FilterEditor
    private lateinit var ruleNameLayout: TextInputLayout
    private lateinit var ruleNameInput: TextInputEditText
    private lateinit var ruleEnabledSwitch: SwitchCompat
    private lateinit var destinationLabel: TextView
    private lateinit var include: TextInputEditText
    private lateinit var codeWord: MaterialCheckBox
    private lateinit var digits: MaterialCheckBox
    private lateinit var exclude: TextInputEditText
    private lateinit var senders: TextInputEditText
    private lateinit var all: MaterialRadioButton
    private lateinit var any: MaterialRadioButton
    private lateinit var presetGroup: RadioGroup
    private lateinit var presetButtons: List<Pair<FilterEditor.Intent, MaterialRadioButton>>
    private val templateCheckBoxes = mutableMapOf<String, MaterialCheckBox>()
    private lateinit var custom: MaterialCardView
    private lateinit var combination: LinearLayout
    private lateinit var optional: LinearLayout
    private lateinit var excludeFields: LinearLayout
    private lateinit var senderFields: LinearLayout
    private lateinit var excludeSummary: TextView
    private lateinit var senderSummary: TextView
    private lateinit var excludeChevron: ImageView
    private lateinit var senderChevron: ImageView
    private lateinit var previewBadge: TextView
    private lateinit var summary: TextView
    private lateinit var dirty: TextView
    private lateinit var errorBox: TextView
    private lateinit var save: MaterialButton

    private var editingRuleId: String? = null
    private var savedName = ""
    private var savedEnabled = true
    private var savedDestId = ""
    private var draftName = ""
    private var draftEnabled = true
    private var draftDestId = ""
    private var destinations: List<Destination> = emptyList()

    private var binding = true
    private var invalidSaved = false
    private var excludeOpen = false
    private var senderOpen = false
    private var selectedTemplates: Set<String> = emptySet()
    private var testOpen = false
    private var testSender = ""
    private var testBody = ""
    private var testResult = ""
    private var testRules: FilterRules? = null
    private var abandonDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        AppAppearance.apply(this)
        super.onCreate(savedInstanceState)
        ui = Ui(this)

        val configResult = runCatching { ConfigStore.load(this) }
        val config = configResult.getOrDefault(ForwardConfig())
        destinations = config.destinations
        editingRuleId = intent.getStringExtra(EXTRA_RULE_ID)?.takeIf { it.isNotBlank() }
        val existing = editingRuleId?.let { id -> config.rules.firstOrNull { it.id == id } }

        val defaultDestId = destinations.firstOrNull()?.id.orEmpty()
        val defaultName = existing?.name ?: "规则 ${config.rules.size + 1}"
        savedName = savedInstanceState?.getString("savedName") ?: (existing?.name ?: defaultName)
        savedEnabled = savedInstanceState?.getBoolean("savedEnabled") ?: (existing?.enabled ?: true)
        savedDestId = savedInstanceState?.getString("savedDestId") ?: (existing?.destinationId ?: defaultDestId)

        draftName = savedInstanceState?.getString("draftName") ?: savedName
        draftEnabled = savedInstanceState?.getBoolean("draftEnabled") ?: savedEnabled
        draftDestId = savedInstanceState?.getString("draftDestId") ?: savedDestId

        val savedFilter = savedInstanceState?.getBundle("savedRules")?.let(::readRules) ?: (existing?.filter ?: FilterRules())
        val draftFilter = savedInstanceState?.getBundle("draftRules")?.let(::readRules) ?: savedFilter
        val filterIntent = savedInstanceState?.getString("intent")?.let { runCatching { FilterEditor.Intent.valueOf(it) }.getOrNull() }
            ?: FilterEditor.identify(draftFilter)
        editor = FilterEditor(savedFilter, draftFilter, filterIntent)
        invalidSaved = savedInstanceState?.getBoolean("invalidSaved") ?: configResult.isFailure
        excludeOpen = savedInstanceState?.getBoolean("excludeOpen") ?: false
        senderOpen = savedInstanceState?.getBoolean("senderOpen") ?: false
        selectedTemplates = savedInstanceState?.getStringArrayList("selectedTemplates")?.toSet()
            ?: PlatformTemplates.detectSelectedIds(draftFilter)
        testSender = savedInstanceState?.getString("testSender").orEmpty()
        testBody = savedInstanceState?.getString("testBody").orEmpty()
        testResult = savedInstanceState?.getString("testResult").orEmpty()
        testRules = savedInstanceState?.getBundle("testRules")?.let(::readRules)

        val root = ui.column().apply { setBackgroundColor(ui.color(R.color.page)) }
        val content = ui.column().apply { setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(20)) }
        ui.enableMotion(content)
        val scroll = ScrollView(this).apply {
            id = R.id.filter_scroll
            isFillViewport = true
            addView(content)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // 底部固定操作栏
        root.addView(View(this).apply { setBackgroundColor(ui.color(R.color.line)) }, LinearLayout.LayoutParams(-1, ui.dp(1)))
        val footer = ui.column().apply {
            setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(14))
            setBackgroundColor(ui.color(R.color.surface))
        }
        dirty = ui.text("", 12f, R.color.subtle).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        footer.addView(dirty)
        ui.gap(footer, 8)
        val actions = ui.row()
        actions.addView(ui.button("试一条短信") { showTest() }.apply {
            id = R.id.filter_test_button
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) }
        })
        save = ui.button("保存规则", primary = true) { saveRules() }.apply {
            id = R.id.filter_save_button
            layoutParams = LinearLayout.LayoutParams(0, -2, 1.35f)
        }
        actions.addView(save)
        footer.addView(actions)
        root.addView(footer)
        setContentView(root)

        // 标准顶栏
        content.addView(ui.topBar(if (existing == null) "新建转发规则" else "编辑转发规则") { requestExit() })
        content.addView(ui.text("保存后仅影响新收到的短信，已有待发消息不变。", 13f, R.color.muted))
        ui.gap(content, 14)

        // 错误提示块
        errorBox = ui.text(
            if (invalidSaved) "已保存配置异常，请重新检查并保存。" else "",
            13f, R.color.danger, bold = true
        ).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            background = ui.round(R.color.danger_surface, 12)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(14) }
        }
        content.addView(errorBox)

        // 第一层：规则基本信息
        ui.sectionTitle(content, "基本信息")
        content.addView(ui.card(padH = 16, padV = 16) {
            val (nl, ni) = ui.textField(
                stableId = R.id.rule_name_input,
                label = "规则名称 *",
                placeholder = "例如：验证码转发、告警通知",
                value = draftName,
                singleLine = true
            )
            ruleNameLayout = nl
            ruleNameInput = ni
            addView(ruleNameLayout)

            ui.divider(this, top = 8, bottom = 10)

            destinationLabel = ui.text("", 14f, R.color.ink, bold = true)
            addView(ui.row().apply {
                minimumHeight = ui.dp(52)
                background = ui.ripple(R.color.surface, 10)
                addView(ui.column().apply {
                    addView(ui.fieldLabel("转发目的地", required = true))
                    ui.gap(this, 2)
                    addView(destinationLabel)
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) })
                addView(ui.text("选择", 14f, R.color.accent, bold = true))
                addView(ui.chevronRight(R.color.accent))
                setOnClickListener { ui.haptic(this); pickDestination() }
            })

            ui.divider(this, top = 10, bottom = 10)

            addView(ui.row().apply {
                addView(ui.column().apply {
                    addView(ui.text("启用此规则", 15f, bold = true))
                    addView(ui.text("关闭后暂不生效，规则配置会保留", 12f, R.color.subtle))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                ruleEnabledSwitch = ui.switch(draftEnabled, "启用此转发规则", R.id.rule_enabled_switch).apply {
                    setOnCheckedChangeListener { _, checked ->
                        if (!binding) {
                            ui.haptic(this)
                            draftEnabled = checked
                            render()
                        }
                    }
                }
                addView(ruleEnabledSwitch)
            })
        })

        // 第二层：实时规则效果预览卡（纯白大圆角 + 克制状态文字，无蓝底胶囊）
        content.addView(ui.card(padH = 18, padV = 15) {
            addView(ui.row().apply {
                addView(View(this@FilterActivity).apply {
                    background = ui.round(R.color.accent, 99)
                    layoutParams = LinearLayout.LayoutParams(ui.dp(3), ui.dp(14)).apply { marginEnd = ui.dp(8) }
                })
                addView(ui.text("规则效果预览", 14f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                previewBadge = ui.valueText()
                addView(previewBadge)
            })
            ui.gap(this, 8)
            summary = ui.text("", 14f, R.color.muted).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
            addView(summary)
        })

        // 第三层：转发方式
        ui.sectionTitle(content, "匹配方式")
        content.addView(ui.card(padH = 14, padV = 14) {
            presetGroup = RadioGroup(this@FilterActivity).apply { isSaveEnabled = false }
            val options = listOf(
                Triple(FilterEditor.Intent.EVERYTHING, R.id.filter_preset_everything, "全部短信\n收到任何新短信都直接转发"),
                Triple(FilterEditor.Intent.VERIFICATION, R.id.filter_preset_verification, "仅验证码短信 · 推荐\n同时包含“验证码”和 4–6 位数字"),
                Triple(FilterEditor.Intent.VERIFICATION_OR_DIGITS, R.id.filter_preset_either, "验证码字样或 4–6 位数字\n满足其一即转发，订单号也可能命中"),
                Triple(FilterEditor.Intent.CUSTOM, R.id.filter_preset_custom, "自定义规则\n自由设置关键词与数字匹配条件")
            )
            presetButtons = options.map { (choice, stableId, title) ->
                val btn = ui.optionRadio(title, stableId)
                presetGroup.addView(btn)
                choice to btn
            }
            presetGroup.check(options.first { it.first == editor.intent }.second)
            addView(presetGroup)
            presetGroup.setOnCheckedChangeListener { _, checked ->
                if (!binding) {
                    editor.select(options.first { it.second == checked }.first)
                    selectedTemplates = PlatformTemplates.detectSelectedIds(editor.rules)
                    bindBodyFields()
                    render()
                }
            }
        })

        // 第三层补充：常用商家平台模板（直接多选，自动启用三段防误转 + 默认安全排除词）
        ui.sectionTitle(
            content,
            "常用平台模板 · 直接勾选",
            "勾选后自动合并平台关键词、启用验证码字样与 4–6 位数字并填入默认安全排除词，下方仍可自由微调"
        )
        content.addView(ui.card(padH = 14, padV = 14) {
            PlatformCategory.entries.forEachIndexed { catIdx, category ->
                if (catIdx > 0) ui.gap(this, 10)
                addView(ui.text(category.displayName, 12f, R.color.muted, bold = true).apply {
                    setPadding(ui.dp(2), 0, 0, ui.dp(6))
                })
                val items = PlatformTemplates.inCategory(category)
                items.chunked(2).forEach { pair ->
                    val row = ui.row().apply {
                        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) }
                    }
                    pair.forEachIndexed { idx, template ->
                        val cb = MaterialCheckBox(this@FilterActivity).apply {
                            text = if (template.isAllVerification) "${template.name}（不限平台，仅验证三段防误转）" else template.name
                            textSize = 13.5f
                            setTextColor(ui.color(R.color.ink))
                            minHeight = ui.dp(46)
                            setPadding(ui.dp(4), ui.dp(6), ui.dp(10), ui.dp(6))
                            isSaveEnabled = false
                            isChecked = template.id in selectedTemplates
                            setOnCheckedChangeListener { _, checked ->
                                if (!binding) {
                                    ui.haptic(this)
                                    selectedTemplates = PlatformTemplates.toggleSelection(selectedTemplates, template.id, checked)
                                    editor.applyPlatformTemplates(selectedTemplates)
                                    bindBodyFields(syncExclude = true)
                                    render()
                                }
                            }
                        }
                        templateCheckBoxes[template.id] = cb
                        val lp = LinearLayout.LayoutParams(0, -2, 1f).apply {
                            if (pair.size == 2 && idx == 0) marginEnd = ui.dp(8)
                        }
                        row.addView(cb, lp)
                    }
                    if (pair.size == 1 && !pair.first().isAllVerification) {
                        row.addView(View(this@FilterActivity), LinearLayout.LayoutParams(0, 1, 1f))
                    }
                    addView(row)
                }
            }
        })

        // 第四层：自定义正文条件
        custom = ui.card(padH = 16, padV = 16) {
            addView(ui.text("正文匹配条件", 16f, bold = true))
            ui.gap(this, 10)
            val (includeLayout, includeInput) = ui.textField(
                stableId = R.id.filter_include,
                label = "包含以下任意关键词 · 选填",
                placeholder = "例如：美团, 开店宝, 验证码（每行一个，或用逗号分隔）",
                value = draftFilter.includeKeywords.joinToString("\n"),
                singleLine = false
            )
            include = includeInput
            addView(includeLayout)
            ui.gap(this, 4)
            codeWord = ui.checkBox(R.id.filter_code_word, "包含验证码字样（防营销短信误转）", draftFilter.requireCodeWord)
            addView(codeWord)
            addView(ui.text("要求正文包含“验证码、动态码、校验码、安全码、确认码、登录码、code”之一。", 12f, R.color.subtle))
            ui.gap(this, 4)
            digits = ui.checkBox(R.id.filter_digits, "包含连续 4–6 位数字", draftFilter.requireDigits)
            addView(digits)
            addView(ui.text("仅匹配完整数字串，7 位及以上数字（如长订单号）不计入。", 12f, R.color.subtle))
            combination = ui.column().apply {
                ui.divider(this, top = 12, bottom = 10)
                addView(ui.text("多项正文条件的关系", 13f, R.color.muted, bold = true))
                ui.gap(this, 4)
                val group = RadioGroup(this@FilterActivity).apply { isSaveEnabled = false }
                any = ui.simpleRadio("满足其中一项即可", R.id.filter_any)
                all = ui.simpleRadio("必须全部同时满足", R.id.filter_all)
                group.addView(any); group.addView(all)
                group.check(if (draftFilter.matchAll) R.id.filter_all else R.id.filter_any)
                group.setOnCheckedChangeListener { _, selected ->
                    if (!binding) {
                        editor.matchAll(selected == R.id.filter_all)
                        selectedTemplates = PlatformTemplates.detectSelectedIds(editor.rules)
                        render()
                    }
                }
                addView(group)
            }
            addView(combination)
        }
        content.addView(custom)

        // 第五层：附加限制（0 内边距卡片 + cell 满宽按压态）
        optional = ui.column()
        ui.sectionTitle(optional, "附加限制 · 选填", "在上面条件基础上进一步缩小范围")
        optional.addView(ui.listCard {
            ui.enableMotion(this)
            excludeSummary = ui.text("", 13f, R.color.muted)
            excludeChevron = ui.chevronDown(R.color.accent)
            addView(collapsibleHeader("排除包含这些词的短信", excludeSummary, excludeChevron) {
                excludeOpen = !excludeOpen
                render()
            })
            excludeFields = ui.column().apply {
                setPadding(ui.dp(18), 0, ui.dp(18), ui.dp(14))
                val (el, ei) = ui.textField(
                    stableId = R.id.filter_exclude,
                    label = "排除关键词",
                    placeholder = "例如：银行, 营销（每行一个，或用逗号分隔）",
                    value = draftFilter.excludeKeywords.joinToString("\n"),
                    helper = "只要包含任意一个排除词就不转发，优先级最高。",
                    singleLine = false
                )
                exclude = ei
                addView(el)
            }
            addView(excludeFields)

            ui.insetDivider(this, startDp = 18, endDp = 18)

            senderSummary = ui.text("", 13f, R.color.muted)
            senderChevron = ui.chevronDown(R.color.accent)
            addView(collapsibleHeader("只转发这些号码发来的短信", senderSummary, senderChevron) {
                senderOpen = !senderOpen
                render()
            })
            senderFields = ui.column().apply {
                setPadding(ui.dp(18), 0, ui.dp(18), ui.dp(14))
                val (sl, si) = ui.textField(
                    stableId = R.id.filter_senders,
                    label = "允许的发件号码",
                    placeholder = "每行一个号码，也可用逗号分隔",
                    value = draftFilter.allowedSenders.joinToString("\n"),
                    helper = "填完整号码，或用 106* 表示以 106 开头；留空表示不限号码。",
                    singleLine = false
                )
                senders = si
                addView(sl)
                addView(ui.ghostButton("号码怎么填写？") {
                    ui.dialog().setTitle("号码填写说明")
                        .setMessage("• 完整号码：只匹配这个号码，不按尾号匹配。\n• 前缀通配：如 106* 匹配所有以 106 开头的号码；* 只能放末尾，不能单独填写。\n• 格式兼容：支持空格、横线、括号、全角数字，以及中国手机号 +86 / 0086 格式。")
                        .setPositiveButton("知道了", null).show()
                })
            }
            addView(senderFields)
        })
        content.addView(optional)

        savedInstanceState?.getString("includeRaw")?.let { include.setText(it) }
        savedInstanceState?.getString("excludeRaw")?.let { exclude.setText(it) }
        savedInstanceState?.getString("sendersRaw")?.let { senders.setText(it) }
        ruleNameInput.watch { draftName = it; ruleNameLayout.error = null; render() }
        include.watch {
            editor.include(FilterEditor.parseList(it))
            selectedTemplates = PlatformTemplates.detectSelectedIds(editor.rules)
            render()
        }
        exclude.watch { editor.exclude(FilterEditor.parseList(it)); render() }
        senders.watch { editor.senders(FilterEditor.parseList(it)); render() }
        codeWord.setOnCheckedChangeListener { _, checked ->
            if (!binding) {
                editor.requireCodeWord(checked)
                selectedTemplates = PlatformTemplates.detectSelectedIds(editor.rules)
                render()
            }
        }
        digits.setOnCheckedChangeListener { _, checked ->
            if (!binding) {
                editor.digits(checked)
                selectedTemplates = PlatformTemplates.detectSelectedIds(editor.rules)
                render()
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { requestExit() }
        })
        binding = false
        render()
        if (savedInstanceState?.getBoolean("testOpen") == true) showTest()
    }

    override fun onResume() {
        super.onResume()
        destinations = runCatching { ConfigStore.load(this).destinations }.getOrDefault(destinations)
        if (draftDestId.isBlank() && destinations.isNotEmpty()) {
            draftDestId = destinations.first().id
        }
        render()
    }

    private fun pickDestination() {
        if (destinations.isEmpty()) {
            ui.dialog()
                .setTitle("还没有转发目的地")
                .setMessage("请先添加一个转发目的地（飞书、企业微信、钉钉或自定义 Webhook）。")
                .setPositiveButton("去添加") { _, _ ->
                    startActivity(Intent(this, DestinationEditActivity::class.java))
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        val labels = destinations.map { "${it.name}（${it.type.displayName}）" }.toTypedArray()
        val currentIdx = destinations.indexOfFirst { it.id == draftDestId }.coerceAtLeast(0)
        ui.dialog()
            .setTitle("选择转发目的地")
            .setSingleChoiceItems(labels, currentIdx) { dialog, which ->
                draftDestId = destinations[which].id
                dialog.dismiss()
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun isRuleDirty(): Boolean =
        editingRuleId == null ||
            editor.isDirty ||
            draftName.trim() != savedName.trim() ||
            draftEnabled != savedEnabled ||
            draftDestId != savedDestId

    private fun collapsibleHeader(title: String, summaryView: TextView, chevronView: ImageView, onClick: () -> Unit) = ui.row().apply {
        minimumHeight = ui.dp(60)
        setPadding(ui.dp(18), ui.dp(13), ui.dp(18), ui.dp(13))
        background = ui.transparentRipple(0)
        addView(ui.column().apply {
            addView(ui.text(title, 15f, bold = true))
            ui.gap(this, 2)
            addView(summaryView)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) })
        addView(chevronView)
        setOnClickListener { ui.haptic(this); onClick() }
    }

    private fun bindBodyFields(syncExclude: Boolean = false) {
        binding = true
        presetButtons.firstOrNull { it.first == editor.intent }?.second?.id?.let { presetGroup.check(it) }
        include.setText(editor.rules.includeKeywords.joinToString("\n"))
        codeWord.isChecked = editor.rules.requireCodeWord
        digits.isChecked = editor.rules.requireDigits
        if (editor.rules.matchAll) all.isChecked = true else any.isChecked = true
        if (syncExclude) {
            exclude.setText(editor.rules.excludeKeywords.joinToString("\n"))
        }
        templateCheckBoxes.forEach { (id, cb) -> cb.isChecked = id in selectedTemplates }
        binding = false
    }

    private fun render() {
        val rules = editor.rules
        val dest = destinations.firstOrNull { it.id == draftDestId }
        destinationLabel.text = if (dest != null) "${dest.type.displayName} · ${dest.name}" else "尚未选择目的地"
        destinationLabel.setTextColor(ui.color(if (dest != null) R.color.ink else R.color.danger))

        presetButtons.forEach { (choice, button) ->
            val selected = choice == editor.intent
            button.background = if (selected) ui.round(R.color.accent_surface, 12, R.color.accent)
            else ui.round(R.color.field, 12, R.color.line)
        }
        binding = true
        templateCheckBoxes.forEach { (id, cb) ->
            val checked = id in selectedTemplates
            if (cb.isChecked != checked) cb.isChecked = checked
            cb.background = if (checked) ui.round(R.color.accent_surface, 12, R.color.accent)
            else ui.round(R.color.field, 12, R.color.line)
        }
        binding = false
        custom.visibility = if (editor.intent == FilterEditor.Intent.CUSTOM) View.VISIBLE else View.GONE
        val activeBodyConditions = listOf(rules.includeKeywords.isNotEmpty(), rules.requireCodeWord, rules.requireDigits).count { it }
        combination.visibility = if (activeBodyConditions >= 2) View.VISIBLE else View.GONE
        optional.visibility = if (rules.enabled) View.VISIBLE else View.GONE
        excludeFields.visibility = if (excludeOpen) View.VISIBLE else View.GONE
        senderFields.visibility = if (senderOpen) View.VISIBLE else View.GONE
        ui.rotateChevron(excludeChevron, excludeOpen)
        ui.rotateChevron(senderChevron, senderOpen)
        excludeSummary.text = if (rules.excludeKeywords.isEmpty()) "未设置（不排除任何内容）" else "排除：${rules.excludeKeywords.joinToString("、")}"
        senderSummary.text = if (rules.allowedSenders.isEmpty()) "未设置（接收所有号码）" else "仅限：${rules.allowedSenders.joinToString("、")}"

        val destPrefix = if (dest != null) "发往 ${dest.type.displayName}「${dest.name}」：" else ""
        val statusSuffix = if (!draftEnabled) "（当前已关闭，不会触发转发）" else ""
        summary.text = destPrefix + FilterDescriptions.describe(rules) + statusSuffix

        val unsaved = isRuleDirty() || invalidSaved
        ui.setValueText(previewBadge, if (unsaved) "草稿预览" else "生效中", if (unsaved) R.color.warning else R.color.subtle)
        dirty.text = if (unsaved) "有未保存修改 · 现在收到的短信仍按旧规则处理" else "当前规则已保存"
        dirty.setTextColor(ui.color(if (unsaved) R.color.warning else R.color.subtle))
        save.text = if (editingRuleId == null) "保存新规则" else "保存并生效"
        errorBox.visibility = if (errorBox.text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun saveRules() {
        ruleNameLayout.error = null
        val trimmedName = draftName.trim()
        val candidate = ForwardRule(
            id = editingRuleId ?: ConfigStore.newId(),
            name = trimmedName,
            enabled = draftEnabled,
            destinationId = draftDestId,
            filter = editor.rules,
        )
        candidate.validationError(destinations)?.let { msg ->
            if (trimmedName.isEmpty()) {
                ruleNameLayout.error = msg
                ruleNameInput.requestFocus()
            }
            showError(msg)
            return
        }
        synchronized(State.gate) {
            val current = runCatching { ConfigStore.load(this) }.getOrDefault(ForwardConfig(destinations = destinations))
            val nextRules = if (editingRuleId != null && current.rules.any { it.id == editingRuleId }) {
                current.rules.map { if (it.id == editingRuleId) candidate else it }
            } else {
                current.rules + candidate
            }
            val nextConfig = current.copy(rules = nextRules)
            nextConfig.validationError()?.let { showError(it); return }
            if (!ConfigStore.save(this, nextConfig)) {
                showError("没有保存成功，请检查手机存储空间后重试。")
                return
            }
        }
        Toast.makeText(this, "规则「$trimmedName」已保存", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun showError(message: String) {
        errorBox.text = message
        errorBox.visibility = View.VISIBLE
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun requestExit() {
        if (!isRuleDirty()) { finish(); return }
        if (abandonDialog?.isShowing == true) return
        abandonDialog = ui.dialog().setTitle("还没保存修改")
            .setMessage("离开后，这次修改不会生效。")
            .setPositiveButton("放弃修改") { _, _ -> finish() }
            .setNegativeButton("继续编辑", null).show()
    }

    private fun showTest() {
        if (testOpen) return
        if (testRules != editor.rules) testResult = ""
        testOpen = true
        val sheet = ui.bottomSheet("试一条短信", "按当前草稿在本机试算，不保存、不联网发送。") { bodyLayout, _, scroll ->
            val (senderLayout, sender) = ui.textField(
                stableId = R.id.filter_test_sender,
                label = "发件号码 · 选填",
                placeholder = "例如：106123456",
                value = testSender,
                singleLine = true
            )
            bodyLayout.addView(senderLayout)
            ui.gap(bodyLayout, 6)
            bodyLayout.addView(ui.row().apply {
                addView(View(this@FilterActivity), LinearLayout.LayoutParams(0, 1, 1f))
                addView(ui.ghostButton("填入示例短信") {
                    val labels = arrayOf(
                        "美团登录验证码",
                        "美团 8888 元满减营销短信",
                        "验证码短信",
                        "仅含六位数字",
                        "含“银行”字样",
                        "仅含七位数字",
                        "普通通知"
                    )
                    val samples = arrayOf(
                        "【美团】您的登录验证码为 618294，5 分钟内有效，请勿泄露。",
                        "【美团】领 8888 元满减券，限时优惠立减，退订回T。",
                        "【示例】验证码为123456，请忽略本测试短信。",
                        "【示例】您的订单编号为123456。",
                        "【银行】验证码为123456，请忽略本测试短信。",
                        "【示例】您的订单编号为1234567。",
                        "【示例】这是一条普通通知，请忽略。"
                    )
                    ui.dialog().setTitle("选择示例短信").setItems(labels) { _, which ->
                        sender.setText("106123456")
                        bodyLayout.findViewById<EditText>(R.id.filter_test_body).setText(samples[which])
                    }.setNegativeButton("取消", null).show()
                })
            })
            val (smsLayout, body) = ui.textField(
                stableId = R.id.filter_test_body,
                label = "短信正文 *",
                placeholder = "在这里输入一条测试短信",
                value = testBody,
                singleLine = false
            )
            bodyLayout.addView(smsLayout)

            val result = ui.text("", 14f, R.color.muted).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
                layoutParams = LinearLayout.LayoutParams(-1, -2)
            }
            fun styleResult(text: String) {
                result.text = text.ifEmpty { "输入短信后点击下方按钮查看判断结果" }
                val tone = when {
                    text.startsWith("这条短信会转发") -> Ui.Tone.SUCCESS
                    text.startsWith("这条短信不会转发") || text.startsWith("设置有误") -> Ui.Tone.DANGER
                    else -> Ui.Tone.NEUTRAL
                }
                result.setTextColor(ui.color(tone.ink))
                result.background = ui.round(tone.surface, 12)
            }
            styleResult(testResult)

            ui.gap(bodyLayout, 10)
            bodyLayout.addView(ui.button("看看会不会转发", primary = true) {
                smsLayout.error = null
                val validation = editor.rules.validationError()
                val text = body.text?.toString().orEmpty()
                if (validation != null) {
                    testResult = "设置有误：$validation"
                } else if (text.isEmpty()) {
                    smsLayout.error = "先输入一条测试短信"
                    body.requestFocus()
                    return@button
                } else {
                    val decision = editor.rules.evaluate(sender.text?.toString().orEmpty(), text)
                    testRules = editor.rules
                    testResult = (if (decision.allowed) "这条短信会转发" else "这条短信不会转发") + "\n" + decision.reason
                }
                styleResult(testResult)
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            })
            ui.gap(bodyLayout, 12)
            bodyLayout.addView(result)
            sender.watch { testSender = it; testResult = ""; styleResult("内容已变化，请重新试算") }
            body.watch { testBody = it; smsLayout.error = null; testResult = ""; styleResult("内容已变化，请重新试算") }
            if (testResult.isNotEmpty()) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
        sheet.setOnDismissListener { testOpen = false }
        sheet.show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("savedName", savedName)
        outState.putBoolean("savedEnabled", savedEnabled)
        outState.putString("savedDestId", savedDestId)
        outState.putString("draftName", draftName)
        outState.putBoolean("draftEnabled", draftEnabled)
        outState.putString("draftDestId", draftDestId)
        outState.putBundle("savedRules", writeRules(editor.saved))
        outState.putBundle("draftRules", writeRules(editor.rules))
        outState.putString("intent", editor.intent.name)
        outState.putBoolean("invalidSaved", invalidSaved)
        outState.putBoolean("excludeOpen", excludeOpen); outState.putBoolean("senderOpen", senderOpen)
        outState.putStringArrayList("selectedTemplates", ArrayList(selectedTemplates))
        outState.putString("includeRaw", include.text?.toString().orEmpty())
        outState.putString("excludeRaw", exclude.text?.toString().orEmpty())
        outState.putString("sendersRaw", senders.text?.toString().orEmpty())
        outState.putBoolean("testOpen", testOpen); outState.putString("testSender", testSender); outState.putString("testBody", testBody); outState.putString("testResult", testResult)
        testRules?.let { outState.putBundle("testRules", writeRules(it)) }
        super.onSaveInstanceState(outState)
    }

    private fun writeRules(rules: FilterRules) = Bundle().apply {
        putBoolean("enabled", rules.enabled)
        putBoolean("digits", rules.requireDigits)
        putBoolean("all", rules.matchAll)
        putBoolean("codeWord", rules.requireCodeWord)
        putStringArrayList("include", ArrayList(rules.includeKeywords))
        putStringArrayList("exclude", ArrayList(rules.excludeKeywords))
        putStringArrayList("senders", ArrayList(rules.allowedSenders))
    }

    private fun readRules(bundle: Bundle) = FilterRules(
        enabled = bundle.getBoolean("enabled"),
        includeKeywords = bundle.getStringArrayList("include").orEmpty(),
        requireDigits = bundle.getBoolean("digits"),
        matchAll = bundle.getBoolean("all"),
        excludeKeywords = bundle.getStringArrayList("exclude").orEmpty(),
        allowedSenders = bundle.getStringArrayList("senders").orEmpty(),
        requireCodeWord = bundle.getBoolean("codeWord", false),
    )

    private fun EditText.watch(changed: (String) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { if (!binding) changed(s.toString()) }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }
}
