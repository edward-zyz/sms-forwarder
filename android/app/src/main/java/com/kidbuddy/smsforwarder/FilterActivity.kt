package com.kidbuddy.smsforwarder

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** 表单只改内存草稿；保存才生效，本地试算不联网、不入队。 */
class FilterActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var editor: FilterEditor
    private lateinit var include: EditText
    private lateinit var digits: CheckBox
    private lateinit var exclude: EditText
    private lateinit var senders: EditText
    private lateinit var all: RadioButton
    private lateinit var any: RadioButton
    private lateinit var custom: LinearLayout
    private lateinit var combination: LinearLayout
    private lateinit var optional: LinearLayout
    private lateinit var excludeFields: LinearLayout
    private lateinit var senderFields: LinearLayout
    private lateinit var excludeSummary: TextView
    private lateinit var senderSummary: TextView
    private lateinit var summary: TextView
    private lateinit var dirty: TextView
    private lateinit var error: TextView
    private lateinit var save: android.widget.Button
    private var binding = true
    private var invalidSaved = false
    private var excludeOpen = false
    private var senderOpen = false
    private var testOpen = false
    private var testSender = ""
    private var testBody = ""
    private var testResult = ""
    private var testRules: FilterRules? = null
    private var abandonDialog: AlertDialog? = null

    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppAppearance.wrap(newBase)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val loaded = runCatching { FilterSettings.load(this) }
        val saved = savedInstanceState?.getBundle("savedRules")?.let(::readRules) ?: loaded.getOrDefault(FilterRules())
        val draft = savedInstanceState?.getBundle("draftRules")?.let(::readRules) ?: saved
        val intent = savedInstanceState?.getString("intent")?.let { runCatching { FilterEditor.Intent.valueOf(it) }.getOrNull() }
            ?: FilterEditor.identify(draft)
        editor = FilterEditor(saved, draft, intent)
        invalidSaved = savedInstanceState?.getBoolean("invalidSaved") ?: loaded.isFailure
        excludeOpen = savedInstanceState?.getBoolean("excludeOpen") ?: false
        senderOpen = savedInstanceState?.getBoolean("senderOpen") ?: false
        testSender = savedInstanceState?.getString("testSender").orEmpty()
        testBody = savedInstanceState?.getString("testBody").orEmpty()
        testResult = savedInstanceState?.getString("testResult").orEmpty()
        testRules = savedInstanceState?.getBundle("testRules")?.let(::readRules)

        val root = ui.column().apply { setBackgroundColor(ui.color(R.color.page)) }
        val content = ui.column().apply { setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(8)) }
        val scroll = ScrollView(this).apply {
            id = R.id.filter_scroll
            isFillViewport = true
            addView(content)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = ui.column().apply {
            setPadding(ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(12))
            setBackgroundColor(ui.color(R.color.surface))
        }
        dirty = ui.text("", 13f, R.color.muted).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        footer.addView(dirty)
        ui.gap(footer, 8)
        val actions = ui.row()
        actions.addView(ui.button("试一条短信") { showTest() }.apply {
            id = R.id.filter_test_button
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) }
        })
        save = ui.button("保存并生效", primary = true) { saveRules() }.apply {
            id = R.id.filter_save_button
            layoutParams = LinearLayout.LayoutParams(0, -2, 1.3f)
        }
        actions.addView(save)
        footer.addView(actions)
        root.addView(footer)
        setContentView(root)

        content.addView(ui.button("返回短信转发") { requestExit() })
        ui.gap(content, 16)
        content.addView(ui.text("哪些短信要转发", 25f, bold = true))
        ui.gap(content, 8)
        content.addView(ui.text("先选一种方式，再按需要加限制。修改保存后只影响新收到的短信，已有待发消息不变。", 14f, R.color.muted))
        ui.gap(content, 18)
        content.addView(ui.card().apply {
            addView(ui.text("选择转发方式", 17f, bold = true))
            ui.gap(this, 8)
            val choices = RadioGroup(this@FilterActivity).apply { isSaveEnabled = false }
            val options = listOf(
                Triple(FilterEditor.Intent.EVERYTHING, R.id.filter_preset_everything, "全部短信"),
                Triple(FilterEditor.Intent.VERIFICATION, R.id.filter_preset_verification, "验证码短信\n同时含“验证码”和4～6位数字"),
                Triple(FilterEditor.Intent.VERIFICATION_OR_DIGITS, R.id.filter_preset_either, "验证码字样或数字\n有一个符合即可，订单号也可能命中"),
                Triple(FilterEditor.Intent.CUSTOM, R.id.filter_preset_custom, "自己设置")
            )
            options.forEach { (choice, stableId, title) -> choices.addView(radio(title, stableId)) }
            choices.check(options.first { it.first == editor.intent }.second)
            addView(choices)
            choices.setOnCheckedChangeListener { _, checked ->
                if (!binding) {
                    editor.select(options.first { it.second == checked }.first)
                    bindBodyFields()
                    render()
                }
            }
        })

        content.addView(ui.card(R.color.accent_surface).apply {
            addView(ui.text("这次会怎么转发", 15f, bold = true))
            ui.gap(this, 8)
            summary = ui.text("", 14f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
            addView(summary)
        })

        custom = ui.card().apply {
            addView(ui.text("短信里要有什么", 17f, bold = true))
            ui.gap(this, 8)
            addView(ui.text("包含这些词里的任意一个 · 可不填", 14f, bold = true))
            include = field(R.id.filter_include, "例如：验证码,登录（每行一个，或用逗号分开）", draft.includeKeywords.joinToString("\n"))
            ui.gap(this, 8); addView(include)
            digits = CheckBox(this@FilterActivity).apply {
                id = R.id.filter_digits; text = "有连续 4–6 位数字"; textSize = 16f
                setTextColor(ui.color(R.color.ink)); minHeight = ui.dp(48)
                isChecked = draft.requireDigits; isSaveEnabled = false
            }
            addView(digits)
            addView(ui.text("完整的数字串才算；7 位及以上不算。", 13f, R.color.muted))
            combination = ui.column().apply {
                ui.gap(this, 12)
                addView(ui.text("上面两项怎么配合", 14f, bold = true))
                val group = RadioGroup(this@FilterActivity).apply { isSaveEnabled = false }
                any = radio("有一个符合就行", R.id.filter_any)
                all = radio("两项都符合", R.id.filter_all)
                group.addView(any); group.addView(all)
                group.check(if (draft.matchAll) R.id.filter_all else R.id.filter_any)
                group.setOnCheckedChangeListener { _, selected -> if (!binding) { editor.matchAll(selected == R.id.filter_all); render() } }
                addView(group)
            }
            addView(combination)
        }
        content.addView(custom)

        optional = ui.column()
        optional.addView(ui.text("还可以加限制 · 选填", 15f, R.color.muted))
        ui.gap(optional, 12)
        optional.addView(ui.card().apply {
            addView(ui.text("不转发这些内容", 17f, bold = true))
            ui.gap(this, 8)
            excludeSummary = ui.text("", 14f, R.color.muted); addView(excludeSummary)
            ui.gap(this, 8)
            addView(ui.button("添加 / 修改排除词") { excludeOpen = !excludeOpen; render() })
            excludeFields = ui.column().apply {
                ui.gap(this, 8)
                addView(ui.text("含其中任何一个词就不转，即使符合上面的条件。", 13f, R.color.muted))
                exclude = field(R.id.filter_exclude, "例如：银行（每行一个，或用逗号分开）", draft.excludeKeywords.joinToString("\n"))
                ui.gap(this, 8); addView(exclude)
            }
            addView(excludeFields)
        })
        optional.addView(ui.card().apply {
            addView(ui.text("只转发这些号码", 17f, bold = true))
            ui.gap(this, 8)
            senderSummary = ui.text("", 14f, R.color.muted); addView(senderSummary)
            ui.gap(this, 8)
            addView(ui.button("添加 / 修改号码") { senderOpen = !senderOpen; render() })
            senderFields = ui.column().apply {
                ui.gap(this, 8)
                addView(ui.text("填完整号码，或用 106* 表示以 106 开头。留空接收所有号码。", 13f, R.color.muted))
                senders = field(R.id.filter_senders, "每行一个号码，也可用逗号分开", draft.allowedSenders.joinToString("\n"))
                ui.gap(this, 8); addView(senders)
                addView(ui.button("号码怎么填写？") {
                    AlertDialog.Builder(this@FilterActivity).setTitle("号码怎么填写")
                        .setMessage("完整号码只匹配这个号码，不按尾号匹配。\n106* 匹配所有以 106 开头的号码；* 只能放末尾，不能单独填写。\n支持空格、横线、括号、全角数字，以及中国手机号 +86 / 0086 格式。")
                        .setPositiveButton("知道了", null).show()
                })
            }
            addView(senderFields)
        })
        content.addView(optional)
        error = ui.text(if (invalidSaved) "已保存设置异常。请重新设置并保存；修复前新短信不会转发。" else "", 14f, R.color.danger)
        content.addView(error)

        // 自己保存控件文本，避免 Android 自动恢复触发监听、悄悄改写未编辑字段。
        savedInstanceState?.getString("includeRaw")?.let { include.setText(it) }
        savedInstanceState?.getString("excludeRaw")?.let { exclude.setText(it) }
        savedInstanceState?.getString("sendersRaw")?.let { senders.setText(it) }
        include.watch { editor.include(FilterEditor.parseList(it)); render() }
        exclude.watch { editor.exclude(FilterEditor.parseList(it)); render() }
        senders.watch { editor.senders(FilterEditor.parseList(it)); render() }
        digits.setOnCheckedChangeListener { _, checked -> if (!binding) { editor.digits(checked); render() } }
        binding = false
        render()
        if (savedInstanceState?.getBoolean("testOpen") == true) showTest()
    }

    private fun bindBodyFields() {
        binding = true
        include.setText(editor.rules.includeKeywords.joinToString("\n"))
        digits.isChecked = editor.rules.requireDigits
        if (editor.rules.matchAll) all.isChecked = true else any.isChecked = true
        binding = false
    }
    private fun render() {
        val rules = editor.rules
        custom.visibility = if (editor.intent == FilterEditor.Intent.CUSTOM) View.VISIBLE else View.GONE
        combination.visibility = if (rules.includeKeywords.isNotEmpty() && rules.requireDigits) View.VISIBLE else View.GONE
        optional.visibility = if (rules.enabled) View.VISIBLE else View.GONE
        excludeFields.visibility = if (excludeOpen) View.VISIBLE else View.GONE
        senderFields.visibility = if (senderOpen) View.VISIBLE else View.GONE
        excludeSummary.text = if (rules.excludeKeywords.isEmpty()) "尚未设置，不排除任何内容" else rules.excludeKeywords.joinToString("、")
        senderSummary.text = if (rules.allowedSenders.isEmpty()) "尚未设置，接收所有号码" else rules.allowedSenders.joinToString("、")
        summary.text = FilterDescriptions.describe(rules)
        dirty.text = if (editor.isDirty || invalidSaved) "修改未保存 · 现在收短信仍按原设置处理" else "当前已保存"
        save.text = if (rules.enabled) "保存并生效" else "保存为全部转发"
    }

    private fun saveRules() {
        editor.rules.validationError()?.let { showError(it); return }
        if (!runCatching { FilterSettings.save(this, editor.rules) }.getOrDefault(false)) {
            showError("没有保存成功，请检查手机存储空间后重试。")
            return
        }
        Toast.makeText(this, "已保存，只对新短信生效", Toast.LENGTH_LONG).show()
        finish()
    }
    private fun showError(message: String) {
        error.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
    @Deprecated("原生 Activity 使用此回调处理旧版 Android 返回")
    override fun onBackPressed() { requestExit() }
    private fun requestExit() {
        if (!editor.isDirty) { finish(); return }
        if (abandonDialog?.isShowing == true) return
        abandonDialog = AlertDialog.Builder(this).setTitle("还没保存修改")
            .setMessage("离开后，这次修改不会生效。")
            .setPositiveButton("放弃修改") { _, _ -> finish() }
            .setNegativeButton("继续编辑", null).show()
    }

    private fun showTest() {
        if (testOpen) return
        // 上次的结果只对当时的规则有效，改完设置不能继续显示旧结论。
        if (testRules != editor.rules) testResult = ""
        testOpen = true
        val scroll = ScrollView(this)
        val content = ui.column().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(16)) }
        content.addView(ui.text("按当前尚未保存的设置，在本机判断；不保存、不发送。", 14f, R.color.muted))
        ui.gap(content, 12)
        content.addView(ui.text("发件号码 · 可不填", 14f, bold = true))
        val sender = field(R.id.filter_test_sender, "例如：106123456", testSender, true)
        ui.gap(content, 8); content.addView(sender)
        ui.gap(content, 12)
        content.addView(ui.text("短信正文", 14f, bold = true))
        val body = field(R.id.filter_test_body, "在这里输入一条短信", testBody)
        ui.gap(content, 8); content.addView(body)
        val previousTint = when {
            testResult.startsWith("这条短信会转发") -> R.color.success
            testResult.startsWith("这条短信不会转发") || testResult.startsWith("设置有误") -> R.color.danger
            else -> R.color.muted
        }
        val result = ui.text(testResult.ifEmpty { "还没试算" }, 15f, previousTint).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        ui.gap(content, 8)
        content.addView(ui.button("选一条样例") {
            val labels = arrayOf("验证码短信", "仅含六位数字", "含“银行”字样", "仅含七位数字", "普通通知")
            val samples = arrayOf("【示例】验证码为123456，请忽略本测试短信。", "【示例】您的订单编号为123456。", "【银行】验证码为123456，请忽略本测试短信。", "【示例】您的订单编号为1234567。", "【示例】这是一条普通通知，请忽略。")
            AlertDialog.Builder(this).setTitle("选择样例").setItems(labels) { _, which ->
                sender.setText("106123456"); body.setText(samples[which])
            }.setNegativeButton("取消", null).show()
        })
        ui.gap(content, 8)
        content.addView(ui.button("看看会不会转发", primary = true) {
            val validation = editor.rules.validationError()
            if (validation != null) {
                testResult = "设置有误：$validation"
                result.setTextColor(ui.color(R.color.danger))
            } else if (body.text.isEmpty()) {
                body.error = "先输入一条测试短信"; return@button
            } else {
                val decision = editor.rules.evaluate(sender.text.toString(), body.text.toString())
                testRules = editor.rules
                testResult = (if (decision.allowed) "这条短信会转发" else "这条短信不会转发") + "\n" + decision.reason
                result.setTextColor(ui.color(if (decision.allowed) R.color.success else R.color.danger))
            }
            result.text = testResult
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        })
        ui.gap(content, 12); content.addView(result)
        sender.watch { testSender = it; testResult = ""; result.text = "内容已变化，请重新试算"; result.setTextColor(ui.color(R.color.muted)) }
        body.watch { testBody = it; testResult = ""; result.text = "内容已变化，请重新试算"; result.setTextColor(ui.color(R.color.muted)) }
        scroll.addView(content)
        val dialog = AlertDialog.Builder(this).setTitle("试一条短信").setView(scroll)
            .setPositiveButton("回到设置", null).setOnDismissListener { testOpen = false }.show()
        dialog.window?.setBackgroundDrawable(ui.round(R.color.surface, 20))
        if (testResult.isNotEmpty()) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBundle("savedRules", writeRules(editor.saved))
        outState.putBundle("draftRules", writeRules(editor.rules))
        outState.putString("intent", editor.intent.name)
        outState.putBoolean("invalidSaved", invalidSaved)
        outState.putBoolean("excludeOpen", excludeOpen); outState.putBoolean("senderOpen", senderOpen)
        outState.putString("includeRaw", include.text.toString()); outState.putString("excludeRaw", exclude.text.toString()); outState.putString("sendersRaw", senders.text.toString())
        outState.putBoolean("testOpen", testOpen); outState.putString("testSender", testSender); outState.putString("testBody", testBody); outState.putString("testResult", testResult)
        testRules?.let { outState.putBundle("testRules", writeRules(it)) }
        super.onSaveInstanceState(outState)
    }
    private fun writeRules(rules: FilterRules) = Bundle().apply {
        putBoolean("enabled", rules.enabled); putBoolean("digits", rules.requireDigits); putBoolean("all", rules.matchAll)
        putStringArrayList("include", ArrayList(rules.includeKeywords)); putStringArrayList("exclude", ArrayList(rules.excludeKeywords)); putStringArrayList("senders", ArrayList(rules.allowedSenders))
    }
    private fun readRules(bundle: Bundle) = FilterRules(bundle.getBoolean("enabled"), bundle.getStringArrayList("include").orEmpty(), bundle.getBoolean("digits"), bundle.getBoolean("all"), bundle.getStringArrayList("exclude").orEmpty(), bundle.getStringArrayList("senders").orEmpty())

    private fun EditText.watch(changed: (String) -> Unit) { addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { if (!binding) changed(s.toString()) }
        override fun afterTextChanged(s: Editable?) = Unit
    }) }
    private fun field(stableId: Int, placeholder: String, value: String, singleLine: Boolean = false) = EditText(this).apply {
        id = stableId; hint = placeholder; setText(value); textSize = 15f
        setTextColor(ui.color(R.color.ink)); setHintTextColor(ui.color(R.color.muted))
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        inputType = InputType.TYPE_CLASS_TEXT or if (singleLine) InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS else InputType.TYPE_TEXT_FLAG_MULTI_LINE
        if (singleLine) setSingleLine(true) else { minLines = 2; maxLines = 5 }
        minHeight = ui.dp(52); setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
        background = ui.round(R.color.field, 10, R.color.line)
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        isSaveEnabled = false
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }
    private fun radio(label: String, stableId: Int) = RadioButton(this).apply {
        id = stableId; textSize = 15f; setTextColor(ui.color(R.color.ink))
        val split = label.indexOf('\n')
        text = SpannableString(label).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, if (split < 0) length else split, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (split >= 0) {
                setSpan(RelativeSizeSpan(.86f), split + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(ui.color(R.color.muted)), split + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        setPadding(0, ui.dp(4), 0, ui.dp(4))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(6) }
        minHeight = ui.dp(48); isSaveEnabled = false
    }
}
