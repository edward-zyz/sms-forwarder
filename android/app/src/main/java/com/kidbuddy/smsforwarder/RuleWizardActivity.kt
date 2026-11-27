package com.kidbuddy.smsforwarder

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * 「新建转发规则」2 步向导：
 * 第 1 步：选择已有渠道，或内联新建渠道（含平台教程弹窗与当场发送测试消息）；
 * 第 2 步：复用 FilterConditionSection 配置触发条件、自动生成规则名、试一条短信并原子保存启用。
 */
class RuleWizardActivity : AppCompatActivity() {
    private lateinit var ui: Ui
    private lateinit var state: RuleWizardState
    private lateinit var scroll: ScrollView

    // 顶部 2 步进度指示
    private lateinit var step1Badge: TextView
    private lateinit var step1Label: TextView
    private lateinit var step1Bar: LinearLayout
    private lateinit var stepConnector: View
    private lateinit var step2Badge: TextView
    private lateinit var step2Label: TextView

    // 第 1 步视图
    private lateinit var step1Container: LinearLayout
    private lateinit var channelErrorBox: TextView
    private lateinit var existingSection: LinearLayout
    private lateinit var existingListContainer: LinearLayout
    private lateinit var switchToCreateButton: MaterialButton
    private lateinit var backToExistingBar: LinearLayout
    private lateinit var createSection: LinearLayout
    private lateinit var destNameLayout: TextInputLayout
    private lateinit var destNameInput: TextInputEditText
    private lateinit var typeGroup: RadioGroup
    private lateinit var typeButtons: List<Triple<DestinationType, Int, MaterialRadioButton>>
    private lateinit var guideEntryRow: LinearLayout
    private lateinit var guideSubtitleText: TextView
    private lateinit var webhookLayout: TextInputLayout
    private lateinit var webhookInput: TextInputEditText
    private lateinit var secretLayout: TextInputLayout
    private lateinit var secretInput: TextInputEditText
    private lateinit var sendTestChannelButton: MaterialButton
    private lateinit var channelTestStatus: TextView

    // 第 2 步视图
    private lateinit var step2Container: LinearLayout
    private lateinit var selectedChannelTile: LinearLayout
    private lateinit var selectedChannelTitle: TextView
    private lateinit var selectedChannelSubtitle: TextView
    private lateinit var conditionErrorBox: TextView
    private lateinit var ruleNameLayout: TextInputLayout
    private lateinit var ruleNameInput: TextInputEditText
    private lateinit var resetRuleNameButton: TextView
    private lateinit var previewText: TextView
    private lateinit var conditionSection: FilterConditionSection

    // 底部操作栏
    private lateinit var footerStep1: LinearLayout
    private lateinit var footerStep2: LinearLayout
    private lateinit var finishButton: MaterialButton

    private val mainHandler = Handler(Looper.getMainLooper())
    private var binding = true
    private val channelTest = ChannelTestMachine()
    @Volatile private var activeTestConnection: HttpsURLConnection? = null

    private var guideDialog: BottomSheetDialog? = null
    private var guideDialogType: DestinationType? = null

    private var testOpen = false
    private var testSender = ""
    private var testBody = ""
    private var testResult = ""
    private var testRules: FilterRules? = null
    private var testSheet: BottomSheetDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        AppAppearance.apply(this)
        super.onCreate(savedInstanceState)
        ui = Ui(this)

        val configResult = runCatching { ConfigStore.load(this) }
        val existingDestinations = configResult.getOrDefault(ForwardConfig()).destinations
        state = RuleWizardState(existingDestinations, ConfigStore::newId)

        if (savedInstanceState != null) {
            val savedStep = savedInstanceState.getString(KEY_STEP)
                ?.let { runCatching { RuleWizardState.Step.valueOf(it) }.getOrNull() }
                ?: RuleWizardState.Step.CHANNEL
            val savedMode = savedInstanceState.getString(KEY_CHANNEL_MODE)
                ?.let { runCatching { RuleWizardState.ChannelMode.valueOf(it) }.getOrNull() }
                ?: state.channelMode
            val savedDraftType = DestinationType.fromId(savedInstanceState.getString(KEY_DRAFT_TYPE))
                ?: DestinationType.FEISHU
            val draft = RuleWizardState.ChannelDraft(
                id = savedInstanceState.getString(KEY_DRAFT_ID).orEmpty().ifBlank { ConfigStore.newId() },
                type = savedDraftType,
                name = savedInstanceState.getString(KEY_DRAFT_NAME).orEmpty(),
                webhook = savedInstanceState.getString(KEY_DRAFT_WEBHOOK).orEmpty(),
                secret = savedInstanceState.getString(KEY_DRAFT_SECRET).orEmpty(),
            )
            state.restore(
                step = savedStep,
                channelMode = savedMode,
                selectedDestinationId = savedInstanceState.getString(KEY_SELECTED_DEST_ID).orEmpty(),
                channelDraft = draft,
                ruleName = savedInstanceState.getString(KEY_RULE_NAME).orEmpty(),
                ruleNameManuallyEdited = savedInstanceState.getBoolean(KEY_RULE_NAME_MANUAL, false),
                ruleEnabled = savedInstanceState.getBoolean(KEY_RULE_ENABLED, true),
            )
            testSender = savedInstanceState.getString(KEY_TEST_SENDER).orEmpty()
            testBody = savedInstanceState.getString(KEY_TEST_BODY).orEmpty()
            testResult = savedInstanceState.getString(KEY_TEST_RESULT).orEmpty()
            testRules = savedInstanceState.getBundle(KEY_TEST_RULES)?.let(FilterConditionSection::readRules)
        }

        val root = ui.column().apply { setBackgroundColor(ui.color(R.color.page)) }
        val content = ui.column().apply { setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(20)) }
        ui.enableMotion(content)
        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // 底部固定栏
        root.addView(View(this).apply { setBackgroundColor(ui.color(R.color.line)) }, LinearLayout.LayoutParams(-1, ui.dp(1)))
        val footerWrapper = ui.column().apply {
            setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(14))
            setBackgroundColor(ui.color(R.color.surface))
        }
        footerStep1 = ui.column().apply {
            addView(ui.button("下一步：设置触发条件", primary = true) { proceedToStep2() })
        }
        footerStep2 = ui.column().apply {
            val secondaryRow = ui.row()
            secondaryRow.addView(
                ui.button("上一步") { handleBack() }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) }
                },
            )
            secondaryRow.addView(
                ui.button("试一条短信") { showTestSmsSheet() }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                },
            )
            addView(secondaryRow)
            ui.gap(this, 8)
            finishButton = ui.button("完成并启用", primary = true) { finishAndSave() }
            addView(finishButton)
        }
        footerWrapper.addView(footerStep1)
        footerWrapper.addView(footerStep2)
        root.addView(footerWrapper)
        setContentView(root)

        // 顶栏 + 2 步进度条
        content.addView(ui.topBar("新建转发规则") { handleBack() })
        content.addView(buildProgressIndicator())
        ui.gap(content, 12)

        // 构建第 1 步与第 2 步视图容器
        step1Container = buildStep1View()
        step2Container = buildStep2View(savedInstanceState)
        content.addView(step1Container)
        content.addView(step2Container)

        if (configResult.isFailure) {
            showChannelError("已保存配置读取异常，可重新创建或返回检查。")
        }

        // 在控件绑定好之后注入草稿与状态
        syncAllViewsFromState(syncTextFields = true)
        binding = false
        render()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBack()
            }
        })

        savedInstanceState?.getString(KEY_GUIDE_TYPE)?.let { DestinationType.fromId(it) }?.let { type ->
            showGuideSheet(type)
        }
        if (savedInstanceState?.getBoolean(KEY_TEST_OPEN) == true && state.step == RuleWizardState.Step.CONDITION) {
            showTestSmsSheet()
        }
    }

    override fun onResume() {
        super.onResume()
        val latestDestinations = runCatching { ConfigStore.load(this).destinations }.getOrNull() ?: return
        if (latestDestinations != state.existingDestinations) {
            state.refreshDestinations(latestDestinations)
            if (state.step == RuleWizardState.Step.CONDITION && !state.ruleNameManuallyEdited) {
                state.onConditionChanged(conditionSection.currentEditor())
                syncRuleNameInputFromState()
            }
            render()
        }
    }

    private fun buildProgressIndicator(): View = ui.card(padH = 14, padV = 12, bottomMargin = 6) {
        addView(ui.row().apply {
            step1Badge = stepBadgeView("1")
            step1Label = ui.text("1 选择渠道", 13.5f, R.color.ink, bold = true)
            step1Bar = ui.row().apply {
                minimumHeight = ui.dp(44)
                setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(4))
                background = ui.transparentRipple(10)
                addView(step1Badge)
                addView(step1Label, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(6) })
                setOnClickListener {
                    if (state.step == RuleWizardState.Step.CONDITION) {
                        ui.haptic(this)
                        handleBack()
                    }
                }
            }
            addView(step1Bar)

            stepConnector = View(this@RuleWizardActivity).apply {
                setBackgroundColor(ui.color(R.color.line))
                layoutParams = LinearLayout.LayoutParams(0, ui.dp(2), 1f).apply {
                    marginStart = ui.dp(8)
                    marginEnd = ui.dp(8)
                }
            }
            addView(stepConnector)

            step2Badge = stepBadgeView("2")
            step2Label = ui.text("2 设置触发条件", 13.5f, R.color.muted)
            addView(ui.row().apply {
                minimumHeight = ui.dp(44)
                setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(4))
                addView(step2Badge)
                addView(step2Label, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(6) })
            })
        })
    }

    private fun stepBadgeView(label: String) = TextView(this).apply {
        text = label
        textSize = 12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(ui.dp(22), ui.dp(22))
    }

    private fun buildStep1View(): LinearLayout = ui.column().apply {
        ui.enableMotion(this)

        channelErrorBox = ui.text("", 13f, R.color.danger, bold = true).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            background = ui.round(R.color.danger_surface, 12)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(12) }
        }
        addView(channelErrorBox)

        // 已有渠道选择区
        existingSection = ui.column().apply {
            ui.sectionTitle(this, "选择已有渠道", "点选下方渠道直接进入下一步，或新建一个渠道")
            addView(ui.listCard(bottomMargin = 12) {
                existingListContainer = this
            })
            switchToCreateButton = ui.button("+ 新建渠道") {
                state.switchChannelMode(RuleWizardState.ChannelMode.CREATE_NEW)
                showChannelError(null)
                render()
            }
            addView(switchToCreateButton)
        }
        addView(existingSection)

        // 内联新建渠道表单区
        createSection = ui.column().apply {
            ui.enableMotion(this)

            backToExistingBar = ui.row().apply {
                minimumHeight = ui.dp(44)
                setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
                background = ui.ripple(R.color.surface, 12)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) }
                addView(ui.icon(R.drawable.ic_back, 16, R.color.accent).apply {
                    layoutParams = LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)).apply { marginEnd = ui.dp(6) }
                })
                addView(ui.text("返回选择已有渠道", 14f, R.color.accent, bold = true))
                setOnClickListener {
                    ui.haptic(this)
                    state.switchChannelMode(RuleWizardState.ChannelMode.SELECT_EXISTING)
                    showChannelError(null)
                    render()
                }
            }
            addView(backToExistingBar)

            ui.sectionTitle(this, "新建转发渠道", "填写渠道配置并可当场测试，完成向导后统一保存")
            addView(ui.card(padH = 16, padV = 16) {
                val (nl, ni) = ui.textField(
                    label = "渠道名称 *",
                    placeholder = "例如：验证码飞书群、运维企微群",
                    value = state.channelDraft.name,
                    singleLine = true,
                )
                destNameLayout = nl
                destNameInput = ni
                addView(destNameLayout)

                ui.gap(this, 10)
                addView(ui.fieldLabel("平台类型", required = true))
                ui.gap(this, 8)

                val group = RadioGroup(this@RuleWizardActivity).apply { isSaveEnabled = false }
                typeGroup = group
                typeButtons = DestinationType.entries.map { type ->
                    val radioId = View.generateViewId()
                    val btn = ui.optionRadio(type.displayName, radioId).apply { minHeight = ui.dp(48) }
                    group.addView(btn)
                    Triple(type, radioId, btn)
                }
                group.setOnCheckedChangeListener { _, checkedId ->
                    if (!binding) {
                        val picked = typeButtons.firstOrNull { it.second == checkedId }?.first ?: return@setOnCheckedChangeListener
                        state.updateDraftType(picked)
                        resetChannelTestFeedback()
                        showChannelError(null)
                        applyDraftTypeUi()
                    }
                }
                addView(group)
            })

            ui.sectionTitle(this, "连接凭证", "点击输入框右侧眼睛图标可切换明文查看")
            addView(ui.card(padH = 16, padV = 16) {
                ui.enableMotion(this)
                guideSubtitleText = ui.text("", 12.5f, R.color.muted)
                guideEntryRow = ui.row().apply {
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = ui.dp(52)
                    setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
                    background = ui.ripple(R.color.accent_surface, 12, R.color.accent)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        ui.haptic(this)
                        showGuideSheet(state.channelDraft.type)
                    }
                    addView(ui.iconTile(R.drawable.ic_network, R.color.accent, sizeDp = 32, iconSizeDp = 17, radiusDp = 9))
                    addView(ui.column().apply {
                        addView(ui.text("如何获取配置？", 14.5f, R.color.ink, bold = true))
                        ui.gap(this, 2)
                        addView(guideSubtitleText)
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(ui.text("查看配置教程", 13f, R.color.accent, bold = true).apply {
                        setPadding(ui.dp(8), 0, 0, 0)
                    })
                    addView(ui.chevronRight(R.color.accent))
                }
                addView(guideEntryRow)
                ui.gap(this, 12)

                val (wl, wi) = ui.textField(
                    label = "Webhook 地址 *",
                    placeholder = state.channelDraft.type.webhookPlaceholder,
                    value = state.channelDraft.webhook,
                    helper = state.channelDraft.type.webhookHint,
                    singleLine = true,
                    password = true,
                )
                webhookLayout = wl
                webhookInput = wi
                addView(webhookLayout)

                ui.gap(this, 10)
                val (sl, si) = ui.textField(
                    label = "签名密钥 · 选填",
                    placeholder = "启用签名校验时填写",
                    value = state.channelDraft.secret,
                    helper = state.channelDraft.type.secretHint,
                    singleLine = true,
                    password = true,
                )
                secretLayout = sl
                secretInput = si
                addView(secretLayout)

                ui.gap(this, 12)
                sendTestChannelButton = ui.button("发送测试消息") { sendTestToDraftChannel() }
                addView(sendTestChannelButton)

                channelTestStatus = ui.text("", 13f, R.color.muted).apply {
                    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                    setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
                    visibility = View.GONE
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) }
                }
                addView(channelTestStatus)
            })

            destNameInput.watch {
                state.updateDraftName(it)
                destNameLayout.error = null
                showChannelError(null)
            }
            webhookInput.watch {
                state.updateDraftWebhook(it)
                webhookLayout.error = null
                showChannelError(null)
                resetChannelTestFeedback()
            }
            secretInput.watch {
                state.updateDraftSecret(it)
                secretLayout.error = null
                showChannelError(null)
                resetChannelTestFeedback()
            }
        }
        addView(createSection)
    }

    private fun buildStep2View(savedInstanceState: Bundle?): LinearLayout = ui.column().apply {
        ui.enableMotion(this)

        // 已选渠道摘要条
        addView(ui.card(padH = 16, padV = 12) {
            addView(ui.row().apply {
                selectedChannelTile = ui.iconTile(R.drawable.ic_send, R.color.tile_blue, sizeDp = 34, iconSizeDp = 17, radiusDp = 9)
                addView(selectedChannelTile)
                addView(ui.column().apply {
                    selectedChannelTitle = ui.text("", 14.5f, R.color.ink, bold = true)
                    selectedChannelSubtitle = ui.text("", 12.5f, R.color.muted)
                    addView(selectedChannelTitle)
                    ui.gap(this, 2)
                    addView(selectedChannelSubtitle)
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) })
                addView(ui.ghostButton("更换", R.color.accent) {
                    handleBack()
                }.apply {
                    minHeight = ui.dp(44)
                    contentDescription = "更换转发渠道"
                })
            })
        })

        conditionErrorBox = ui.text("", 13f, R.color.danger, bold = true).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            background = ui.round(R.color.danger_surface, 12)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(12) }
        }
        addView(conditionErrorBox)

        // 规则名称卡片
        ui.sectionTitle(this, "规则名称")
        addView(ui.card(padH = 16, padV = 14) {
            val (rl, ri) = ui.textField(
                label = "规则名称 *",
                placeholder = "例如：验证码短信、美团验证码",
                value = state.ruleName,
                helper = "默认根据条件与渠道自动生成，您也可以自由修改。",
                singleLine = true,
            )
            ruleNameLayout = rl
            ruleNameInput = ri
            addView(ruleNameLayout)

            resetRuleNameButton = ui.ghostButton("使用推荐名称", R.color.accent) {
                state.resetRuleNameToSuggested(conditionSection.currentEditor())
                syncRuleNameInputFromState()
                renderStep2Summary()
            }.apply {
                minHeight = ui.dp(44)
                visibility = View.GONE
            }
            addView(resetRuleNameButton)
        })

        // 实时规则效果预览卡
        addView(ui.card(padH = 18, padV = 14) {
            addView(ui.row().apply {
                addView(View(this@RuleWizardActivity).apply {
                    background = ui.round(R.color.accent, 99)
                    layoutParams = LinearLayout.LayoutParams(ui.dp(3), ui.dp(14)).apply { marginEnd = ui.dp(8) }
                })
                addView(ui.text("规则效果预览", 14f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            })
            ui.gap(this, 6)
            previewText = ui.text("", 13.5f, R.color.muted).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            addView(previewText)
        })

        // 复用 FilterConditionSection（默认「只转验证码 · 推荐」）
        conditionSection = FilterConditionSection(this@RuleWizardActivity).apply {
            bind(FilterEditor.forNewRule(), advancedExpanded = false)
            savedInstanceState?.getBundle(KEY_CONDITION_STATE)?.let { restoreInstanceState(it) }
            setOnChangeListener { editor ->
                state.onConditionChanged(editor)
                syncRuleNameInputFromState()
                renderStep2Summary()
            }
        }
        addView(conditionSection)

        ruleNameInput.watch {
            state.updateRuleNameManual(it)
            ruleNameLayout.error = null
            showConditionError(null)
            renderStep2Summary()
        }
    }

    private fun syncAllViewsFromState(syncTextFields: Boolean) {
        binding = true
        if (syncTextFields) {
            if (destNameInput.text?.toString() != state.channelDraft.name) {
                destNameInput.setText(state.channelDraft.name)
            }
            if (webhookInput.text?.toString() != state.channelDraft.webhook) {
                webhookInput.setText(state.channelDraft.webhook)
            }
            if (secretInput.text?.toString() != state.channelDraft.secret) {
                secretInput.setText(state.channelDraft.secret)
            }
            if (ruleNameInput.text?.toString() != state.ruleName) {
                ruleNameInput.setText(state.ruleName)
            }
        }
        val targetId = typeButtons.firstOrNull { it.first == state.channelDraft.type }?.second
        if (targetId != null && typeGroup.checkedRadioButtonId != targetId) {
            typeGroup.check(targetId)
        }
        binding = false
        applyDraftTypeUi()
    }

    private fun applyDraftTypeUi() {
        val currentType = state.channelDraft.type
        typeButtons.forEach { (type, _, button) ->
            val selected = type == currentType
            button.background = if (selected) ui.round(R.color.accent_surface, 12, R.color.accent)
            else ui.round(R.color.field, 12, R.color.line)
        }
        guideSubtitleText.text = "查看「${currentType.displayName}」群机器人与安全配置步骤"
        guideEntryRow.contentDescription = "如何获取配置？查看${currentType.displayName}配置教程"
        webhookLayout.placeholderText = currentType.webhookPlaceholder
        webhookLayout.helperText = currentType.webhookHint
        webhookLayout.error = null
        secretLayout.visibility = if (currentType.supportsSecret) View.VISIBLE else View.GONE
        secretLayout.helperText = currentType.secretHint
        secretLayout.error = null
    }

    private fun syncRuleNameInputFromState() {
        val target = state.ruleName
        if (ruleNameInput.text?.toString() != target) {
            binding = true
            ruleNameInput.setText(target)
            ruleNameInput.setSelection(target.length)
            binding = false
        }
        ruleNameLayout.error = null
    }

    private fun render() {
        val isStep1 = state.step == RuleWizardState.Step.CHANNEL
        renderProgressBar(isStep1)

        step1Container.visibility = if (isStep1) View.VISIBLE else View.GONE
        step2Container.visibility = if (isStep1) View.GONE else View.VISIBLE
        footerStep1.visibility = if (isStep1) View.VISIBLE else View.GONE
        footerStep2.visibility = if (isStep1) View.GONE else View.VISIBLE

        if (isStep1) {
            renderStep1()
        } else {
            renderStep2Summary()
        }
    }

    private fun renderProgressBar(isStep1: Boolean) {
        if (isStep1) {
            step1Badge.text = "1"
            step1Badge.setTextColor(ui.color(R.color.on_accent))
            step1Badge.background = circleBadge(R.color.accent)
            step1Label.setTextColor(ui.color(R.color.ink))
            step1Label.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            step1Bar.isClickable = false
            step1Bar.contentDescription = "当前第 1 步（共 2 步）：选择渠道"

            stepConnector.setBackgroundColor(ui.color(R.color.line))

            step2Badge.text = "2"
            step2Badge.setTextColor(ui.color(R.color.muted))
            step2Badge.background = circleBadge(R.color.neutral_surface)
            step2Label.setTextColor(ui.color(R.color.muted))
            step2Label.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        } else {
            step1Badge.text = "✓"
            step1Badge.setTextColor(ui.color(R.color.accent))
            step1Badge.background = circleBadge(R.color.accent_surface, R.color.accent)
            step1Label.setTextColor(ui.color(R.color.accent))
            step1Label.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            step1Bar.isClickable = true
            step1Bar.contentDescription = "已完成第 1 步：选择渠道，点按可返回修改"

            stepConnector.setBackgroundColor(ui.color(R.color.accent))

            step2Badge.text = "2"
            step2Badge.setTextColor(ui.color(R.color.on_accent))
            step2Badge.background = circleBadge(R.color.accent)
            step2Label.setTextColor(ui.color(R.color.ink))
            step2Label.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
    }

    private fun circleBadge(fillRes: Int, strokeRes: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(ui.color(fillRes))
        strokeRes?.let { setStroke(ui.dp(1), ui.color(it)) }
    }

    private fun renderStep1() {
        val hasExisting = state.hasExistingDestinations()
        val selectMode = hasExisting && state.channelMode == RuleWizardState.ChannelMode.SELECT_EXISTING

        existingSection.visibility = if (selectMode) View.VISIBLE else View.GONE
        createSection.visibility = if (selectMode) View.GONE else View.VISIBLE
        backToExistingBar.visibility = if (!selectMode && hasExisting) View.VISIBLE else View.GONE

        if (selectMode) {
            renderExistingDestinationsList()
        }
    }

    private fun renderExistingDestinationsList() {
        existingListContainer.removeAllViews()
        val list = state.existingDestinations
        list.forEachIndexed { idx, dest ->
            if (idx > 0) ui.insetDivider(existingListContainer, startDp = 62, endDp = 18)
            val isSelected = dest.id == state.selectedDestinationId
            val secretStatus = when {
                !dest.type.supportsSecret -> "无需签名"
                dest.secret.isNotBlank() -> "已加签"
                else -> "未加签"
            }
            val row = ui.row().apply {
                minimumHeight = ui.dp(60)
                setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
                background = if (isSelected) {
                    ui.ripple(R.color.accent_surface, 0)
                } else {
                    ui.transparentRipple(0)
                }
                addView(ui.iconTile(R.drawable.ic_send, tileColorFor(dest.type), sizeDp = 36, iconSizeDp = 18, radiusDp = 10))
                addView(ui.column().apply {
                    addView(ui.text(dest.name, 15.5f, R.color.ink, bold = true))
                    ui.gap(this, 2)
                    addView(ui.text("${dest.type.displayName} · $secretStatus", 12.5f, R.color.muted))
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) })
                if (isSelected) {
                    addView(ui.text("已选", 12.5f, R.color.accent, bold = true).apply {
                        setPadding(0, 0, ui.dp(4), 0)
                    })
                }
                addView(ui.chevronRight(if (isSelected) R.color.accent else R.color.subtle))
                contentDescription = "选择${dest.type.displayName}渠道 ${dest.name} 并进入下一步"
                setOnClickListener {
                    ui.haptic(this)
                    state.selectExistingDestination(dest.id)
                    proceedToStep2()
                }
            }
            existingListContainer.addView(row)
        }
    }

    private fun renderStep2Summary() {
        val dest = state.selectedDestination()
        ui.styleIconTile(selectedChannelTile, tileColorFor(dest.type), radiusDp = 9)
        selectedChannelTitle.text = "发往 ${dest.type.displayName}「${dest.name}」"
        selectedChannelSubtitle.text = if (state.channelMode == RuleWizardState.ChannelMode.CREATE_NEW) {
            "新建渠道 · 完成向导时与规则一起保存"
        } else {
            "已有渠道 · 仅新建并启用规则"
        }

        val suggested = conditionSection.currentEditor().suggestRuleName(state.selectedDestinationName())
        resetRuleNameButton.visibility = if (state.ruleNameManuallyEdited && state.ruleName.trim() != suggested) {
            View.VISIBLE
        } else {
            View.GONE
        }

        previewText.text = FilterDescriptions.summarizeForDestination(
            conditionSection.currentRules(),
            dest,
            state.ruleEnabled,
        )
    }

    private fun proceedToStep2() {
        showChannelError(null)
        destNameLayout.error = null
        webhookLayout.error = null
        secretLayout.error = null

        when (val res = state.proceedToConditionStep(conditionSection.currentEditor())) {
            is RuleWizardState.NextStepResult.Invalid -> {
                val err = res.error
                showChannelError(err.message)
                when (err.field) {
                    RuleWizardState.ChannelErrorField.NAME -> {
                        destNameLayout.error = err.message
                        destNameInput.requestFocus()
                    }
                    RuleWizardState.ChannelErrorField.WEBHOOK -> {
                        webhookLayout.error = err.message
                        webhookInput.requestFocus()
                    }
                    RuleWizardState.ChannelErrorField.SECRET -> {
                        secretLayout.error = err.message
                        secretInput.requestFocus()
                    }
                    RuleWizardState.ChannelErrorField.SELECTION -> Unit
                }
                Toast.makeText(this, err.message, Toast.LENGTH_LONG).show()
            }
            is RuleWizardState.NextStepResult.Success -> {
                syncRuleNameInputFromState()
                showConditionError(null)
                render()
                scroll.post { scroll.scrollTo(0, 0) }
            }
        }
    }

    private fun handleBack() {
        if (state.goBack()) {
            showConditionError(null)
            render()
            scroll.post { scroll.scrollTo(0, 0) }
        } else {
            finish()
        }
    }

    private fun finishAndSave() {
        ruleNameLayout.error = null
        showConditionError(null)

        val filterRules = conditionSection.currentRules()
        filterRules.validationError()?.let { err ->
            showConditionError(err)
            Toast.makeText(this, err, Toast.LENGTH_LONG).show()
            return
        }

        synchronized(State.gate) {
            val currentConfig = runCatching { ConfigStore.load(this) }.getOrElse {
                showConditionError("读取当前配置失败，请检查已有目的地与规则设置")
                return
            }
            when (val payload = state.buildSavePayload(currentConfig, filterRules, ConfigStore::newId, ConfigStore::newId)) {
                is RuleWizardState.SavePayloadResult.Error -> {
                    if (payload.backToChannelStep) {
                        state.goBack()
                        render()
                        showChannelError(payload.message)
                    } else {
                        if (payload.isRuleNameError) {
                            ruleNameLayout.error = payload.message
                            ruleNameInput.requestFocus()
                        }
                        showConditionError(payload.message)
                    }
                    Toast.makeText(this, payload.message, Toast.LENGTH_LONG).show()
                    return
                }
                is RuleWizardState.SavePayloadResult.Success -> {
                    if (!ConfigStore.save(this, payload.config)) {
                        val msg = "保存失败，请检查手机存储空间后重试"
                        showConditionError(msg)
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        return
                    }
                    val successMsg = if (payload.createdDestination) {
                        "已创建渠道「${payload.destination.name}」并启用规则「${payload.rule.name}」"
                    } else {
                        "已新建并启用规则「${payload.rule.name}」"
                    }
                    Toast.makeText(this, successMsg, Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun sendTestToDraftChannel() {
        if (channelTest.isSending) return
        destNameLayout.error = null
        webhookLayout.error = null
        secretLayout.error = null
        showChannelError(null)

        val target = when (val res = state.buildTestTarget()) {
            is RuleWizardState.TestTargetResult.Invalid -> {
                val err = res.error
                when (err.field) {
                    RuleWizardState.ChannelErrorField.NAME -> {
                        destNameLayout.error = err.message
                        destNameInput.requestFocus()
                    }
                    RuleWizardState.ChannelErrorField.WEBHOOK -> {
                        webhookLayout.error = err.message
                        webhookInput.requestFocus()
                    }
                    RuleWizardState.ChannelErrorField.SECRET -> {
                        secretLayout.error = err.message
                        secretInput.requestFocus()
                    }
                    RuleWizardState.ChannelErrorField.SELECTION -> Unit
                }
                showChannelTestFeedback(Ui.Tone.DANGER, err.message)
                return
            }
            is RuleWizardState.TestTargetResult.Ready -> res.destination
        }

        val deviceLabel = State.deviceLabel(this)
        val deviceLine = if (deviceLabel.isNotEmpty()) "\n接收手机：$deviceLabel" else ""
        val testText = "短信转发$deviceLine\n测试消息：安卓手机已准备好，收到此消息表示${target.type.displayName}「${target.name}」连接成功。"
        val request = DestinationProtocol.buildRequest(target, testText, System.currentTimeMillis())
        if (request.body.size > DestinationProtocol.MAX_PAYLOAD_BYTES) {
            showChannelTestFeedback(Ui.Tone.DANGER, "测试消息体积超过平台限制")
            return
        }

        val seq = channelTest.start() ?: return
        sendTestChannelButton.isEnabled = false
        sendTestChannelButton.text = "正在发送测试消息…"
        showChannelTestFeedback(Ui.Tone.NEUTRAL, "正在向「${target.type.displayName}」发送测试消息，请稍候…")

        Thread {
            var conn: HttpsURLConnection? = null
            val outcome: Pair<Boolean, String> = try {
                conn = (URL(request.url).openConnection() as HttpsURLConnection).apply {
                    instanceFollowRedirects = false
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 10000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    request.headers.forEach { (k, v) -> setRequestProperty(k, v) }
                    setFixedLengthStreamingMode(request.body.size)
                }
                activeTestConnection = conn
                conn.outputStream.use { it.write(request.body) }
                val code = conn.responseCode
                val responseText = if (code in 200..299) {
                    conn.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val size = input.read(buffer)
                            if (size < 0) break
                            check(output.size() + size <= 32768) { "响应过大" }
                            output.write(buffer, 0, size)
                        }
                        output.toString("UTF-8")
                    }
                } else ""
                val checkResult = DestinationProtocol.checkResponse(target.type, code, responseText)
                if (checkResult.ok) {
                    true to "测试消息发送成功，请在接收群内确认是否收到。"
                } else {
                    false to "发送失败：${checkResult.detail}（请检查地址、关键词或签名密钥）"
                }
            } catch (e: IllegalStateException) {
                false to "发送失败：${e.message.orEmpty()}"
            } catch (_: Exception) {
                false to "发送失败：网络连接异常，请检查网络与机器人配置"
            } finally {
                conn?.disconnect()
                if (activeTestConnection === conn) activeTestConnection = null
            }

            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                val (ok, msg) = outcome
                if (!channelTest.complete(seq, ok, msg)) return@post
                sendTestChannelButton.isEnabled = true
                sendTestChannelButton.text = "发送测试消息"
                showChannelTestFeedback(if (ok) Ui.Tone.SUCCESS else Ui.Tone.DANGER, msg)
                Toast.makeText(this, if (ok) "测试消息发送成功" else msg, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun showChannelTestFeedback(tone: Ui.Tone, message: String) {
        channelTestStatus.text = message
        channelTestStatus.setTextColor(ui.color(tone.ink))
        channelTestStatus.background = ui.round(tone.surface, 12)
        channelTestStatus.visibility = View.VISIBLE
    }

    private fun resetChannelTestFeedback() {
        if (channelTest.invalidate()) {
            activeTestConnection?.disconnect()
            activeTestConnection = null
            sendTestChannelButton.isEnabled = true
            sendTestChannelButton.text = "发送测试消息"
        }
        if (::channelTestStatus.isInitialized) {
            channelTestStatus.text = ""
            channelTestStatus.visibility = View.GONE
        }
    }

    private fun showGuideSheet(initialType: DestinationType = state.channelDraft.type) {
        if (guideDialog?.isShowing == true) return
        guideDialogType = initialType
        guideDialog = DestinationGuideSheet.show(this, initialType) { activeType ->
            guideDialogType = activeType
        }.apply {
            setOnDismissListener {
                guideDialog = null
                guideDialogType = null
            }
        }
    }

    private fun showTestSmsSheet() {
        if (testOpen) return
        val draftRules = conditionSection.currentRules()
        if (testRules != draftRules) testResult = ""
        testOpen = true
        val sheet = ui.bottomSheet("试一条短信", "按当前草稿在本机试算，不保存、不联网发送。") { bodyLayout, _, sheetScroll ->
            val (senderLayout, sender) = ui.textField(
                label = "发件号码 · 选填",
                placeholder = "例如：106123456",
                value = testSender,
                singleLine = true,
            )
            bodyLayout.addView(senderLayout)
            ui.gap(bodyLayout, 6)

            val (smsLayout, body) = ui.textField(
                label = "短信正文 *",
                placeholder = "在这里输入一条测试短信",
                value = testBody,
                singleLine = false,
            )

            bodyLayout.addView(ui.row().apply {
                addView(View(this@RuleWizardActivity), LinearLayout.LayoutParams(0, 1, 1f))
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
                        body.setText(samples[which])
                    }.setNegativeButton("取消", null).show()
                }.apply { minHeight = ui.dp(44) })
            })
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
                sheetScroll.post { sheetScroll.fullScroll(View.FOCUS_DOWN) }
            })
            ui.gap(bodyLayout, 12)
            bodyLayout.addView(result)
            sender.watch { testSender = it; testResult = ""; styleResult("内容已变化，请重新试算") }
            body.watch { testBody = it; smsLayout.error = null; testResult = ""; styleResult("内容已变化，请重新试算") }
            if (testResult.isNotEmpty()) sheetScroll.post { sheetScroll.fullScroll(View.FOCUS_DOWN) }
        }
        sheet.setOnDismissListener {
            testOpen = false
            testSheet = null
        }
        testSheet = sheet
        sheet.show()
    }

    private fun showChannelError(msg: String?) {
        channelErrorBox.text = msg.orEmpty()
        channelErrorBox.visibility = if (msg.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun showConditionError(msg: String?) {
        conditionErrorBox.text = msg.orEmpty()
        conditionErrorBox.visibility = if (msg.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun tileColorFor(type: DestinationType): Int = when (type) {
        DestinationType.FEISHU -> R.color.tile_blue
        DestinationType.WECOM -> R.color.tile_green
        DestinationType.DINGTALK -> R.color.tile_indigo
        DestinationType.CUSTOM -> R.color.tile_gray
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_STEP, state.step.name)
        outState.putString(KEY_CHANNEL_MODE, state.channelMode.name)
        outState.putString(KEY_SELECTED_DEST_ID, state.selectedDestinationId)
        outState.putString(KEY_DRAFT_ID, state.channelDraft.id)
        outState.putString(KEY_DRAFT_TYPE, state.channelDraft.type.id)
        outState.putString(KEY_DRAFT_NAME, state.channelDraft.name)
        outState.putString(KEY_DRAFT_WEBHOOK, state.channelDraft.webhook)
        outState.putString(KEY_DRAFT_SECRET, state.channelDraft.secret)
        outState.putString(KEY_RULE_NAME, state.ruleName)
        outState.putBoolean(KEY_RULE_NAME_MANUAL, state.ruleNameManuallyEdited)
        outState.putBoolean(KEY_RULE_ENABLED, state.ruleEnabled)
        outState.putBundle(KEY_CONDITION_STATE, conditionSection.saveInstanceState())

        if (guideDialog?.isShowing == true) {
            outState.putString(KEY_GUIDE_TYPE, (guideDialogType ?: state.channelDraft.type).id)
        }
        outState.putBoolean(KEY_TEST_OPEN, testOpen)
        outState.putString(KEY_TEST_SENDER, testSender)
        outState.putString(KEY_TEST_BODY, testBody)
        outState.putString(KEY_TEST_RESULT, testResult)
        testRules?.let { outState.putBundle(KEY_TEST_RULES, FilterConditionSection.writeRules(it)) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        channelTest.destroy()
        activeTestConnection?.disconnect()
        activeTestConnection = null
        mainHandler.removeCallbacksAndMessages(null)
        guideDialog?.setOnDismissListener(null)
        guideDialog?.dismiss()
        guideDialog = null
        testSheet?.setOnDismissListener(null)
        testSheet?.dismiss()
        testSheet = null
        super.onDestroy()
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
        private const val KEY_STEP = "wizard_step"
        private const val KEY_CHANNEL_MODE = "wizard_channel_mode"
        private const val KEY_SELECTED_DEST_ID = "wizard_selected_dest_id"
        private const val KEY_DRAFT_ID = "wizard_draft_id"
        private const val KEY_DRAFT_TYPE = "wizard_draft_type"
        private const val KEY_DRAFT_NAME = "wizard_draft_name"
        private const val KEY_DRAFT_WEBHOOK = "wizard_draft_webhook"
        private const val KEY_DRAFT_SECRET = "wizard_draft_secret"
        private const val KEY_RULE_NAME = "wizard_rule_name"
        private const val KEY_RULE_NAME_MANUAL = "wizard_rule_name_manual"
        private const val KEY_RULE_ENABLED = "wizard_rule_enabled"
        private const val KEY_CONDITION_STATE = "wizard_condition_state"
        private const val KEY_GUIDE_TYPE = "wizard_guide_type"
        private const val KEY_TEST_OPEN = "wizard_test_open"
        private const val KEY_TEST_SENDER = "wizard_test_sender"
        private const val KEY_TEST_BODY = "wizard_test_body"
        private const val KEY_TEST_RESULT = "wizard_test_result"
        private const val KEY_TEST_RULES = "wizard_test_rules"
    }
}
