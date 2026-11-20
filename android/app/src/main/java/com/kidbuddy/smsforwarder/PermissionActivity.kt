package com.kidbuddy.smsforwarder

import android.Manifest
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class PermissionActivity : AppCompatActivity() {
    private lateinit var ui: Ui
    private lateinit var content: LinearLayout
    private var showBasicDetails = false
    private val packageUri get() = Uri.fromParts("package", packageName, null)

    override fun onCreate(savedInstanceState: Bundle?) {
        AppAppearance.apply(this)
        super.onCreate(savedInstanceState)
        title = "权限与后台保活"
        showBasicDetails = savedInstanceState?.getBoolean("showBasicDetails") ?: false
        ui = Ui(this)
        val page = ui.column().apply {
            setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(28))
            addView(ui.topBar("权限与后台保活") { finish() })
        }
        content = ui.column()
        page.addView(content)
        setContentView(ScrollView(this).apply {
            id = R.id.permissions_scroll
            isFillViewport = true
            setBackgroundColor(ui.color(R.color.page))
            addView(page)
        })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("showBasicDetails", showBasicDetails)
        super.onSaveInstanceState(outState)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == SMS_REQUEST) {
            if (grantResults.isNotEmpty()) {
                getPreferences(MODE_PRIVATE).edit().putBoolean("askedSms", true).apply()
            }
            render()
        }
    }

    private fun granted(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun render() {
        content.removeAllViews()
        val sms = Manifest.permission.RECEIVE_SMS
        val smsGranted = granted(sms)
        val asked = getPreferences(MODE_PRIVATE).getBoolean("askedSms", false)

        // 第一层：必须权限
        ui.sectionTitle(content, "必须权限")
        content.addView(ui.card(
            fill = if (smsGranted) R.color.surface else R.color.danger_surface,
            border = if (smsGranted) R.color.card_stroke else R.color.line
        ) {
            addView(ui.row().apply {
                addView(ui.text("接收新短信", 17f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                addView(ui.badge(if (smsGranted) "✓ 已授权" else "未授权", if (smsGranted) Ui.Tone.SUCCESS else Ui.Tone.DANGER))
            })
            ui.gap(this, 8)
            if (smsGranted) {
                addView(ui.text("可正常接收系统送达的新短信，不读取手机历史短信。", 14f, R.color.muted))
            } else {
                val canRequest = !asked || shouldShowRequestPermissionRationale(sms)
                addView(ui.text("开启自动转发前，必须授予接收短信权限。", 14f, R.color.danger))
                ui.gap(this, 14)
                if (canRequest) {
                    addView(ui.button("授予短信权限", primary = true) {
                        requestPermissions(arrayOf(sms), SMS_REQUEST)
                    })
                    ui.gap(this, 8)
                }
                addView(ui.button("打开系统应用权限设置", primary = !canRequest) { openSettings() })
                ui.gap(this, 10)
                addView(ui.text("若未弹出系统提示，请在应用详情中进入「权限 → 短信」开启；如无法授权，请检查安装来源或设备策略。", 13f, R.color.muted))
            }
        })

        // 第二层：后台保活检查（4 项合一）
        ui.sectionTitle(content, "后台保活建议", "减少锁屏休眠后的发送延迟，按需调整即可")
        val restricted = getSystemService(ActivityManager::class.java).isBackgroundRestricted
        val exempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        val dataInfo = if (granted(Manifest.permission.ACCESS_NETWORK_STATE)) {
            when (getSystemService(ConnectivityManager::class.java).restrictBackgroundStatus) {
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED ->
                    Triple("受限", Ui.Tone.WARNING, "节省流量已限制本应用在移动网络下的后台联网。")
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED ->
                    Triple("已豁免", Ui.Tone.SUCCESS, "节省流量已开启，本应用已获白名单豁免。")
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED ->
                    Triple("正常", Ui.Tone.SUCCESS, "系统未开启节省流量限制。")
                else ->
                    Triple("未知", Ui.Tone.NEUTRAL, "系统未提供节省流量状态。")
            }
        } else Triple("缺权限", Ui.Tone.DANGER, "缺少查看网络状态的权限。")

        content.addView(ui.card(padH = 16, padV = 6) {
            addView(checkItem(
                name = "后台活动",
                badgeText = if (restricted) "受限" else "正常",
                tone = if (restricted) Ui.Tone.WARNING else Ui.Tone.SUCCESS,
                detail = if (restricted) "系统已限制后台活动，请在电池设置中允许后台运行。" else "系统未限制本应用的后台活动。",
                actionLabel = "去设置"
            ) { openSettings() })

            ui.divider(this, top = 2, bottom = 2)

            addView(checkItem(
                name = "电池优化",
                badgeText = if (exempt) "已豁免" else "建议豁免",
                tone = if (exempt) Ui.Tone.SUCCESS else Ui.Tone.WARNING,
                detail = if (exempt) "已加入电池优化白名单，休眠时也能及时发送。" else "已启用电池优化，锁屏休眠时可能延迟送达。",
                actionLabel = "去设置"
            ) { openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) })

            ui.divider(this, top = 2, bottom = 2)

            addView(checkItem(
                name = "后台流量",
                badgeText = dataInfo.first,
                tone = dataInfo.second,
                detail = dataInfo.third,
                actionLabel = "去设置"
            ) { openSettings(Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, packageUri)) })

            ui.divider(this, top = 2, bottom = 2)

            addView(checkItem(
                name = "厂商自启动",
                badgeText = "手动核对",
                tone = Ui.Tone.NEUTRAL,
                detail = "小米、红米、华为等机型请在系统设置中搜索「自启动」，允许短信转发。",
                actionLabel = "去设置"
            ) { openSettings(Intent(Settings.ACTION_SETTINGS)) })
        })

        // 第三层：安装时自动授予的基础权限
        val basics = listOf(
            Manifest.permission.INTERNET to "访问互联网 · 将短信推送到目的地",
            Manifest.permission.ACCESS_NETWORK_STATE to "查看网络状态 · 断网恢复后自动重试",
            Manifest.permission.RECEIVE_BOOT_COMPLETED to "开机自启恢复 · 重启后继续发送队列"
        )
        val grantedCount = basics.count { granted(it.first) }
        val allBasicOk = grantedCount == basics.size
        val expanded = showBasicDetails || !allBasicOk

        ui.sectionTitle(content, "系统基础权限")
        content.addView(ui.card(padH = 16, padV = 12) {
            addView(ui.row().apply {
                background = ui.ripple(R.color.surface, 10)
                setPadding(0, ui.dp(2), 0, ui.dp(2))
                addView(ui.column().apply {
                    addView(ui.text("联网与队列恢复权限", 15f, bold = true))
                    ui.gap(this, 2)
                    addView(ui.text(
                        if (allBasicOk) "安装时由系统自动授予，无需手动操作" else "检测到权限缺失，请检查安装包是否完整",
                        13f, if (allBasicOk) R.color.muted else R.color.danger
                    ))
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) })
                addView(ui.badge(
                    if (allBasicOk) "$grantedCount/${basics.size} 正常" else "异常",
                    if (allBasicOk) Ui.Tone.SUCCESS else Ui.Tone.DANGER
                ))
                if (allBasicOk) {
                    val chevron = ui.chevronDown(R.color.subtle).apply { rotation = if (expanded) 180f else 0f }
                    addView(chevron)
                    setOnClickListener {
                        ui.haptic(this)
                        showBasicDetails = !showBasicDetails
                        render()
                    }
                }
            })

            if (expanded) {
                for ((permission, purpose) in basics) {
                    ui.divider(this, top = 12, bottom = 10)
                    val allowed = granted(permission)
                    addView(ui.row().apply {
                        addView(ui.text(purpose, 14f), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) })
                        addView(ui.text(
                            if (allowed) "已授予" else "缺失",
                            13f, if (allowed) R.color.success else R.color.danger, bold = true
                        ))
                    })
                }
            }
        })
    }

    private fun checkItem(
        name: String,
        badgeText: String,
        tone: Ui.Tone,
        detail: String,
        actionLabel: String,
        action: () -> Unit
    ) = ui.row().apply {
        setPadding(0, ui.dp(12), 0, ui.dp(12))
        addView(ui.column().apply {
            addView(ui.row().apply {
                addView(ui.text(name, 15f, bold = true))
                addView(View(this@PermissionActivity), LinearLayout.LayoutParams(ui.dp(8), 1))
                addView(ui.badge(badgeText, tone))
            })
            ui.gap(this, 4)
            addView(ui.text(detail, 13f, R.color.muted))
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(10) })
        val needsAction = tone == Ui.Tone.WARNING || tone == Ui.Tone.DANGER
        if (needsAction) {
            addView(ui.button(actionLabel, compact = true, action = action))
        } else {
            addView(ui.ghostButton(if (tone == Ui.Tone.SUCCESS) "查看" else actionLabel, R.color.accent, action))
        }
    }

    private fun openSettings(preferred: Intent? = null) {
        val choices = listOfNotNull(
            preferred,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
            Intent(Settings.ACTION_SETTINGS)
        ).distinctBy { it.action }
        for (intent in choices) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        Toast.makeText(this, "请手动打开系统设置，找到「应用 → 短信转发」。", Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val SMS_REQUEST = 1
    }
}
