package com.kidbuddy.smsforwarder

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** 管理多条短信转发规则；支持启用/停用、编辑、删除与全局试算。 */
class RulesActivity : AppCompatActivity() {
    private lateinit var ui: Ui
    private lateinit var listContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        AppAppearance.apply(this)
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val content = ui.column().apply { setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(28)) }
        val scroll = ScrollView(this).apply {
            id = R.id.rules_scroll
            isFillViewport = true
            setBackgroundColor(ui.color(R.color.page))
            addView(content)
        }
        setContentView(scroll)

        content.addView(ui.topBar("转发规则") { finish() })
        content.addView(ui.text("新短信会匹配所有已启用的规则，并分别发往对应目的地；同目的地自动去重。", 13f, R.color.muted))
        ui.gap(content, 14)

        val actions = ui.row()
        actions.addView(
            ui.button("添加规则", primary = true) { openNewRule() },
            LinearLayout.LayoutParams(0, -2, 1.2f).apply { marginEnd = ui.dp(8) },
        )
        actions.addView(
            ui.button("试一条短信") { showGlobalTest() },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        content.addView(actions)
        ui.gap(content, 16)

        listContainer = ui.column()
        content.addView(listContainer)
    }

    override fun onResume() {
        super.onResume()
        renderList()
    }

    private fun openNewRule() {
        val config = runCatching { ConfigStore.load(this) }.getOrDefault(ForwardConfig())
        if (config.destinations.isEmpty()) {
            ui.dialog()
                .setTitle("先添加转发目的地")
                .setMessage("创建规则前，需要先添加一个转发目的地（飞书、企业微信、钉钉或自定义 Webhook）。")
                .setPositiveButton("去添加目的地") { _, _ ->
                    startActivity(Intent(this, DestinationEditActivity::class.java))
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        startActivity(Intent(this, FilterActivity::class.java))
    }

    private fun renderList() {
        listContainer.removeAllViews()
        val configResult = runCatching { ConfigStore.load(this) }
        if (configResult.isFailure) {
            listContainer.addView(ui.card(R.color.danger_surface, border = R.color.line) {
                addView(ui.text("规则配置读取异常，请检查目的地和规则设置。", 14f, R.color.danger, bold = true))
            })
            return
        }
        val config = configResult.getOrThrow()
        if (config.rules.isEmpty()) {
            listContainer.addView(ui.card {
                addView(ui.text("还没有转发规则", 17f, bold = true))
                ui.gap(this, 6)
                addView(ui.text("点击上方「添加规则」，可以为验证码、运维告警、银行通知等分别设置过滤条件与接收群。", 14f, R.color.muted))
            })
            return
        }

        ui.sectionTitle(listContainer, "全部规则（${config.rules.size}）")
        config.rules.forEach { rule ->
            val dest = config.destinationById(rule.destinationId)
            listContainer.addView(ui.card(padH = 16, padV = 14) {
                addView(ui.row().apply {
                    addView(ui.column().apply {
                        addView(ui.text(rule.name, 17f, bold = true))
                        ui.gap(this, 6)
                        val destText = if (dest != null) "发往 · ${dest.type.displayName}「${dest.name}」" else "目的地无效，请重新选择"
                        addView(ui.badge(destText, if (dest != null) Ui.Tone.ACCENT else Ui.Tone.DANGER))
                    }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) })
                    val toggle = ui.switch(rule.enabled, "启用规则 ${rule.name}").apply {
                        setOnCheckedChangeListener { _, checked ->
                            ui.haptic(this)
                            toggleRule(rule.id, checked)
                        }
                    }
                    addView(toggle)
                })
                ui.gap(this, 10)
                addView(ui.text(FilterDescriptions.describe(rule.filter), 14f, R.color.muted))
                ui.divider(this, top = 12, bottom = 10)
                addView(ui.row().apply {
                    addView(ui.button("编辑规则", compact = true) {
                        startActivity(Intent(this@RulesActivity, FilterActivity::class.java).apply {
                            putExtra(FilterActivity.EXTRA_RULE_ID, rule.id)
                        })
                    })
                    addView(View(this@RulesActivity), LinearLayout.LayoutParams(0, 1, 1f))
                    addView(ui.ghostButton("删除", R.color.danger) { confirmDelete(rule) })
                })
            })
        }
    }

    private fun toggleRule(ruleId: String, enabled: Boolean) {
        synchronized(State.gate) {
            val current = runCatching { ConfigStore.load(this) }.getOrNull() ?: return
            val next = current.copy(rules = current.rules.map { if (it.id == ruleId) it.copy(enabled = enabled) else it })
            if (!ConfigStore.save(this, next)) {
                Toast.makeText(this, "状态保存失败，请重试", Toast.LENGTH_SHORT).show()
                renderList()
            }
        }
    }

    private fun confirmDelete(rule: ForwardRule) {
        ui.dialog()
            .setTitle("删除规则？")
            .setMessage("删除「${rule.name}」后，不再按该规则转发新短信，已有待发队列不变。")
            .setPositiveButton("删除") { _, _ ->
                synchronized(State.gate) {
                    val current = runCatching { ConfigStore.load(this) }.getOrDefault(ForwardConfig())
                    val next = current.copy(rules = current.rules.filterNot { it.id == rule.id })
                    if (ConfigStore.save(this, next)) {
                        Toast.makeText(this, "已删除规则「${rule.name}」", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "删除失败，请重试", Toast.LENGTH_SHORT).show()
                    }
                }
                renderList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showGlobalTest() {
        val config = runCatching { ConfigStore.load(this) }.getOrNull()
        if (config == null || config.rules.isEmpty()) {
            Toast.makeText(this, "请先添加至少一条转发规则", Toast.LENGTH_SHORT).show()
            return
        }
        ui.bottomSheet("试一条短信（全部规则）", "按当前已保存的所有启用规则在本机试算，不联网、不入队。") { bodyLayout, _, scroll ->
            val (senderLayout, senderInput) = ui.textField(
                label = "发件号码 · 选填",
                placeholder = "例如：106123456",
                singleLine = true
            )
            bodyLayout.addView(senderLayout)
            ui.gap(bodyLayout, 10)
            val (smsLayout, smsInput) = ui.textField(
                label = "短信正文 *",
                placeholder = "在这里输入一条测试短信",
                singleLine = false
            )
            bodyLayout.addView(smsLayout)
            val result = ui.text("输入短信后点击下方按钮查看命中结果", 14f, R.color.muted).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
                background = ui.round(R.color.neutral_surface, 12)
                layoutParams = LinearLayout.LayoutParams(-1, -2)
            }
            ui.gap(bodyLayout, 12)
            bodyLayout.addView(ui.button("看看会转发到哪里", primary = true) {
                smsLayout.error = null
                val text = smsInput.text?.toString().orEmpty()
                if (text.isEmpty()) {
                    smsLayout.error = "先输入一条测试短信"
                    smsInput.requestFocus()
                    return@button
                }
                val latestConfig = runCatching { ConfigStore.load(this) }.getOrDefault(config)
                val decision = RoutingEngine.evaluate(latestConfig, senderInput.text?.toString().orEmpty(), text)
                if (decision.allowed) {
                    val lines = buildList {
                        add("会转发到 ${decision.dispatches.size} 个目的地：")
                        decision.dispatches.forEach { d ->
                            add("• ${d.destination.type.displayName}「${d.destination.name}」（命中：${d.rule.name}）")
                        }
                        if (decision.matches.size > decision.dispatches.size) {
                            add("（共命中 ${decision.matches.size} 条规则，同目的地已自动去重）")
                        }
                    }
                    result.text = lines.joinToString("\n")
                    result.setTextColor(ui.color(R.color.success))
                    result.background = ui.round(R.color.success_surface, 12)
                } else {
                    result.text = "这条短信不会转发\n${decision.summaryReason}"
                    result.setTextColor(ui.color(R.color.danger))
                    result.background = ui.round(R.color.danger_surface, 12)
                }
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            })
            ui.gap(bodyLayout, 12)
            bodyLayout.addView(result)
        }.show()
    }
}
