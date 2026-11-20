package com.kidbuddy.smsforwarder

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var ui: Ui
    private lateinit var statusTile: LinearLayout
    private lateinit var toggle: SwitchCompat
    private lateinit var stateTitle: TextView
    private lateinit var stateDetail: TextView
    private lateinit var setupChecklist: LinearLayout
    private lateinit var stepPermValue: TextView
    private lateinit var stepDestValue: TextView
    private lateinit var stepRuleValue: TextView
    private lateinit var testRow: LinearLayout
    private lateinit var testButton: MaterialButton
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
    private var updatingToggle = false
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { renderStatus(); handler.postDelayed(this, 1500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppAppearance.apply(this)
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val content = ui.column().apply { setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(28)) }
        ui.enableMotion(content)
        val scroll = ScrollView(this).apply {
            id = R.id.main_scroll
            isFillViewport = true
            clipToPadding = false
            setBackgroundColor(ui.color(R.color.page))
            addView(content)
        }
        setContentView(scroll)

        // HyperOS 风格大标题页头：顶部轻量工具行 + 舒展大标题
        val modes = arrayOf("system", "light", "dark")
        val modeLabels = arrayOf("跟随系统", "浅色模式", "深色模式")
        val modeShort = arrayOf("自动外观", "浅色", "深色")
        val appearance = getSharedPreferences("appearance", MODE_PRIVATE)
        val selectedMode = modes.indexOf(appearance.getString("mode", "system")).coerceAtLeast(0)

        content.addView(ui.row().apply {
            setPadding(ui.dp(2), 0, 0, ui.dp(16))
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_forward)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)).apply { marginEnd = ui.dp(12) })
            addView(ui.column().apply {
                addView(ui.text("短信转发", 24f, bold = true).apply { isAccessibilityHeading = true })
                addView(ui.text("自动送达飞书、企微、钉钉等", 13f, R.color.subtle))
            }, LinearLayout.LayoutParams(0, -2, 1f))
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

        // 第一层：核心状态主卡（纯白大圆角 + 40dp 精致状态图标块 + MIUI 圆润开关）
        content.addView(ui.card(padH = 18, padV = 18) {
            ui.enableMotion(this)
            addView(ui.row().apply {
                statusTile = ui.iconTile(R.drawable.ic_bolt, R.color.tile_gray, sizeDp = 40, iconSizeDp = 20, radiusDp = 12)
                addView(statusTile)
                addView(ui.column().apply {
                    stateTitle = ui.text("", 17f, bold = true)
                    addView(stateTitle)
                    ui.gap(this, 2)
                    stateDetail = ui.text("", 13f, R.color.muted)
                    addView(stateDetail)
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(10) })
                toggle = ui.switch(State.enabled(this@MainActivity), "开启或暂停自动转发短信")
                addView(toggle)
            })

            // 未就绪时的 3 步准备清单（纯净列表，无花哨彩色胶囊）
            setupChecklist = ui.column().apply {
                ui.divider(this, top = 12, bottom = 4)
                stepPermValue = ui.valueText()
                addView(stepRow("1. 授予接收短信权限", stepPermValue) { openPermissions() })
                ui.divider(this, top = 0, bottom = 0)
                stepDestValue = ui.valueText()
                addView(stepRow("2. 添加转发目的地", stepDestValue) {
                    val hasDest = runCatching { ConfigStore.load(this@MainActivity).destinations.isNotEmpty() }.getOrDefault(false)
                    startActivity(Intent(this@MainActivity, if (hasDest) DestinationsActivity::class.java else DestinationEditActivity::class.java))
                })
                ui.divider(this, top = 0, bottom = 0)
                stepRuleValue = ui.valueText()
                addView(stepRow("3. 设置并启用转发规则", stepRuleValue) {
                    startActivity(Intent(this@MainActivity, RulesActivity::class.java))
                })
            }
            addView(setupChecklist)

            // 运行中快捷测试入口（中性灰底黑字，干净克制）
            testRow = ui.column().apply {
                ui.gap(this, 12)
                testButton = ui.button("发送测试消息") { sendTest() }
                addView(testButton)
            }
            addView(testRow)
        })

        // 第二层：最近动态（紧致纯白卡）
        content.addView(ui.card(padH = 16, padV = 14) {
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

        // 第三层：待发队列（仅在有积压时浮现）
        queueCard = ui.card(padH = 16, padV = 14) {
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

        // 第四层：转发设置（HyperOS / Apple 设置风：左侧 32dp 柔和圆角图标底块 + 发丝内缩分割线）
        ui.sectionTitle(content, "转发设置")
        content.addView(ui.card(padH = 18, padV = 4) {
            destSummary = ui.text("", 12.5f, R.color.subtle)
            destValue = ui.valueText()
            addView(settingEntry(R.drawable.ic_send, R.color.tile_blue, "转发目的地", destSummary, trailingValue(destValue)) {
                startActivity(Intent(this@MainActivity, DestinationsActivity::class.java))
            })

            ui.insetDivider(this, startDp = 44)

            ruleSummary = ui.text("", 12.5f, R.color.subtle)
            ruleValue = ui.valueText()
            addView(settingEntry(R.drawable.ic_filter, R.color.tile_indigo, "转发规则", ruleSummary, trailingValue(ruleValue)) {
                startActivity(Intent(this@MainActivity, RulesActivity::class.java))
            })

            ui.insetDivider(this, startDp = 44)

            permissionSummary = ui.text("", 12.5f, R.color.subtle)
            permissionValue = ui.valueText()
            addView(settingEntry(R.drawable.ic_shield, R.color.tile_green, "权限与后台保活", permissionSummary, trailingValue(permissionValue)) {
                openPermissions()
            })
        })

        // 页脚隐私说明
        content.addView(ui.text(
            "本机暂存 · 发送后即删 · 不读历史短信 · 暂停期间不补发",
            12f, R.color.subtle
        ).apply {
            setPadding(ui.dp(6), ui.dp(4), ui.dp(6), 0)
        })

        toggle.setOnCheckedChangeListener { _, checked ->
            if (!updatingToggle) {
                ui.haptic(toggle)
                changeEnabled(checked)
            }
        }
        renderStatus()
    }

    private fun trailingValue(valueView: TextView) = ui.row().apply {
        addView(valueView)
        addView(ui.chevronRight())
    }

    private fun stepRow(label: String, valueView: TextView, action: () -> Unit) = ui.row().apply {
        minimumHeight = ui.dp(46)
        setPadding(0, ui.dp(8), 0, ui.dp(8))
        background = ui.transparentRipple(8)
        addView(ui.text(label, 14f), LinearLayout.LayoutParams(0, -2, 1f))
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
        minimumHeight = ui.dp(62)
        setPadding(0, ui.dp(12), 0, ui.dp(12))
        background = ui.transparentRipple(10)
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
            syncToggle()
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
                        val text = "短信转发\n测试消息：安卓手机已准备好，收到此消息表示${dest.type.displayName}「${dest.name}」连接成功。"
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

    private fun openPermissions() { startActivity(Intent(this, PermissionActivity::class.java)) }

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
        val configResult = runCatching { ConfigStore.load(this) }
        val config = configResult.getOrNull()
        val hasDest = config != null && config.destinations.isNotEmpty()
        val hasActiveRoute = config != null && config.hasActiveRoute()
        val ready = permission && hasDest && hasActiveRoute && configResult.isSuccess
        val count = Store(this).use { it.count() }
        syncToggle()

        val isRunning = enabled && ready
        ui.styleIconTile(
            statusTile,
            when {
                isRunning -> R.color.tile_green
                !ready -> R.color.tile_amber
                else -> R.color.tile_gray
            },
            radiusDp = 12
        )

        stateTitle.showText(when {
            configResult.isFailure -> "转发配置异常"
            !ready -> "完成准备后即可开启"
            enabled -> "自动转发运行中"
            else -> "自动转发已暂停"
        })
        stateDetail.showText(when {
            configResult.isFailure -> "请检查「转发目的地」和「转发规则」并重新保存。"
            !permission -> "授权接收短信并配置规则后，即可自动转发新短信。"
            !hasDest -> "请先添加至少一个转发目的地（飞书、企微、钉钉或 Webhook）。"
            !hasActiveRoute -> "请添加并启用至少一条转发规则，指定哪些短信发往哪里。"
            enabled -> "正按 ${config!!.enabledRules().size} 条已启用规则自动转发新短信。"
            else -> "打开右侧开关即可开始转发；暂停期间的新短信不补发。"
        })

        setupChecklist.visibility = if (ready || configResult.isFailure) View.GONE else View.VISIBLE
        ui.setValueText(stepPermValue, if (permission) "已授权" else "去授权", if (permission) R.color.subtle else R.color.danger)
        ui.setValueText(stepDestValue, if (hasDest) "已添加" else "去添加", if (hasDest) R.color.subtle else R.color.accent)
        ui.setValueText(stepRuleValue, if (hasActiveRoute) "已启用" else "去设置", if (hasActiveRoute) R.color.subtle else R.color.accent)

        testRow.visibility = if (enabled && hasDest) View.VISIBLE else View.GONE
        ui.enabled(testButton, enabled && hasDest)

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
    }

    override fun onResume() { super.onResume(); handler.removeCallbacks(refresh); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
}
