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
            setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(28))
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
        content.addView(ui.card(padH = 18, padV = 16) {
            addView(ui.row().apply {
                addView(ui.iconTile(R.drawable.ic_shield, if (smsGranted) R.color.tile_green else R.color.danger, sizeDp = 36, iconSizeDp = 19, radiusDp = 10))
                addView(ui.text("接收新短信", 16f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                addView(ui.valueText(
                    if (smsGranted) "已授权" else "未授权",
                    if (smsGranted) R.color.subtle else R.color.danger
                ))
            })
            ui.gap(this, 6)
            if (smsGranted) {
                addView(ui.text("可正常接收系统送达的新短信，不读取手机历史短信。", 13f, R.color.muted))
            } else {
                val canRequest = !asked || shouldShowRequestPermissionRationale(sms)
                addView(ui.text("开启自动转发前，必须授予接收短信权限。", 13.5f, R.color.danger))
                ui.gap(this, 12)
                if (canRequest) {
                    addView(ui.button("授予短信权限", primary = true) {
                        requestPermissions(arrayOf(sms), SMS_REQUEST)
                    })
                    ui.gap(this, 8)
                }
                addView(ui.button("打开系统应用权限设置", primary = !canRequest) { openSettings() })
                ui.gap(this, 8)
                addView(ui.text("若未弹出系统提示，请在应用详情中进入「权限 → 短信」开启。", 12.5f, R.color.subtle))
            }
        })

        // 第二层：后台保活建议（纯白分组列表，整行可点，右侧状态文字 + 极细箭头）
        ui.sectionTitle(content, "后台保活建议", "减少锁屏休眠后的发送延迟，按需调整即可")
        val restricted = getSystemService(ActivityManager::class.java).isBackgroundRestricted
        val exempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        val dataInfo = if (granted(Manifest.permission.ACCESS_NETWORK_STATE)) {
            when (getSystemService(ConnectivityManager::class.java).restrictBackgroundStatus) {
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED ->
                    Triple("受限", R.color.warning, "节省流量已限制本应用在移动网络下的后台联网")
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED ->
                    Triple("已豁免", R.color.subtle, "节省流量已开启，本应用已获白名单豁免")
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED ->
                    Triple("正常", R.color.subtle, "系统未开启节省流量限制")
                else ->
                    Triple("未知", R.color.subtle, "系统未提供节省流量状态")
            }
        } else Triple("缺权限", R.color.danger, "缺少查看网络状态的权限")

        content.addView(ui.listCard {
            addView(checkRow(
                iconRes = R.drawable.ic_bolt,
                tileColorRes = R.color.tile_blue,
                name = "后台活动",
                status = if (restricted) "受限" else "正常",
                statusColor = if (restricted) R.color.warning else R.color.subtle,
                detail = if (restricted) "系统已限制后台活动，建议允许后台运行" else "系统未限制本应用的后台活动"
            ) { openSettings() })

            ui.insetDivider(this, startDp = 62, endDp = 18)

            addView(checkRow(
                iconRes = R.drawable.ic_battery,
                tileColorRes = R.color.tile_green,
                name = "电池优化",
                status = if (exempt) "已豁免" else "建议豁免",
                statusColor = if (exempt) R.color.subtle else R.color.warning,
                detail = if (exempt) "已加入白名单，休眠时也能及时发送" else "锁屏休眠时可能延迟送达"
            ) { openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) })

            ui.insetDivider(this, startDp = 62, endDp = 18)

            addView(checkRow(
                iconRes = R.drawable.ic_network,
                tileColorRes = R.color.tile_teal,
                name = "后台流量",
                status = dataInfo.first,
                statusColor = dataInfo.second,
                detail = dataInfo.third
            ) { openSettings(Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, packageUri)) })

            ui.insetDivider(this, startDp = 62, endDp = 18)

            addView(checkRow(
                iconRes = R.drawable.ic_shield,
                tileColorRes = R.color.tile_amber,
                name = "厂商自启动",
                status = "手动核对",
                statusColor = R.color.subtle,
                detail = "小米、红米等机型请在系统设置中搜索「自启动」并允许"
            ) { openSettings(Intent(Settings.ACTION_SETTINGS)) })
        })

        // 第二层补充：国产手机放行提示（纯白大圆角 + 克制琥珀竖条）
        ui.sectionTitle(content, "国产手机放行提示", "收不到验证码或后台延迟时，请依次检查以下 3 项")
        content.addView(ui.card(padH = 18, padV = 16) {
            addView(ui.row().apply {
                addView(android.view.View(this@PermissionActivity).apply {
                    background = ui.round(R.color.warning, 99)
                    layoutParams = LinearLayout.LayoutParams(ui.dp(3), ui.dp(14)).apply { marginEnd = ui.dp(8) }
                })
                addView(ui.text("关键放行步骤", 15f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                addView(ui.valueText("必读", R.color.warning))
            })
            ui.gap(this, 10)
            addView(ui.text(
                "① Android 13–15 侧载安装：若短信权限无法勾选，请在「应用信息」右上角菜单「⋮」点击「允许受限制的设置」后再返回授权。",
                13.5f, R.color.muted
            ))
            ui.gap(this, 6)
            addView(ui.text(
                "② 华为 / 小米 / OPPO / vivo：请在系统短信或安全中心关闭「验证码安全保护」；小米 / 红米机型还需放行「通知类短信」权限。",
                13.5f, R.color.muted
            ))
            ui.gap(this, 6)
            addView(ui.text(
                "③ 后台省电与自启动：将本应用的省电策略设为「无限制」并允许「自启动」，避免锁屏休眠后验证码延迟送达。",
                13.5f, R.color.muted
            ))
            ui.gap(this, 14)
            addView(ui.button("打开应用信息设置") { openSettings() })
        })

        // 第三层：系统基础权限（0 内边距卡片 + cell 满宽按压态）
        val basics = listOf(
            Manifest.permission.INTERNET to "访问互联网 · 将短信推送到目的地",
            Manifest.permission.ACCESS_NETWORK_STATE to "查看网络状态 · 断网恢复后自动重试",
            Manifest.permission.RECEIVE_BOOT_COMPLETED to "开机自启恢复 · 重启后继续发送队列"
        )
        val grantedCount = basics.count { granted(it.first) }
        val allBasicOk = grantedCount == basics.size
        val expanded = showBasicDetails || !allBasicOk

        ui.sectionTitle(content, "系统基础权限")
        content.addView(ui.listCard {
            addView(ui.row().apply {
                background = ui.transparentRipple(0)
                setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(14))
                addView(ui.column().apply {
                    addView(ui.text("联网与队列恢复权限", 15f, bold = true))
                    ui.gap(this, 2)
                    addView(ui.text(
                        if (allBasicOk) "安装时自动授予，无需手动操作" else "检测到权限缺失，请检查安装包",
                        12.5f, if (allBasicOk) R.color.subtle else R.color.danger
                    ))
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) })
                addView(ui.valueText(
                    if (allBasicOk) "$grantedCount/${basics.size} 正常" else "异常",
                    if (allBasicOk) R.color.subtle else R.color.danger
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
                    ui.insetDivider(this, startDp = 18, endDp = 18)
                    val allowed = granted(permission)
                    addView(ui.row().apply {
                        setPadding(ui.dp(18), ui.dp(11), ui.dp(18), ui.dp(11))
                        addView(ui.text(purpose, 13.5f, R.color.muted), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) })
                        addView(ui.valueText(
                            if (allowed) "已授予" else "缺失",
                            if (allowed) R.color.subtle else R.color.danger
                        ))
                    })
                }
            }
        })
    }

    private fun checkRow(
        iconRes: Int,
        tileColorRes: Int,
        name: String,
        status: String,
        statusColor: Int,
        detail: String,
        action: () -> Unit
    ) = ui.row().apply {
        minimumHeight = ui.dp(64)
        setPadding(ui.dp(18), ui.dp(13), ui.dp(18), ui.dp(13))
        background = ui.transparentRipple(0)
        addView(ui.iconTile(iconRes, tileColorRes, sizeDp = 32, iconSizeDp = 17, radiusDp = 9))
        addView(ui.column().apply {
            addView(ui.text(name, 15.5f, bold = true))
            ui.gap(this, 2)
            addView(ui.text(detail, 12.5f, R.color.subtle))
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(10) })
        addView(ui.valueText(status, statusColor))
        addView(ui.chevronRight())
        setOnClickListener { ui.haptic(this); action() }
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
