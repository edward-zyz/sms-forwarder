package com.kidbuddy.smsforwarder

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var webhook: EditText
    private lateinit var secret: EditText
    private lateinit var toggle: Switch
    private lateinit var stateTitle: TextView
    private lateinit var stateDetail: TextView
    private lateinit var queueValue: TextView
    private lateinit var permissionValue: TextView
    private lateinit var latest: TextView
    private lateinit var latestTime: TextView
    private lateinit var configSummary: TextView
    private lateinit var configFields: LinearLayout
    private lateinit var configButton: Button
    private lateinit var configHint: TextView
    private lateinit var saveButton: Button
    private lateinit var testButton: Button
    private lateinit var retryButton: Button
    private lateinit var clearButton: Button
    private lateinit var permissionButton: Button
    private lateinit var queueHint: TextView
    private lateinit var filterSummary: TextView
    private var updatingToggle = false
    private var configExpanded = false
    private var showCredentials = false
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { renderStatus(); handler.postDelayed(this, 1500) }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppAppearance.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val content = ui.column().apply { setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(24)) }
        val scroll = ScrollView(this).apply {
            id = R.id.main_scroll
            isFillViewport = true
            clipToPadding = false
            setBackgroundColor(ui.color(R.color.page))
            addView(content)
        }
        setContentView(scroll)
        content.addView(ui.row().apply {
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_forward)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)).apply { rightMargin = ui.dp(12) })
            addView(ui.column().apply {
                addView(ui.text("短信转发", 25f, bold = true))
                addView(ui.text("Android 短信，送达飞书", 14f, R.color.muted))
            })
        })
        ui.gap(content, 24)

        // 首屏只突出开关、待发数量和权限，不把配置表单放在主操作之前。
        content.addView(ui.card(R.color.accent_surface).apply {
            addView(ui.row().apply {
                addView(ui.column().apply {
                    addView(ui.text("自动转发", 13f, R.color.muted, true))
                    stateTitle = ui.text("", 24f, bold = true)
                    addView(stateTitle)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                toggle = Switch(this@MainActivity).apply {
                    contentDescription = "自动转发短信到飞书"
                    minWidth = ui.dp(64)
                    minHeight = ui.dp(48)
                    setPadding(ui.dp(8), 0, 0, 0)
                    isSaveEnabled = false
                    isChecked = State.enabled(this@MainActivity)
                }
                addView(toggle)
            })
            ui.gap(this, 8)
            stateDetail = ui.text("", 14f, R.color.muted)
            addView(stateDetail)
            ui.divider(this)
            addView(ui.row().apply {
                addView(ui.column().apply {
                    queueValue = ui.text("0 条", 20f, bold = true)
                    addView(queueValue)
                    addView(ui.text("等待发送", 13f, R.color.muted))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ui.column().apply {
                    permissionValue = ui.text("", 16f, bold = true)
                    addView(permissionValue)
                    addView(ui.text("短信权限", 13f, R.color.muted))
                }, LinearLayout.LayoutParams(0, -2, 1f))
            })
            ui.gap(this, 20)
            testButton = ui.button("发送测试消息", primary = true) { sendTest() }
            addView(testButton)
        })

        content.addView(ui.card().apply {
            addView(ui.text("最近动态", 16f, bold = true))
            ui.gap(this, 12)
            latest = ui.text("", 15f)
            latest.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            addView(latest)
            ui.gap(this, 8)
            latestTime = ui.text("", 12f, R.color.muted)
            addView(latestTime)
        })

        content.addView(ui.card().apply {
            addView(ui.text("哪些短信要转发", 17f, bold = true))
            ui.gap(this, 8)
            filterSummary = ui.text("", 14f, R.color.muted)
            addView(filterSummary)
            ui.gap(this, 16)
            addView(ui.button("设置转发范围") {
                startActivity(Intent(this@MainActivity, FilterActivity::class.java))
            })
        })

        content.addView(ui.card().apply {
            addView(ui.row().apply {
                addView(ui.column().apply {
                    addView(ui.text("飞书配置", 17f, bold = true))
                    configSummary = ui.text("", 13f, R.color.muted)
                    addView(configSummary)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                configButton = ui.button("编辑") { openConfigEditor() }.apply {
                    contentDescription = "编辑飞书配置，运行中会先暂停转发"
                }
                addView(configButton, LinearLayout.LayoutParams(ui.dp(112), -2).apply { leftMargin = ui.dp(8) })
            })
            configFields = ui.column()
            addView(configFields)
            ui.gap(configFields, 16)
            configFields.addView(ui.text("机器人 Webhook", 14f, bold = true))
            ui.gap(configFields, 8)
            webhook = credentialField(R.id.webhook_input, "粘贴飞书机器人的 HTTPS 地址")
            webhook.setText(State.prefs(this@MainActivity).getString("webhook", ""))
            configFields.addView(webhook)
            ui.gap(configFields, 16)
            configFields.addView(ui.text("签名密钥 · 选填", 14f, bold = true))
            ui.gap(configFields, 8)
            secret = credentialField(R.id.secret_input, "仅在机器人启用签名校验时填写")
            secret.setText(State.prefs(this@MainActivity).getString("secret", ""))
            configFields.addView(secret)
            ui.gap(configFields, 8)
            configFields.addView(ui.button("显示地址与密钥") {
                showCredentials = !showCredentials
                listOf(webhook, secret).forEach {
                    it.transformationMethod = if (showCredentials) null else PasswordTransformationMethod.getInstance()
                    it.setSelection(it.text.length)
                }
                revealButtonText()
            }.apply { tag = "reveal" })
            ui.gap(configFields, 12)
            configHint = ui.text("", 13f, R.color.muted)
            configFields.addView(configHint)
            ui.gap(configFields, 12)
            saveButton = ui.button("保存配置", primary = true) { saveConfig() }
            configFields.addView(saveButton)
        })

        content.addView(ui.card().apply {
            addView(ui.text("后台运行", 17f, bold = true))
            ui.gap(this, 8)
            addView(ui.text("MIUI 请允许自启动，并将省电策略设为“无限制”，避免锁屏后延迟转发。", 14f, R.color.muted))
            ui.gap(this, 16)
            permissionButton = ui.button("授权接收短信") { requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS), 1) }
            addView(permissionButton)
            ui.gap(this, 8)
            addView(ui.button("打开系统应用设置") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
            })
        })

        content.addView(ui.card().apply {
            addView(ui.text("待发队列", 17f, bold = true))
            ui.gap(this, 8)
            queueHint = ui.text("", 14f, R.color.muted)
            addView(queueHint)
            ui.gap(this, 16)
            retryButton = ui.button("重试待发消息") { retryQueue() }
            addView(retryButton)
            ui.gap(this, 8)
            clearButton = ui.button("清空待发队列", danger = true) { clearQueue() }
            addView(clearButton)
        })
        content.addView(ui.text("选中的短信会发送到所设飞书群，请仅使用可信私人群。暂停期间收到的短信不会补发。", 13f, R.color.muted))
        ui.gap(content, 12)
        content.addView(ui.text("本机暂存 · 成功后删除 · 不读取历史短信", 12f, R.color.muted))
        ui.gap(content, 16)
        val modes = arrayOf("system", "light", "dark")
        val names = arrayOf("跟随系统", "浅色", "深色")
        val appearance = getSharedPreferences("appearance", MODE_PRIVATE)
        val selected = modes.indexOf(appearance.getString("mode", "system")).coerceAtLeast(0)
        content.addView(ui.button("外观：${names[selected]}") {
            AlertDialog.Builder(this).setTitle("选择外观")
                .setSingleChoiceItems(names, selected) { dialog, which ->
                    appearance.edit().putString("mode", modes[which]).apply()
                    dialog.dismiss()
                    recreate()
                }.setNegativeButton("取消", null).show()
        })

        toggle.setOnCheckedChangeListener { _, checked -> if (!updatingToggle) changeEnabled(checked) }
        setConfigExpanded(savedInstanceState?.getBoolean("configExpanded")
            ?: !Protocol.validWebhook(State.prefs(this).getString("webhook", "").orEmpty()))
        renderStatus()
    }

    private fun credentialField(stableId: Int, placeholder: String) = EditText(this).apply {
        id = stableId
        hint = placeholder
        textSize = 14f
        setTextColor(ui.color(R.color.ink))
        setHintTextColor(ui.color(R.color.muted))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        transformationMethod = PasswordTransformationMethod.getInstance()
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setSingleLine(true)
        minHeight = ui.dp(52)
        setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
        background = ui.round(R.color.field, 10, R.color.line)
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    private fun revealButtonText() {
        configFields.findViewWithTag<Button>("reveal").text = if (showCredentials) "隐藏地址与密钥" else "显示地址与密钥"
    }

    private fun openConfigEditor() {
        if (configExpanded) { setConfigExpanded(false); return }
        synchronized(State.gate) {
            if (State.enabled(this)) {
                if (!State.prefs(this).edit().putBoolean("enabled", false).commit()) {
                    // commit 写盘失败时内存值仍可能已变化，恢复后继续锁住配置。
                    State.prefs(this).edit().putBoolean("enabled", true).apply()
                    toast("暂停失败，请重试后编辑")
                    renderStatus()
                    return
                }
                ForwardJob.cancel(this)
                toast("已暂停转发，可以编辑飞书配置")
            }
            setConfigExpanded(true)
            renderStatus()
        }
    }

    private fun setConfigExpanded(expanded: Boolean) {
        configExpanded = expanded
        configFields.visibility = if (expanded) View.VISIBLE else View.GONE
        configButton.text = when { expanded -> "收起"; State.enabled(this) -> "暂停编辑"; else -> "编辑" }
        if (!expanded) {
            showCredentials = false
            listOf(webhook, secret).forEach { it.transformationMethod = PasswordTransformationMethod.getInstance(); it.clearFocus() }
            revealButtonText()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(webhook.windowToken, 0)
        }
    }

    private fun saveConfig() {
        synchronized(State.gate) {
            when {
                State.enabled(this) -> toast("请先暂停转发，再保存")
                Store(this).use { it.count() } > 0 -> toast("请先清空待发队列，避免发往新的群")
                !Protocol.validWebhook(webhook.text.toString().trim()) -> {
                    webhook.error = "请输入有效的飞书机器人 HTTPS 地址"
                    webhook.requestFocus()
                }
                else -> {
                    val prefs = State.prefs(this)
                    val saved = prefs.edit().putString("webhook", webhook.text.toString().trim())
                        .putString("secret", secret.text.toString().trim())
                        .putLong("generation", prefs.getLong("generation", 0) + 1).commit()
                    if (saved) { webhook.error = null; setConfigExpanded(false); toast("飞书配置已保存，请开启自动转发") }
                    else toast("保存失败，请检查手机存储空间后重试")
                    renderStatus()
                }
            }
        }
    }

    private fun changeEnabled(checked: Boolean) {
        synchronized(State.gate) {
            if (!checked) {
                if (State.prefs(this).edit().putBoolean("enabled", false).commit()) ForwardJob.cancel(this)
                else toast("暂停状态保存失败，请重试")
                renderStatus()
                return
            }
            // 以实际保存状态驱动开关，避免确认前就显示为运行中。
            syncToggle()
            if (!hasSmsPermission()) {
                toast("请先授权接收短信")
                requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS), 1)
                return
            }
            if (!Protocol.validWebhook(State.prefs(this).getString("webhook", "").orEmpty())) {
                setConfigExpanded(true)
                webhook.error = "请先填写并保存飞书机器人地址"
                webhook.requestFocus()
                toast("请先保存飞书配置")
                return
            }
            AlertDialog.Builder(this).setTitle("开启自动转发")
                .setMessage("符合短信筛选设置的新短信，以及已有待发消息，将发送到已保存的飞书群，内容可能包含验证码。请确认群内都是可信成员。")
                .setPositiveButton("开启") { _, _ ->
                    synchronized(State.gate) {
                        val prefs = State.prefs(this)
                        if (hasSmsPermission() && Protocol.validWebhook(prefs.getString("webhook", "").orEmpty())) {
                            if (prefs.edit().putBoolean("enabled", true).putLong("generation", prefs.getLong("generation", 0) + 1).commit()) {
                                ForwardJob.schedule(this)
                            } else toast("开启失败，请重试")
                        }
                        renderStatus()
                    }
                }.setNegativeButton("取消", null).show()
        }
    }

    private fun sendTest() {
        if (!State.enabled(this)) { toast("请先开启自动转发"); return }
        AlertDialog.Builder(this).setTitle("发送测试消息")
            .setMessage("将向已保存的飞书群发送一条测试消息，不包含真实短信。")
            .setPositiveButton("发送") { _, _ ->
                try {
                    synchronized(State.gate) {
                        if (!State.enabled(this)) { toast("转发已暂停"); return@setPositiveButton }
                        Store(this).use { it.add("短信转发\n测试消息：安卓手机已准备好，收到此消息表示飞书连接成功。") }
                        State.status(this, "测试消息已入队，等待飞书确认")
                        ForwardJob.schedule(this)
                    }
                } catch (e: IllegalArgumentException) { toast(e.message.orEmpty()) }
                catch (_: Exception) { toast("测试消息入队失败，请检查存储空间") }
                renderStatus()
            }.setNegativeButton("取消", null).show()
    }

    private fun retryQueue() {
        synchronized(State.gate) {
            if (!State.enabled(this)) { toast("请先开启自动转发"); return }
            Store(this).use { it.retry() }
            State.status(this, "已安排重试")
            ForwardJob.schedule(this)
        }
        renderStatus()
    }

    private fun clearQueue() {
        if (State.enabled(this)) { toast("请先暂停转发"); return }
        AlertDialog.Builder(this).setTitle("清空待发队列？")
            .setMessage("删除全部待发消息后，无法补发。手机原来的短信会保留。")
            .setPositiveButton("清空") { _, _ ->
                synchronized(State.gate) {
                    if (State.enabled(this)) { toast("请先暂停转发"); return@setPositiveButton }
                    Store(this).use { it.clear() }
                    State.status(this, "已清空待发队列")
                }
                renderStatus()
            }.setNegativeButton("取消", null).show()
    }

    private fun hasSmsPermission() = checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
    private fun syncToggle() {
        updatingToggle = true
        toggle.isChecked = State.enabled(this)
        updatingToggle = false
    }
    private fun toast(value: String) { Toast.makeText(this, value, Toast.LENGTH_LONG).show() }
    private fun TextView.showText(value: String) { if (text.toString() != value) text = value }

    private fun renderStatus() {
        if (!::latest.isInitialized) return
        val prefs = State.prefs(this)
        val enabled = State.enabled(this)
        val permission = hasSmsPermission()
        val configured = Protocol.validWebhook(prefs.getString("webhook", "").orEmpty())
        val filter = runCatching { FilterSettings.load(this) }
        val count = Store(this).use { it.count() }
        syncToggle()
        stateTitle.showText(when { !enabled -> "转发已暂停"; !permission -> "等待短信授权"; else -> "转发已开启" })
        stateDetail.showText(when {
            !configured -> "先配置飞书机器人，再开启自动转发。"
            !permission -> "授权接收短信后，才能自动转发新短信。"
            filter.isFailure -> "短信筛选设置异常，请重新保存后再转发。"
            enabled && filter.getOrNull()?.enabled == true -> "按你选定的范围转发新短信。"
            enabled -> "新短信将自动发送到你的飞书群。"
            else -> "开启后自动转发，暂停期间的新短信不补发。"
        })
        filterSummary.showText(runCatching { FilterSettings.summary(this) }.getOrDefault("短信筛选设置异常，请重新设置并保存"))
        queueValue.showText("$count 条")
        permissionValue.showText(if (permission) "已授权" else "未授权")
        permissionValue.setTextColor(ui.color(if (permission) R.color.success else R.color.danger))
        permissionButton.visibility = if (permission) View.GONE else View.VISIBLE
        configSummary.showText(if (configured) "机器人已配置 · 地址已隐藏" else "尚未配置接收群")
        configButton.text = when { configExpanded -> "收起"; enabled -> "暂停编辑"; else -> "编辑" }
        configHint.showText(when {
            enabled -> "修改配置前，请先关闭上方的自动转发。"
            count > 0 -> "请先清空待发队列，再修改接收群。"
            else -> "修改后保存配置，再开启自动转发。机器人关键词可设为“短信转发”。"
        })
        ui.enabled(saveButton, !enabled && count == 0)
        ui.enabled(webhook, !enabled)
        ui.enabled(secret, !enabled)
        ui.enabled(testButton, enabled && configured)
        ui.enabled(retryButton, enabled && count > 0)
        ui.enabled(clearButton, !enabled && count > 0)
        queueHint.showText(when {
            count == 0 -> "暂无待发消息。断网时会暂存，恢复网络后自动重试。"
            enabled -> "$count 条消息等待发送。连续失败 8 次后，可在此手动重试。"
            else -> "$count 条消息已暂停发送。开启转发可继续，也可以清空。"
        })
        val message = prefs.getString("status", "").orEmpty()
        latest.showText(message.ifBlank { "还没有转发记录，收到新短信后会在这里显示发送结果。" })
        latest.setTextColor(ui.color(if (listOf("失败", "错误", "无效", "未能", "超过", "已满").any { it in message }) R.color.danger else R.color.ink))
        val time = prefs.getLong("statusTime", 0)
        latestTime.showText(if (time == 0L) "只显示状态，不展示短信正文" else "最近更新 · " + SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(time)))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("configExpanded", configExpanded)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() { super.onResume(); handler.removeCallbacks(refresh); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
}
