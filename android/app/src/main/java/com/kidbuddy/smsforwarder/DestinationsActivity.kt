package com.kidbuddy.smsforwarder

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** 管理转发目的地（飞书、企业微信、钉钉、自定义 Webhook）。 */
class DestinationsActivity : AppCompatActivity() {
    private lateinit var ui: Ui
    private lateinit var listContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        AppAppearance.apply(this)
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val content = ui.column().apply { setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(28)) }
        val scroll = ScrollView(this).apply {
            id = R.id.destinations_scroll
            isFillViewport = true
            setBackgroundColor(ui.color(R.color.page))
            addView(content)
        }
        setContentView(scroll)

        content.addView(ui.topBar("转发目的地") { finish() })
        content.addView(ui.text("支持飞书、企业微信、钉钉和自定义 HTTPS Webhook，添加后可在规则中绑定。", 13f, R.color.muted))
        ui.gap(content, 14)
        content.addView(ui.button("添加目的地", primary = true) {
            startActivity(Intent(this, DestinationEditActivity::class.java))
        })
        ui.gap(content, 16)

        listContainer = ui.column()
        content.addView(listContainer)
    }

    override fun onResume() {
        super.onResume()
        renderList()
    }

    private fun renderList() {
        listContainer.removeAllViews()
        val configResult = runCatching { ConfigStore.load(this) }
        if (configResult.isFailure) {
            listContainer.addView(ui.card(R.color.danger_surface, border = R.color.line) {
                addView(ui.text("配置读取异常，请检查或重新添加目的地。", 14f, R.color.danger, bold = true))
            })
            return
        }
        val config = configResult.getOrThrow()
        if (config.destinations.isEmpty()) {
            listContainer.addView(ui.card {
                addView(ui.text("还没有转发目的地", 17f, bold = true))
                ui.gap(this, 6)
                addView(ui.text("点击上方「添加目的地」，可配置飞书、企业微信、钉钉或自定义 Webhook 机器人。", 14f, R.color.muted))
            })
            return
        }

        ui.sectionTitle(listContainer, "已添加目的地（${config.destinations.size}）")
        config.destinations.forEach { dest ->
            val refCount = config.rules.count { it.destinationId == dest.id }
            listContainer.addView(ui.card(padH = 16, padV = 14) {
                addView(ui.row().apply {
                    addView(ui.text(dest.name, 17f, bold = true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) })
                    addView(ui.badge(dest.type.displayName, Ui.Tone.ACCENT))
                })
                ui.gap(this, 6)
                val secretStatus = when {
                    !dest.type.supportsSecret -> "无需签名密钥"
                    dest.secret.isNotBlank() -> "已启用签名校验"
                    else -> "未设置签名密钥"
                }
                val refStatus = if (refCount > 0) "正被 $refCount 条规则使用" else "未被规则引用"
                addView(ui.text("地址已隐藏 · $secretStatus · $refStatus", 13f, R.color.muted))
                ui.divider(this, top = 12, bottom = 10)
                addView(ui.row().apply {
                    addView(ui.button("编辑", compact = true) {
                        startActivity(Intent(this@DestinationsActivity, DestinationEditActivity::class.java).apply {
                            putExtra(DestinationEditActivity.EXTRA_DESTINATION_ID, dest.id)
                        })
                    }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(8) })
                    addView(ui.button("发测试消息", compact = true) { sendTestTo(dest) })
                    addView(View(this@DestinationsActivity), LinearLayout.LayoutParams(0, 1, 1f))
                    addView(ui.ghostButton("删除", R.color.danger) { confirmDelete(dest, refCount) })
                })
            })
        }
    }

    private fun sendTestTo(dest: Destination) {
        if (!State.enabled(this)) {
            Toast.makeText(this, "请先返回主页开启自动转发", Toast.LENGTH_LONG).show()
            return
        }
        ui.dialog()
            .setTitle("发送测试消息")
            .setMessage("将向${dest.type.displayName}「${dest.name}」发送一条测试消息，不包含真实短信。")
            .setPositiveButton("发送") { _, _ ->
                try {
                    synchronized(State.gate) {
                        if (!State.enabled(this)) {
                            Toast.makeText(this, "转发已暂停", Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                        val text = "短信转发\n测试消息：安卓手机已准备好，收到此消息表示${dest.type.displayName}「${dest.name}」连接成功。"
                        Store(this).use { it.add(text, null, dest.id, "测试消息") }
                        State.status(this, "测试消息已入队，等待「${dest.name}」确认")
                        ForwardJob.schedule(this)
                    }
                    Toast.makeText(this, "测试消息已加入发送队列", Toast.LENGTH_SHORT).show()
                } catch (e: IllegalArgumentException) {
                    Toast.makeText(this, e.message.orEmpty(), Toast.LENGTH_LONG).show()
                } catch (_: Exception) {
                    Toast.makeText(this, "测试消息入队失败，请检查存储空间", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete(dest: Destination, refCount: Int) {
        if (refCount > 0) {
            ui.dialog()
                .setTitle("无法删除目的地")
                .setMessage("「${dest.name}」正被 $refCount 条转发规则使用。请先在「转发规则」中修改或删除这些规则，再删除此目的地。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        ui.dialog()
            .setTitle("删除目的地？")
            .setMessage("将删除${dest.type.displayName}「${dest.name}」的配置。")
            .setPositiveButton("删除") { _, _ ->
                synchronized(State.gate) {
                    val current = runCatching { ConfigStore.load(this) }.getOrDefault(ForwardConfig())
                    val next = current.copy(destinations = current.destinations.filterNot { it.id == dest.id })
                    if (ConfigStore.save(this, next)) {
                        Toast.makeText(this, "已删除「${dest.name}」", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "删除失败，请重试", Toast.LENGTH_LONG).show()
                    }
                }
                renderList()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
