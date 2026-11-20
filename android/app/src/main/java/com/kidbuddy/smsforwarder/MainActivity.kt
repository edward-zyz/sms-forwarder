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
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var ui: Ui
    private lateinit var heroCard: MaterialCardView
    private lateinit var statusBadge: TextView
    private lateinit var toggle: MaterialSwitch
    private lateinit var stateTitle: TextView
    private lateinit var stateDetail: TextView
    private lateinit var setupChecklist: LinearLayout
    private lateinit var stepPermBadge: TextView
    private lateinit var stepDestBadge: TextView
    private lateinit var stepRuleBadge: TextView
    private lateinit var testRow: LinearLayout
    private lateinit var testButton: MaterialButton
    private lateinit var latest: TextView
    private lateinit var latestTime: TextView
    private lateinit var queueCard: MaterialCardView
    private lateinit var queueBadge: TextView
    private lateinit var queueHint: TextView
    private lateinit var retryButton: MaterialButton
    private lateinit var clearButton: MaterialButton
    private lateinit var destSummary: TextView
    private lateinit var destBadge: TextView
    private lateinit var ruleSummary: TextView
    private lateinit var ruleBadge: TextView
    private lateinit var permissionSummary: TextView
    private lateinit var permissionBadge: TextView
    private var updatingToggle = false
    private var heroStateCache = ""
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { renderStatus(); handler.postDelayed(this, 1500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppAppearance.apply(this)
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val content = ui.column().apply { setPadding(ui.dp(18), ui.dp(20), ui.dp(18), ui.dp(28)) }
        ui.enableMotion(content)
        val scroll = ScrollView(this).apply {
            id = R.id.main_scroll
            isFillViewport = true
            clipToPadding = false
            setBackgroundColor(ui.color(R.color.page))
            addView(content)
        }
        setContentView(scroll)

        // 顶部页头：品牌标识 + 带矢量图标的外观切换胶囊
        val modes = arrayOf("system", "light", "dark")
        val modeLabels = arrayOf("跟随系统", "浅色模式", "深色模式")
        val modeShort = arrayOf("自动", "浅色", "深色")
        val appearance = getSharedPreferences("appearance", MODE_PRIVATE)
        val selectedMode = modes.indexOf(appearance.getString("mode", "system")).coerceAtLeast(0)

        content.addView(ui.row().apply {
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_forward)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ui.dp(42), ui.dp(42)).apply { marginEnd = ui.dp(12) })
            addView(ui.column().apply {
                addView(ui.text("短信转发", 22f, bold = true).apply { isAccessibilityHeading = true })
                addView(ui.text("新短信自动送达飞书、企微、钉钉等", 13f, R.color.muted))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.row().apply {
                minimumHeight = ui.dp(44)
                contentDescription = "外观：${modeLabels[selectedMode]}"
                setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
                background = ui.ripple(R.color.surface, 99, R.color.card_stroke)
                addView(ui.icon(R.drawable.ic_theme, 16, R.color.muted).apply {
                    layoutParams = LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)).apply { marginEnd = ui.dp(6) }
                })
                addView(ui.text(modeShort[selectedMode], 13f, R.color.muted, bold = true))
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
        ui.gap(content, 18)

        // 第一层：核心状态主卡（Material 3 卡片 + 药丸开关 + 准备清单 / 测试入口）
        heroCard = ui.card(R.color.surface, border = R.color.card_stroke, padH = 18, padV = 18) {
            ui.enableMotion(this)
            addView(ui.row().apply {
                statusBadge = ui.badge()
                addView(statusBadge)
                addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
                toggle = ui.switch(State.enabled(this@MainActivity), "开启或暂停自动转发短信")
                addView(toggle)
            })
            ui.gap(this, 8)
            stateTitle = ui.text("", 22f, bold = true)
            addView(stateTitle)
            ui.gap(this, 4)
            stateDetail = ui.text("", 14f, R.color.muted)
            addView(stateDetail)

            setupChecklist = ui.column().apply {
                ui.divider(this, top = 14, bottom = 12)
                addView(ui.text("开启前请完成以下准备", 13f, R.color.muted, bold = true))
                ui.gap(this, 8)
                stepPermBadge = ui.badge()
                addView(stepRow("1. 授予接收短信权限", stepPermBadge) { openPermissions() })
                ui.gap(this, 8)
                stepDestBadge = ui.badge()
                addView(stepRow("2. 添加转发目的地", stepDestBadge) {
                    val hasDest = runCatching { ConfigStore.load(this@MainActivity).destinations.isNotEmpty() }.getOrDefault(false)
                    startActivity(Intent(this@MainActivity, if (hasDest) DestinationsActivity::class.java else DestinationEditActivity::class.java))
                })
                ui.gap(this, 8)
                stepRuleBadge = ui.badge()
                addView(stepRow("3. 设置并启用转发规则", stepRuleBadge) {
                    startActivity(Intent(this@MainActivity, RulesActivity::class.java))
                })
            }
            addView(setupChecklist)

            testRow = ui.column().apply {
                ui.gap(this, 14)
                testButton = ui.button("发送测试消息", primary = true) { sendTest() }
                addView(testButton)
            }
            addView(testRow)
        }
        content.addView(heroCard)

        // 第二层：最近动态
        content.addView(ui.card(padH = 18, padV = 15) {
            addView(ui.row().apply {
                addView(ui.text("最近动态", 14f, R.color.muted, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                latestTime = ui.text("", 12f, R.color.subtle)
                addView(latestTime)
            })
            ui.gap(this, 8)
            latest = ui.text("", 15f)
            latest.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            addView(latest)
        })

        // 第三层：待发队列（仅在有积压时浮现）
        queueCard = ui.card(R.color.surface, border = R.color.card_stroke, padH = 18, padV = 16) {
            addView(ui.row().apply {
                addView(ui.text("待发队列", 16f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                queueBadge = ui.badge("", Ui.Tone.WARNING)
                addView(queueBadge)
            })
            ui.gap(this, 8)
            queueHint = ui.text("", 14f, R.color.muted)
            addView(queueHint)
            ui.gap(this, 14)
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

        // 第四层：转发设置分组卡
        ui.sectionTitle(content, "转发设置")
        content.addView(ui.card(padH = 18, padV = 6) {
            destSummary = ui.text("", 13f, R.color.muted)
            destBadge = ui.badge()
            addView(settingEntry("转发目的地", destSummary, trailingBadge(destBadge)) {
                startActivity(Intent(this@MainActivity, DestinationsActivity::class.java))
            })

            ui.divider(this, top = 2, bottom = 2)

            ruleSummary = ui.text("", 13f, R.color.muted)
            ruleBadge = ui.badge()
            addView(settingEntry("转发规则", ruleSummary, trailingBadge(ruleBadge)) {
                startActivity(Intent(this@MainActivity, RulesActivity::class.java))
            })

            ui.divider(this, top = 2, bottom = 2)

            permissionSummary = ui.text("", 13f, R.color.muted)
            permissionBadge = ui.badge()
            addView(settingEntry("权限与后台保活", permissionSummary, trailingBadge(permissionBadge)) {
                openPermissions()
            })
        })

        // 页脚隐私与行为说明
        ui.gap(content, 4)
        content.addView(ui.text(
            "本机暂存 · 发送成功后立即删除 · 不读取历史短信\n暂停期间的新短信不会补发 · 请仅转发至可信私人群",
            12f, R.color.subtle
        ).apply {
            setPadding(ui.dp(4), 0, ui.dp(4), 0)
        })

        toggle.setOnCheckedChangeListener { _, checked ->
            if (!updatingToggle) {
                ui.haptic(toggle)
                changeEnabled(checked)
            }
        }
        renderStatus()
    }

    private fun trailingBadge(badge: TextView) = ui.row().apply {
        addView(badge)
        addView(ui.chevronRight())
    }

    private fun stepRow(label: String, statusView: TextView, action: () -> Unit) = ui.row().apply {
        minimumHeight = ui.dp(48)
        setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
        background = ui.ripple(R.color.surface, 12, R.color.card_stroke)
        addView(ui.text(label, 14f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        addView(statusView, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(8) })
        setOnClickListener { ui.haptic(this); action() }
    }

    private fun settingEntry(title: String, subtitleView: TextView, trailing: View, action: () -> Unit) = ui.row().apply {
        minimumHeight = ui.dp(64)
        setPadding(0, ui.dp(12), 0, ui.dp(12))
        background = ui.ripple(R.color.surface, 10)
        addView(ui.column().apply {
            addView(ui.text(title, 16f, bold = true))
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
        val nextHeroState = when {
            isRunning -> "running"
            !ready -> "pending"
            else -> "paused"
        }
        if (heroStateCache != nextHeroState) {
            heroStateCache = nextHeroState
            when (nextHeroState) {
                "running" -> {
                    ui.styleCard(heroCard, R.color.success_surface, R.color.line)
                    ui.styleBadge(statusBadge, "● 运行中", Ui.Tone.SUCCESS)
                }
                "pending" -> {
                    ui.styleCard(heroCard, R.color.warning_surface, R.color.line)
                    ui.styleBadge(statusBadge, "● 待准备", Ui.Tone.WARNING)
                }
                else -> {
                    ui.styleCard(heroCard, R.color.surface, R.color.card_stroke)
                    ui.styleBadge(statusBadge, "● 已暂停", Ui.Tone.NEUTRAL)
                }
            }
        }

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
            enabled -> "正按 ${config!!.enabledRules().size} 条已启用规则，自动转发符合条件的新短信。"
            else -> "打开右上角开关即可开始转发；暂停期间的新短信不补发。"
        })

        setupChecklist.visibility = if (ready || configResult.isFailure) View.GONE else View.VISIBLE
        ui.styleBadge(stepPermBadge, if (permission) "✓ 已授权" else "去授权", if (permission) Ui.Tone.SUCCESS else Ui.Tone.DANGER)
        ui.styleBadge(stepDestBadge, if (hasDest) "✓ 已添加 ${config!!.destinations.size} 个" else "去添加", if (hasDest) Ui.Tone.SUCCESS else Ui.Tone.ACCENT)
        ui.styleBadge(stepRuleBadge, if (hasActiveRoute) "✓ 已启用" else "去设置", if (hasActiveRoute) Ui.Tone.SUCCESS else Ui.Tone.ACCENT)

        testRow.visibility = if (enabled && hasDest) View.VISIBLE else View.GONE
        ui.enabled(testButton, enabled && hasDest)

        val message = prefs.getString("status", "").orEmpty()
        latest.showText(message.ifBlank { "还没有转发记录，收到新短信后会在这里显示结果。" })
        latest.setTextColor(ui.color(if (listOf("失败", "错误", "无效", "未能", "超过", "已满").any { it in message }) R.color.danger else R.color.ink))
        val time = prefs.getLong("statusTime", 0)
        latestTime.showText(if (time == 0L) "不展示短信正文" else SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(time)))

        queueCard.visibility = if (count > 0) View.VISIBLE else View.GONE
        if (count > 0) {
            ui.styleBadge(queueBadge, "$count 条待发", Ui.Tone.WARNING)
            queueHint.showText(if (enabled) {
                "网络恢复后会自动重试；若连续失败 8 次，可在此手动重试。"
            } else {
                "转发已暂停。开启转发后将继续发送，也可以直接清空。"
            })
            ui.enabled(retryButton, enabled)
            ui.enabled(clearButton, !enabled)
        }

        if (config == null) {
            destSummary.showText("配置异常，点击查看")
            ui.styleBadge(destBadge, "异常", Ui.Tone.DANGER)
        } else if (config.destinations.isEmpty()) {
            destSummary.showText("支持飞书、企业微信、钉钉、自定义 Webhook")
            ui.styleBadge(destBadge, "未添加", Ui.Tone.WARNING)
        } else {
            val platforms = config.destinations.map { it.type.displayName }.distinct().joinToString("、")
            destSummary.showText("已配置 $platforms")
            ui.styleBadge(destBadge, "${config.destinations.size} 个", Ui.Tone.ACCENT)
        }

        if (config == null) {
            ruleSummary.showText("配置异常，点击查看")
            ui.styleBadge(ruleBadge, "异常", Ui.Tone.DANGER)
        } else if (config.rules.isEmpty()) {
            ruleSummary.showText("为验证码、告警等设置不同条件和目的地")
            ui.styleBadge(ruleBadge, "未设置", Ui.Tone.WARNING)
        } else {
            val activeCount = config.enabledRules().size
            ruleSummary.showText("共 ${config.rules.size} 条规则，其中 $activeCount 条已启用")
            ui.styleBadge(
                ruleBadge,
                if (activeCount > 0) "$activeCount 条生效" else "均已停用",
                if (activeCount > 0) Ui.Tone.SUCCESS else Ui.Tone.NEUTRAL
            )
        }

        val bgRestricted = getSystemService(ActivityManager::class.java).isBackgroundRestricted
        val batteryExempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        when {
            !permission -> {
                permissionSummary.showText("缺少接收短信权限，无法自动转发")
                ui.styleBadge(permissionBadge, "需授权", Ui.Tone.DANGER)
            }
            bgRestricted -> {
                permissionSummary.showText("短信权限已授权 · 系统限制了后台活动")
                ui.styleBadge(permissionBadge, "建议调整", Ui.Tone.WARNING)
            }
            !batteryExempt -> {
                permissionSummary.showText("短信权限已授权 · 电池优化可能延迟送达")
                ui.styleBadge(permissionBadge, "可优化", Ui.Tone.WARNING)
            }
            else -> {
                permissionSummary.showText("短信权限与后台运行状态正常")
                ui.styleBadge(permissionBadge, "正常", Ui.Tone.SUCCESS)
            }
        }
    }

    override fun onResume() { super.onResume(); handler.removeCallbacks(refresh); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
}
