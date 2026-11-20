package com.kidbuddy.smsforwarder

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** 新建或编辑单个转发目的地（飞书 / 企业微信 / 钉钉 / 自定义 Webhook）。 */
class DestinationEditActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_DESTINATION_ID = "destination_id"
    }

    private lateinit var ui: Ui
    private lateinit var nameLayout: TextInputLayout
    private lateinit var nameInput: TextInputEditText
    private lateinit var webhookLayout: TextInputLayout
    private lateinit var webhookInput: TextInputEditText
    private lateinit var secretLayout: TextInputLayout
    private lateinit var secretInput: TextInputEditText
    private lateinit var errorText: TextView
    private lateinit var typeButtons: List<Pair<DestinationType, MaterialRadioButton>>

    private var editingId: String? = null
    private var selectedType: DestinationType = DestinationType.FEISHU
    private var binding = true

    override fun onCreate(savedInstanceState: Bundle?) {
        AppAppearance.apply(this)
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val config = runCatching { ConfigStore.load(this) }.getOrDefault(ForwardConfig())
        editingId = intent.getStringExtra(EXTRA_DESTINATION_ID)?.takeIf { it.isNotBlank() }
        val existing = editingId?.let { config.destinationById(it) }

        selectedType = savedInstanceState?.getString("type")?.let { DestinationType.fromId(it) }
            ?: existing?.type ?: DestinationType.FEISHU

        val root = ui.column().apply { setBackgroundColor(ui.color(R.color.page)) }
        val content = ui.column().apply { setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(16)) }
        ui.enableMotion(content)
        val scroll = ScrollView(this).apply {
            id = R.id.dest_edit_scroll
            isFillViewport = true
            addView(content)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(View(this).apply { setBackgroundColor(ui.color(R.color.line)) }, LinearLayout.LayoutParams(-1, ui.dp(1)))
        val footer = ui.column().apply {
            setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(14))
            setBackgroundColor(ui.color(R.color.surface))
        }
        footer.addView(ui.button("保存目的地", primary = true) { saveDestination() })
        root.addView(footer)
        setContentView(root)

        content.addView(ui.topBar(if (existing == null) "添加目的地" else "编辑目的地") { finish() })
        content.addView(ui.text("地址与密钥仅保存在本机应用私有目录，不会写入日志。", 13f, R.color.muted))
        ui.gap(content, 14)

        errorText = ui.text("", 13f, R.color.danger, bold = true).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            background = ui.round(R.color.danger_surface, 12)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(14) }
        }
        content.addView(errorText)

        ui.sectionTitle(content, "基础信息")
        content.addView(ui.card(padH = 16, padV = 16) {
            val (nl, ni) = ui.textField(
                stableId = R.id.dest_name_input,
                label = "目的地名称 *",
                placeholder = "例如：验证码飞书群、运维企微群",
                value = savedInstanceState?.getString("name") ?: existing?.name.orEmpty(),
                singleLine = true
            )
            nameLayout = nl
            nameInput = ni
            addView(nameLayout)

            ui.gap(this, 10)
            addView(ui.fieldLabel("平台类型", required = true))
            ui.gap(this, 8)
            val typeOptions = listOf(
                DestinationType.FEISHU to R.id.dest_type_feishu,
                DestinationType.WECOM to R.id.dest_type_wecom,
                DestinationType.DINGTALK to R.id.dest_type_dingtalk,
                DestinationType.CUSTOM to R.id.dest_type_custom,
            )
            val group = RadioGroup(this@DestinationEditActivity).apply { isSaveEnabled = false }
            typeButtons = typeOptions.map { (type, stableId) ->
                val btn = ui.optionRadio(type.displayName, stableId).apply { minHeight = ui.dp(48) }
                group.addView(btn)
                type to btn
            }
            group.check(typeOptions.first { it.first == selectedType }.second)
            group.setOnCheckedChangeListener { _, checkedId ->
                if (!binding) {
                    selectedType = typeOptions.first { it.second == checkedId }.first
                    applyTypeUi()
                }
            }
            addView(group)
        })

        ui.sectionTitle(content, "连接凭证", "点击输入框右侧眼睛图标可切换明文查看")
        content.addView(ui.card(padH = 16, padV = 16) {
            ui.enableMotion(this)
            val (wl, wi) = ui.textField(
                stableId = R.id.dest_webhook_input,
                label = "Webhook 地址 *",
                placeholder = selectedType.webhookPlaceholder,
                value = savedInstanceState?.getString("webhook") ?: existing?.webhook.orEmpty(),
                helper = selectedType.webhookHint,
                singleLine = true,
                password = true
            )
            webhookLayout = wl
            webhookInput = wi
            addView(webhookLayout)

            ui.gap(this, 10)
            val (sl, si) = ui.textField(
                stableId = R.id.dest_secret_input,
                label = "签名密钥 · 选填",
                placeholder = "启用签名校验时填写",
                value = savedInstanceState?.getString("secret") ?: existing?.secret.orEmpty(),
                helper = selectedType.secretHint,
                singleLine = true,
                password = true
            )
            secretLayout = sl
            secretInput = si
            addView(secretLayout)
        })

        binding = false
        applyTypeUi()
    }

    private fun applyTypeUi() {
        typeButtons.forEach { (type, button) ->
            val selected = type == selectedType
            button.background = if (selected) ui.round(R.color.accent_surface, 12, R.color.accent)
            else ui.round(R.color.field, 12, R.color.line)
        }
        webhookLayout.placeholderText = selectedType.webhookPlaceholder
        webhookLayout.helperText = selectedType.webhookHint
        webhookLayout.error = null
        secretLayout.visibility = if (selectedType.supportsSecret) View.VISIBLE else View.GONE
        secretLayout.helperText = selectedType.secretHint
        secretLayout.error = null
        showError(null)
    }

    private fun showError(msg: String?) {
        errorText.text = msg.orEmpty()
        errorText.visibility = if (msg.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun saveDestination() {
        nameLayout.error = null
        webhookLayout.error = null
        val trimmedName = nameInput.text?.toString().orEmpty().trim()
        val trimmedWebhook = webhookInput.text?.toString().orEmpty().trim()
        val trimmedSecret = if (selectedType.supportsSecret) secretInput.text?.toString().orEmpty().trim() else ""
        val candidate = Destination(
            id = editingId ?: ConfigStore.newId(),
            name = trimmedName,
            type = selectedType,
            webhook = trimmedWebhook,
            secret = trimmedSecret,
        )
        candidate.validationError()?.let { msg ->
            showError(msg)
            if (trimmedName.isEmpty()) {
                nameLayout.error = msg
                nameInput.requestFocus()
            } else if (!DestinationProtocol.validWebhook(selectedType, trimmedWebhook)) {
                webhookLayout.error = msg
                webhookInput.requestFocus()
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            return
        }

        synchronized(State.gate) {
            val current = runCatching { ConfigStore.load(this) }.getOrDefault(ForwardConfig())
            val nextDestinations = if (editingId != null && current.destinations.any { it.id == editingId }) {
                current.destinations.map { if (it.id == editingId) candidate else it }
            } else {
                current.destinations + candidate
            }
            val nextConfig = current.copy(destinations = nextDestinations)
            nextConfig.validationError()?.let { msg ->
                showError(msg)
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                return
            }
            if (!ConfigStore.save(this, nextConfig)) {
                showError("保存失败，请检查手机存储空间后重试")
                Toast.makeText(this, "保存失败，请重试", Toast.LENGTH_LONG).show()
                return
            }
        }
        Toast.makeText(this, "目的地「${candidate.name}」已保存", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("name", nameInput.text?.toString().orEmpty())
        outState.putString("type", selectedType.id)
        outState.putString("webhook", webhookInput.text?.toString().orEmpty())
        outState.putString("secret", secretInput.text?.toString().orEmpty())
        super.onSaveInstanceState(outState)
    }
}
