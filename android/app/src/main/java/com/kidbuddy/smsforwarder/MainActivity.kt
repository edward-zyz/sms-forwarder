package com.kidbuddy.smsforwarder

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var ui: Ui
    private lateinit var powerRing: View
    private lateinit var powerButton: FrameLayout
    private lateinit var powerIcon: ImageView
    private lateinit var stateTitle: TextView
    private lateinit var stateDetail: TextView
    private lateinit var testChip: LinearLayout
    private lateinit var setupSection: LinearLayout
    private lateinit var stepPermValue: TextView
    private lateinit var stepDestValue: TextView
    private lateinit var stepRuleValue: TextView
    private lateinit var latest: TextView
    private lateinit var latestTime: TextView
    private lateinit var queueCard: MaterialCardView
    private lateinit var queueCountText: TextView
    private lateinit var queueHint: TextView
    private lateinit var retryButton: MaterialButton
    private lateinit var clearButton: MaterialButton
    private lateinit var destSummary: TextView
    private lateinit var destValue: TextView
    private lateinit var ruleSummary: TextView
    private lateinit var ruleValue: TextView
    private lateinit var permissionSummary: TextView
    private lateinit var permissionValue: TextView
    private lateinit var deviceSummary: TextView
    private lateinit var deviceValue: TextView
    private var heroStateCache = ""
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { renderStatus(); handler.postDelayed(this, 1500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppAppearance.apply(this)
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val content = ui.column().apply { setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(28)) }
        ui.enableMotion(content)
        val scroll = ScrollView(this).apply {
            id = R.id.main_scroll
            isFillViewport = true
            clipToPadding = false
            setBackgroundColor(ui.color(R.color.page))
            addView(content)
        }
        setContentView(scroll)

        // 顶部精简栏：左侧轻量应用名，右侧无框外观切换
        val modes = arrayOf("system", "light", "dark")
        val modeLabels = arrayOf("跟随系统", "浅色模式", "深色模式")
        val modeShort = arrayOf("自动", "浅色", "深色")
        val appearance = getSharedPreferences("appearance", MODE_PRIVATE)
        val selectedMode = modes.indexOf(appearance.getString("mode", "system")).coerceAtLeast(0)

        content.addView(ui.row().apply {
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_forward)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)).apply { marginEnd = ui.dp(8) })
            addView(ui.text("短信转发", 18f, bold = true).apply { isAccessibilityHeading = true },
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.row().apply {
                minimumHeight = ui.dp(40)
                contentDescription = "外观：${modeLabels[selectedMode]}"
                setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6))
                background = ui.transparentRipple(99)
                addView(ui.icon(R.drawable.ic_theme, 16, R.color.muted).apply {
                    layoutParams = LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)).apply { marginEnd = ui.dp(5) }
                })
                addView(ui.text(modeShort[selectedMode], 13f, R.color.muted))
                setOnClickListener {
                    ui.haptic(this)
                    ui.dialog().setTitle("选择外观")
                        .setSingleChoiceItems(modeLabels, selectedMode) { dialog, which ->
                            appearance.edit().putString("mode", modes[which]).apply()
                            dialog.dismiss()
                            AppAppearance.apply(this@MainActivity)
                            recreate()
                        }.setNegativeButton("取消", null).show()
                }
            })
        })

        // 头部约 1/3 屏 Hero 主控开关区：居中超大圆形主控按钮 + 状态标题与副文
        content.addView(ui.column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, ui.dp(18), 0, ui.dp(22))
            ui.enableMotion(this)

            val dialContainer = FrameLayout(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(ui.dp(156), ui.dp(156))
                clipChildren = false
                clipToPadding = false
            }

            powerRing = View(this@MainActivity)
            dialContainer.addView(powerRing, FrameLayout.LayoutParams(ui.dp(156), ui.dp(156), Gravity.CENTER))

            powerIcon = ui.icon(R.drawable.ic_power, 42, R.color.muted)
            powerButton = FrameLayout(this@MainActivity).apply {
                elevation = ui.dp(6).toFloat()
                isClickable = true
                isFocusable = true
                addView(powerIcon, FrameLayout.LayoutParams(ui.dp(42), ui.dp(42), Gravity.CENTER))
                setOnClickListener {
                    ui.haptic(this)
                    if (ui.motionEnabled()) {
                        animate().scaleX(0.93f).scaleY(0.93f).setDuration(90L).withEndAction {
                            animate().scaleX(1f).scaleY(1f).setDuration(140L).start()
                        }.start()
                    }
                    changeEnabled(!State.enabled(this@MainActivity))
                }
            }
            dialContainer.addView(powerButton, FrameLayout.LayoutParams(ui.dp(116), ui.dp(116), Gravity.CENTER))
            addView(dialContainer)

            ui.gap(this, 16)
            stateTitle = ui.text("", 20f, bold = true).apply { gravity = Gravity.CENTER }
            addView(stateTitle)
            ui.gap(this, 4)
            stateDetail = ui.text("", 13.5f, R.color.muted).apply { gravity = Gravity.CENTER }
            addView(stateDetail)

            // 运行中居中轻胶囊：发送测试消息
            testChip = ui.row().apply {
                minimumHeight = ui.dp(40)
                setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8))
                background = ui.ripple(R.color.surface, 99)
                elevation = ui.dp(1).toFloat()
                addView(ui.icon(R.drawable.ic_send, 15, R.color.accent).apply {
                    layoutParams = LinearLayout.LayoutParams(ui.dp(15), ui.dp(15)).apply { marginEnd = ui.dp(6) }
                })
                addView(ui.text("发送测试消息", 13.5f, R.color.ink, bold = true))
                setOnClickListener {
                    ui.haptic(this)
                    sendTest()
                }
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(14) }
            }
            addView(testChip)
        })

        // 未就绪时的 3 步准备清单（仅未就绪时显示，0 内边距卡片 + cell 满宽按压态）
        setupSection = ui.column().apply {
            ui.sectionTitle(this, "开启前准备")
            addView(ui.listCard {
                stepPermValue = ui.valueText()
                addView(stepRow("1. 授予接收短信权限", stepPermValue) { openPermissions() })
                ui.insetDivider(this, startDp = 18, endDp = 18)
                stepDestValue = ui.valueText()
                addView(stepRow("2. 添加转发目的地", stepDestValue) {
                    val hasDest = runCatching { ConfigStore.load(this@MainActivity).destinations.isNotEmpty() }.getOrDefault(false)
                    startActivity(Intent(this@MainActivity, if (hasDest) DestinationsActivity::class.java else DestinationEditActivity::class.java))
                })
                ui.insetDivider(this, startDp = 18, endDp = 18)
                stepRuleValue = ui.valueText()
                addView(stepRow("3. 设置并启用转发规则", stepRuleValue) {
                    startActivity(Intent(this@MainActivity, RulesActivity::class.java))
                })
            })
        }
        content.addView(setupSection)

        // 最近动态
        content.addView(ui.card(padH = 18, padV = 15) {
            addView(ui.row().apply {
                addView(ui.text("最近动态", 13f, R.color.subtle), LinearLayout.LayoutParams(0, -2, 1f))
                latestTime = ui.text("", 12f, R.color.subtle)
                addView(latestTime)
            })
            ui.gap(this, 6)
            latest = ui.text("", 14.5f)
            latest.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            addView(latest)
        })

        // 待发队列（仅在有积压时浮现）
        queueCard = ui.card(padH = 18, padV = 15) {
            addView(ui.row().apply {
                addView(ui.text("待发队列", 15.5f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                queueCountText = ui.valueText("", R.color.warning)
                addView(queueCountText)
            })
            ui.gap(this, 6)
            queueHint = ui.text("", 13f, R.color.muted)
            addView(queueHint)
            ui.gap(this, 12)
            addView(ui.row().apply {
                retryButton = ui.button("重试发送") { retryQueue() }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) }
                }
                clearButton = ui.button("清空队列", danger = true) { clearQueue() }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                }
                addView(retryButton)
                addView(clearButton)
            })
        }
        content.addView(queueCard)

        // 转发设置分组卡（HyperOS / Apple 设置风：32dp 彩色圆角图标块 + 满宽按压态 + 发丝内缩分割线）
        ui.sectionTitle(content, "转发设置")
        content.addView(ui.listCard {
            destSummary = ui.text("", 12.5f, R.color.subtle)
            destValue = ui.valueText()
            addView(settingEntry(R.drawable.ic_send, R.color.tile_blue, "转发目的地", destSummary, trailingValue(destValue)) {
                startActivity(Intent(this@MainActivity, DestinationsActivity::class.java))
            })

            ui.insetDivider(this, startDp = 62, endDp = 18)

            ruleSummary = ui.text("", 12.5f, R.color.subtle)
            ruleValue = ui.valueText()
            addView(settingEntry(R.drawable.ic_filter, R.color.tile_indigo, "转发规则", ruleSummary, trailingValue(ruleValue)) {
                startActivity(Intent(this@MainActivity, RulesActivity::class.java))
            })

            ui.insetDivider(this, startDp = 62, endDp = 18)

            permissionSummary = ui.text("", 12.5f, R.color.subtle)
            permissionValue = ui.valueText()
            addView(settingEntry(R.drawable.ic_shield, R.color.tile_green, "权限与后台保活", permissionSummary, trailingValue(permissionValue)) {
                openPermissions()
            })

            ui.insetDivider(this, startDp = 62, endDp = 18)

            deviceSummary = ui.text("", 12.5f, R.color.subtle)
            deviceValue = ui.valueText()
            addView(settingEntry(R.drawable.ic_phone, R.color.tile_teal, "本机号码 / 备注", deviceSummary, trailingValue(deviceValue)) {
                editDeviceLabel()
            })
        })

        // 页脚安全与隐私说明（面向普通用户的大白话）
        content.addView(ui.text(
            "只发给你指定的目的地 · 不翻看旧短信 · 不留存内容",
            12f, R.color.subtle
        ).apply {
            gravity = Gravity.CENTER
            setPadding(ui.dp(6), ui.dp(4), ui.dp(6), 0)
        })

        renderStatus()
    }

    private fun circleDrawable(fillColorRes: Int, strokeColorRes: Int? = null, strokeDp: Int = 1) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(ui.color(fillColorRes))
        strokeColorRes?.let { setStroke(ui.dp(strokeDp), ui.color(it)) }
    }

    private fun circleRipple(fillColorRes: Int) = RippleDrawable(
        ui.colorList(R.color.ripple),
        circleDrawable(fillColorRes),
        null
    )

    private fun trailingValue(valueView: TextView) = ui.row().apply {
        addView(valueView)
        addView(ui.chevronRight())
    }

    private fun stepRow(label: String, valueView: TextView, action: () -> Unit) = ui.row().apply {
        minimumHeight = ui.dp(50)
        setPadding(ui.dp(18), ui.dp(11), ui.dp(18), ui.dp(11))
        background = ui.transparentRipple(0)
        addView(ui.text(label, 14.5f), LinearLayout.LayoutParams(0, -2, 1f))
        addView(valueView, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(8) })
        addView(ui.chevronRight())
        setOnClickListener { ui.haptic(this); action() }
    }

    private fun settingEntry(
        iconRes: Int,
        tileColorRes: Int,
        title: String,
        subtitleView: TextView,
        trailing: View,
        action: () -> Unit
    ) = ui.row().apply {
        minimumHeight = ui.dp(64)
        setPadding(ui.dp(18), ui.dp(13), ui.dp(18), ui.dp(13))
        background = ui.transparentRipple(0)
        addView(ui.iconTile(iconRes, tileColorRes, sizeDp = 32, iconSizeDp = 17, radiusDp = 9))
        addView(ui.column().apply {
            addView(ui.text(title, 15.5f, bold = true))
            ui.gap(this, 2)
            addView(subtitleView)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(10) })
        addView(trailing)
        setOnClickListener { ui.haptic(this); action() }
    }

    private fun changeEnabled(checked: Boolean) {
        synchronized(State.gate) {
            if (!checked) {
                if (State.prefs(this).edit().putBoolean("enabled", false).commit()) ForwardJob.cancel(this)
                else toast("暂停状态保存失败，请重试")
                renderStatus()
                return
            }
            if (!hasSmsPermission()) {
                toast("请先授予接收短信权限")
                openPermissions()
                return
            }
            val configResult = runCatching { ConfigStore.load(this) }
            if (configResult.isFailure) {
                toast("转发配置异常，请检查目的地与规则设置")
                return
            }
            val config = configResult.getOrThrow()
            if (config.destinations.isEmpty()) {
                toast("请先添加一个转发目的地")
                startActivity(Intent(this, DestinationEditActivity::class.java))
                return
            }
            if (!config.hasActiveRoute()) {
                toast("请先添加并启用至少一条转发规则")
                startActivity(Intent(this, RulesActivity::class.java))
                return
            }
            ui.dialog().setTitle("开启自动转发")
                .setMessage("符合已启用规则的新短信及已有待发消息，将发送到对应目的地，内容可能包含验证码。请确认接收群均为可信成员。")
                .setPositiveButton("开启") { _, _ ->
                    synchronized(State.gate) {
                        val latestConfig = runCatching { ConfigStore.load(this) }.getOrNull()
                        if (hasSmsPermission() && latestConfig != null && latestConfig.hasActiveRoute()) {
                            val prefs = State.prefs(this)
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
        val config = runCatching { ConfigStore.load(this) }.getOrNull()
        val dests = config?.destinations.orEmpty()
        if (dests.isEmpty()) {
            toast("请先添加转发目的地")
            return
        }
        if (dests.size == 1) {
            confirmSendTest(dests.single())
            return
        }
        val names = dests.map { "${it.name}（${it.type.displayName}）" }.toTypedArray()
        ui.dialog()
            .setTitle("选择测试目的地")
            .setItems(names) { _, which -> confirmSendTest(dests[which]) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmSendTest(dest: Destination) {
        ui.dialog()
            .setTitle("发送测试消息")
            .setMessage("将向${dest.type.displayName}「${dest.name}」发送一条测试消息，不包含真实短信。")
            .setPositiveButton("发送") { _, _ ->
                try {
                    synchronized(State.gate) {
                        if (!State.enabled(this)) { toast("转发已暂停"); return@setPositiveButton }
                        val deviceLabel = State.deviceLabel(this)
                        val deviceLine = if (deviceLabel.isNotEmpty()) "\n接收手机：$deviceLabel" else ""
                        val text = "短信转发$deviceLine\n测试消息：安卓手机已准备好，收到此消息表示${dest.type.displayName}「${dest.name}」连接成功。"
                        Store(this).use { it.add(text, null, dest.id, "测试消息") }
                        State.status(this, "测试消息已入队，等待「${dest.name}」确认")
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
        ui.dialog().setTitle("清空待发队列？")
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

    private fun editDeviceLabel() {
        val current = State.deviceLabel(this)
        val (fieldLayout, input) = ui.textField(
            label = "本机号码或门店备注",
            placeholder = "例如 138****8888 或 浦东店·美团号",
            value = current,
            helper = "选填（最多 ${MessageFormatter.MAX_DEVICE_LABEL_LENGTH} 字）。多台手机转同一个群时，消息中会附带「接收手机」，不申请额外电话权限。"
        )
        val container = ui.column().apply {
            setPadding(ui.dp(22), ui.dp(12), ui.dp(22), 0)
            addView(fieldLayout)
        }
        val builder = ui.dialog()
            .setTitle("本机号码 / 备注")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val raw = input.text?.toString().orEmpty()
                if (State.setDeviceLabel(this, raw)) {
                    val saved = State.deviceLabel(this)
                    toast(if (saved.isEmpty()) "已清空本机号码/备注" else "已保存：$saved")
                } else {
                    toast("保存失败，请重试")
                }
                renderStatus()
            }
            .setNegativeButton("取消", null)
        if (current.isNotEmpty()) {
            builder.setNeutralButton("清空") { _, _ ->
                if (State.setDeviceLabel(this, "")) toast("已清空本机号码/备注")
                else toast("清空失败，请重试")
                renderStatus()
            }
        }
        builder.show()
    }

    private fun openPermissions() { startActivity(Intent(this, PermissionActivity::class.java)) }

    private fun hasSmsPermission() = checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
    private fun toast(value: String) { Toast.makeText(this, value, Toast.LENGTH_LONG).show() }
    private fun TextView.showText(value: String) { if (text.toString() != value) text = value }

    private fun renderStatus() {
        if (!::latest.isInitialized) return
        val prefs = State.prefs(this)
        val enabled = State.enabled(this)
        val permission = hasSmsPermission()
        val configResult = runCatching { ConfigStore.load(this) }
        val config = configResult.getOrNull()
        val hasDest = config != null && config.destinations.isNotEmpty()
        val hasActiveRoute = config != null && config.hasActiveRoute()
        val ready = permission && hasDest && hasActiveRoute && configResult.isSuccess
        val count = Store(this).use { it.count() }

        val isRunning = enabled && ready
        val nextHeroState = when {
            isRunning -> "running"
            !ready -> "pending"
            else -> "paused"
        }
        if (heroStateCache != nextHeroState) {
            heroStateCache = nextHeroState
            when (nextHeroState) {
                "running" -> {
                    powerRing.background = circleDrawable(R.color.accent_surface, R.color.accent, strokeDp = 1)
                    powerButton.background = circleRipple(R.color.accent)
                    powerIcon.imageTintList = ui.colorList(R.color.on_accent)
                    powerButton.contentDescription = "自动转发运行中，点按暂停转发"
                }
                "pending" -> {
                    powerRing.background = circleDrawable(R.color.warning_surface)
                    powerButton.background = circleRipple(R.color.surface)
                    powerIcon.imageTintList = ui.colorList(R.color.warning)
                    powerButton.contentDescription = "尚未完成转发准备，点按查看所需配置"
                }
                else -> {
                    powerRing.background = circleDrawable(R.color.neutral_surface)
                    powerButton.background = circleRipple(R.color.surface)
                    powerIcon.imageTintList = ui.colorList(R.color.muted)
                    powerButton.contentDescription = "自动转发已暂停，点按开启转发"
                }
            }
        }

        stateTitle.showText(when {
            configResult.isFailure -> "转发配置异常"
            !ready -> "完成准备后开启"
            enabled -> "转发运行中"
            else -> "轻点开启转发"
        })
        stateDetail.showText(when {
            configResult.isFailure -> "请检查「转发目的地」和「转发规则」并重新保存"
            !permission -> "请先完成下方权限与规则准备"
            !hasDest -> "请先添加至少一个转发目的地"
            !hasActiveRoute -> "请启用至少一条转发规则"
            enabled -> "已启用 ${config!!.enabledRules().size} 条规则 · 收到新短信自动送达"
            else -> "已暂停 · 暂停期间的新短信不会补发"
        })

        testChip.visibility = if (enabled && hasDest) View.VISIBLE else View.GONE

        setupSection.visibility = if (ready || configResult.isFailure) View.GONE else View.VISIBLE
        ui.setValueText(stepPermValue, if (permission) "已授权" else "去授权", if (permission) R.color.subtle else R.color.danger)
        ui.setValueText(stepDestValue, if (hasDest) "已添加" else "去添加", if (hasDest) R.color.subtle else R.color.accent)
        ui.setValueText(stepRuleValue, if (hasActiveRoute) "已启用" else "去设置", if (hasActiveRoute) R.color.subtle else R.color.accent)

        val message = prefs.getString("status", "").orEmpty()
        latest.showText(message.ifBlank { "还没有转发记录，收到新短信后会在这里显示结果。" })
        latest.setTextColor(ui.color(if (listOf("失败", "错误", "无效", "未能", "超过", "已满").any { it in message }) R.color.danger else R.color.ink))
        val time = prefs.getLong("statusTime", 0)
        latestTime.showText(if (time == 0L) "不展示短信正文" else SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(time)))

        queueCard.visibility = if (count > 0) View.VISIBLE else View.GONE
        if (count > 0) {
            ui.setValueText(queueCountText, "$count 条待发", R.color.warning, bold = true)
            queueHint.showText(if (enabled) {
                "网络恢复后会自动重试；连续失败 8 次后可手动重试。"
            } else {
                "转发已暂停。开启转发将继续发送，也可以直接清空。"
            })
            ui.enabled(retryButton, enabled)
            ui.enabled(clearButton, !enabled)
        }

        if (config == null) {
            destSummary.showText("配置异常，点击检查")
            ui.setValueText(destValue, "异常", R.color.danger)
        } else if (config.destinations.isEmpty()) {
            destSummary.showText("支持飞书、企业微信、钉钉、Webhook")
            ui.setValueText(destValue, "未添加", R.color.warning)
        } else {
            val platforms = config.destinations.map { it.type.displayName }.distinct().joinToString("、")
            destSummary.showText(platforms)
            ui.setValueText(destValue, "${config.destinations.size} 个", R.color.subtle)
        }

        if (config == null) {
            ruleSummary.showText("配置异常，点击检查")
            ui.setValueText(ruleValue, "异常", R.color.danger)
        } else if (config.rules.isEmpty()) {
            ruleSummary.showText("按验证码、号码、关键词灵活路由")
            ui.setValueText(ruleValue, "未设置", R.color.warning)
        } else {
            val activeCount = config.enabledRules().size
            ruleSummary.showText("共 ${config.rules.size} 条规则")
            ui.setValueText(
                ruleValue,
                if (activeCount > 0) "$activeCount 条生效" else "均已停用",
                if (activeCount > 0) R.color.subtle else R.color.muted
            )
        }

        val bgRestricted = getSystemService(ActivityManager::class.java).isBackgroundRestricted
        val batteryExempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        when {
            !permission -> {
                permissionSummary.showText("缺少接收短信权限")
                ui.setValueText(permissionValue, "需授权", R.color.danger)
            }
            bgRestricted -> {
                permissionSummary.showText("系统限制了后台活动")
                ui.setValueText(permissionValue, "建议调整", R.color.warning)
            }
            !batteryExempt -> {
                permissionSummary.showText("电池优化可能延迟送达")
                ui.setValueText(permissionValue, "可优化", R.color.warning)
            }
            else -> {
                permissionSummary.showText("短信权限与后台运行正常")
                ui.setValueText(permissionValue, "正常", R.color.subtle)
            }
        }

        val deviceLabel = State.deviceLabel(this)
        if (deviceLabel.isEmpty()) {
            deviceSummary.showText("多台手机转同一个群时，用来区分来源")
            ui.setValueText(deviceValue, "未填写", R.color.muted)
        } else {
            deviceSummary.showText("转发时附带：$deviceLabel")
            ui.setValueText(deviceValue, "已填写", R.color.subtle)
        }
    }

    override fun onResume() { super.onResume(); handler.removeCallbacks(refresh); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
}
