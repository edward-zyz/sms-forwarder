package com.kidbuddy.smsforwarder

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** 新建或编辑单条转发规则；表单只改内存草稿，保存才生效，本地试算不联网、不入队。 */
class FilterActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_RULE_ID = "rule_id"
    }

    private lateinit var ui: Ui
    private lateinit var ruleNameLayout: TextInputLayout
    private lateinit var ruleNameInput: TextInputEditText
    private lateinit var resetNameButton: TextView
    private lateinit var ruleEnabledSwitch: SwitchCompat
    private lateinit var destinationLabel: TextView
    private lateinit var conditionSection: FilterConditionSection
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
    private var ruleNameManuallyEdited = false
    private var destinations: List<Destination> = emptyList()

    private var binding = true
    private var invalidSaved = false
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
        savedEnabled = savedInstanceState?.getBoolean("savedEnabled") ?: (existing?.enabled ?: true)
        savedDestId = savedInstanceState?.getString("savedDestId") ?: (existing?.destinationId ?: defaultDestId)
        draftEnabled = savedInstanceState?.getBoolean("draftEnabled") ?: savedEnabled
        draftDestId = savedInstanceState?.getString("draftDestId") ?: savedDestId

        val initialEditor = if (existing != null) {
            FilterEditor(existing.filter)
        } else {
            FilterEditor.forNewRule()
        }
        val initialDestName = destinations.firstOrNull { it.id == draftDestId }?.name
        val initialSuggestedName = initialEditor.suggestRuleName(initialDestName)

        savedName = savedInstanceState?.getString("savedName") ?: (existing?.name ?: initialSuggestedName)
        draftName = savedInstanceState?.getString("draftName") ?: savedName
        ruleNameManuallyEdited = savedInstanceState?.getBoolean("ruleNameManuallyEdited") ?: (existing != null)
        invalidSaved = savedInstanceState?.getBoolean("invalidSaved") ?: configResult.isFailure

        testSender = savedInstanceState?.getString("testSender").orEmpty()
        testBody = savedInstanceState?.getString("testBody").orEmpty()
        testResult = savedInstanceState?.getString("testResult").orEmpty()
        testRules = savedInstanceState?.getBundle("testRules")?.let(FilterConditionSection::readRules)

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
            13f, R.color.danger, bold = true,
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
                placeholder = "例如：验证码短信、美团验证码",
                value = draftName,
                singleLine = true,
            )
            ruleNameLayout = nl
            ruleNameInput = ni
            addView(ruleNameLayout)

            resetNameButton = ui.ghostButton("使用推荐名称", R.color.accent) {
                ruleNameManuallyEdited = false
                applySuggestedRuleName()
                render()
            }.apply {
                minHeight = ui.dp(44)
                visibility = View.GONE
            }
            addView(resetNameButton)

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

        // 第二层：实时规则效果预览卡
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

        // 第三层：可复用的触发条件组件（3选1模式 + 平台标签 + 默认折叠高级选项）
        conditionSection = FilterConditionSection(this).apply {
            bind(initialEditor, advancedExpanded = false)
            savedInstanceState?.getBundle("conditionState")?.let { restoreInstanceState(it) }
            setOnChangeListener {
                if (!ruleNameManuallyEdited) {
                    applySuggestedRuleName()
                }
                render()
            }
        }
        content.addView(conditionSection)

        ruleNameInput.watch {
            draftName = it
            ruleNameManuallyEdited = true
            ruleNameLayout.error = null
            render()
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
            if (!ruleNameManuallyEdited) {
                applySuggestedRuleName()
            }
        }
        render()
    }

    private fun currentDestination(): Destination? =
        destinations.firstOrNull { it.id == draftDestId }

    private fun applySuggestedRuleName() {
        val suggested = conditionSection.currentEditor().suggestRuleName(currentDestination()?.name)
        draftName = suggested
        if (ruleNameInput.text?.toString() != suggested) {
            binding = true
            ruleNameInput.setText(suggested)
            ruleNameInput.setSelection(suggested.length)
            binding = false
        }
        ruleNameLayout.error = null
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
                if (!ruleNameManuallyEdited) {
                    applySuggestedRuleName()
                }
                dialog.dismiss()
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun isRuleDirty(): Boolean =
        editingRuleId == null ||
            conditionSection.currentEditor().isDirty ||
            draftName.trim() != savedName.trim() ||
            draftEnabled != savedEnabled ||
            draftDestId != savedDestId

    private fun render() {
        val rules = conditionSection.currentRules()
        val dest = currentDestination()
        destinationLabel.text = if (dest != null) "${dest.type.displayName} · ${dest.name}" else "尚未选择目的地"
        destinationLabel.setTextColor(ui.color(if (dest != null) R.color.ink else R.color.danger))

        val suggestedName = conditionSection.currentEditor().suggestRuleName(dest?.name)
        resetNameButton.visibility = if (editingRuleId == null && ruleNameManuallyEdited && draftName.trim() != suggestedName) {
            View.VISIBLE
        } else {
            View.GONE
        }

        summary.text = FilterDescriptions.summarizeForDestination(rules, dest, draftEnabled)

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
            filter = conditionSection.currentRules(),
        )
        candidate.validationError(destinations)?.let { msg ->
            if (trimmedName.isEmpty() || trimmedName.length > ForwardRule.MAX_NAME_LENGTH) {
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
        val draftRules = conditionSection.currentRules()
        if (testRules != draftRules) testResult = ""
        testOpen = true
        val sheet = ui.bottomSheet("试一条短信", "按当前草稿在本机试算，不保存、不联网发送。") { bodyLayout, _, scroll ->
            val (senderLayout, sender) = ui.textField(
                stableId = R.id.filter_test_sender,
                label = "发件号码 · 选填",
                placeholder = "例如：106123456",
                value = testSender,
                singleLine = true,
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
                        "普通通知",
                    )
                    val samples = arrayOf(
                        "【美团】您的登录验证码为 618294，5 分钟内有效，请勿泄露。",
                        "【美团】领 8888 元满减券，限时优惠立减，退订回T。",
                        "【示例】验证码为123456，请忽略本测试短信。",
                        "【示例】您的订单编号为123456。",
                        "【银行】验证码为123456，请忽略本测试短信。",
                        "【示例】您的订单编号为1234567。",
                        "【示例】这是一条普通通知，请忽略。",
                    )
                    ui.dialog().setTitle("选择示例短信").setItems(labels) { _, which ->
                        sender.setText("106123456")
                        bodyLayout.findViewById<EditText>(R.id.filter_test_body).setText(samples[which])
                    }.setNegativeButton("取消", null).show()
                }.apply { minHeight = ui.dp(44) })
            })
            val (smsLayout, body) = ui.textField(
                stableId = R.id.filter_test_body,
                label = "短信正文 *",
                placeholder = "在这里输入一条测试短信",
                value = testBody,
                singleLine = false,
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
                val latestRules = conditionSection.currentRules()
                val validation = latestRules.validationError()
                val text = body.text?.toString().orEmpty()
                if (validation != null) {
                    testResult = "设置有误：$validation"
                } else if (text.isEmpty()) {
                    smsLayout.error = "先输入一条测试短信"
                    body.requestFocus()
                    return@button
                } else {
                    val decision = latestRules.evaluate(sender.text?.toString().orEmpty(), text)
                    testRules = latestRules
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
        outState.putBoolean("ruleNameManuallyEdited", ruleNameManuallyEdited)
        outState.putBoolean("invalidSaved", invalidSaved)
        outState.putBundle("conditionState", conditionSection.saveInstanceState())
        outState.putBoolean("testOpen", testOpen)
        outState.putString("testSender", testSender)
        outState.putString("testBody", testBody)
        outState.putString("testResult", testResult)
        testRules?.let { outState.putBundle("testRules", FilterConditionSection.writeRules(it)) }
        super.onSaveInstanceState(outState)
    }

    private fun EditText.watch(changed: (String) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { if (!binding) changed(s.toString()) }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }
}
