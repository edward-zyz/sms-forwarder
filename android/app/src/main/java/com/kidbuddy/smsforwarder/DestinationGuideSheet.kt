package com.kidbuddy.smsforwarder

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * 可复用的渠道配置教程弹窗（Material3 BottomSheet）。
 * 支持按指定平台直接打开，也支持在弹窗内切换查看 4 种平台教程。
 */
object DestinationGuideSheet {
    fun show(
        context: Context,
        initialType: DestinationType = DestinationType.FEISHU,
        onTypeChanged: ((DestinationType) -> Unit)? = null,
    ): BottomSheetDialog {
        val ui = Ui(context)
        var currentType = initialType
        lateinit var contentContainer: LinearLayout
        lateinit var tabButtons: List<Pair<DestinationType, TextView>>

        return ui.bottomSheet(
            title = "渠道配置教程",
            subtitle = "按步骤创建群机器人并开启安全校验，可随时切换平台查看。",
        ) { body, dialog, scroll ->
            val tabRow = ui.row()
            tabButtons = DestinationType.entries.mapIndexed { index, type ->
                val tab = TextView(context).apply {
                    text = type.displayName
                    textSize = 13.5f
                    gravity = Gravity.CENTER
                    minHeight = ui.dp(44)
                    minimumHeight = ui.dp(44)
                    minWidth = ui.dp(44)
                    minimumWidth = ui.dp(44)
                    setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                        if (index < DestinationType.entries.lastIndex) marginEnd = ui.dp(8)
                    }
                }
                tabRow.addView(tab)
                type to tab
            }

            val tabScroller = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                addView(tabRow)
                layoutParams = LinearLayout.LayoutParams(-1, -2)
            }
            body.addView(tabScroller)
            ui.gap(body, 14)

            contentContainer = ui.column()
            body.addView(contentContainer)

            fun styleTabs() {
                tabButtons.forEach { (type, tab) ->
                    val selected = type == currentType
                    tab.isSelected = selected
                    tab.typeface = Typeface.create(
                        if (selected) "sans-serif-medium" else "sans-serif",
                        Typeface.NORMAL,
                    )
                    tab.setTextColor(ui.color(if (selected) R.color.accent else R.color.ink))
                    tab.background = if (selected) {
                        ui.ripple(R.color.accent_surface, 12, R.color.accent)
                    } else {
                        ui.ripple(R.color.field, 12, R.color.line)
                    }
                    tab.contentDescription = if (selected) {
                        "${type.displayName}配置教程，已选中"
                    } else {
                        "切换到${type.displayName}配置教程"
                    }
                }
            }

            fun renderGuide(type: DestinationType) {
                currentType = type
                styleTabs()
                contentContainer.removeAllViews()
                val guide = DestinationGuides.forType(type)

                // 1. 平台摘要与适用场景卡片
                contentContainer.addView(ui.column().apply {
                    background = ui.round(R.color.accent_surface, 14)
                    setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
                    addView(ui.row().apply {
                        addView(ui.iconTile(R.drawable.ic_send, tileColorFor(guide.type), sizeDp = 36, iconSizeDp = 18, radiusDp = 10))
                        addView(ui.column().apply {
                            addView(ui.text(guide.title, 16f, R.color.ink, bold = true).apply {
                                isAccessibilityHeading = true
                            })
                            ui.gap(this, 2)
                            addView(ui.text(guide.summary, 13f, R.color.muted))
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                    })
                    ui.gap(this, 10)
                    addView(ui.text("适用场景：${guide.scenario}", 13.5f, R.color.ink))
                })

                ui.gap(contentContainer, 14)
                addSectionHeading(ui, contentContainer, "操作步骤（共 ${guide.steps.size} 步）")
                contentContainer.addView(ui.column().apply {
                    background = ui.round(R.color.field, 14)
                    setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
                    guide.steps.forEachIndexed { idx, step ->
                        if (idx > 0) ui.divider(this, top = 12, bottom = 12)
                        addView(ui.row().apply {
                            gravity = Gravity.TOP
                            addView(TextView(context).apply {
                                text = (idx + 1).toString()
                                textSize = 12f
                                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                                setTextColor(ui.color(R.color.on_accent))
                                gravity = Gravity.CENTER
                                background = ui.round(R.color.accent, 99)
                                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                                layoutParams = LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)).apply {
                                    topMargin = ui.dp(2)
                                    marginEnd = ui.dp(10)
                                }
                            })
                            addView(ui.column().apply {
                                addView(ui.text("第 ${idx + 1} 步：${step.title}", 14.5f, R.color.ink, bold = true))
                                ui.gap(this, 4)
                                addView(ui.text(step.detail, 13.5f, R.color.muted))
                            }, LinearLayout.LayoutParams(0, -2, 1f))
                        })
                    }
                })

                ui.gap(contentContainer, 14)
                addSectionHeading(ui, contentContainer, "安全与加签说明")
                contentContainer.addView(ui.row().apply {
                    gravity = Gravity.TOP
                    background = ui.round(R.color.success_surface, 14)
                    setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
                    addView(ui.icon(R.drawable.ic_shield, 18, R.color.success).apply {
                        layoutParams = LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)).apply {
                            topMargin = ui.dp(2)
                            marginEnd = ui.dp(10)
                        }
                    })
                    addView(ui.text(guide.securityNote, 13.5f, R.color.ink), LinearLayout.LayoutParams(0, -2, 1f))
                })

                ui.gap(contentContainer, 14)
                addSectionHeading(ui, contentContainer, "Webhook 地址格式示例")
                contentContainer.addView(ui.column().apply {
                    background = ui.round(R.color.field, 14, R.color.line)
                    setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
                    addView(ui.text(guide.webhookExample, 12.5f, R.color.ink).apply {
                        typeface = Typeface.MONOSPACE
                        setTextIsSelectable(true)
                    })
                    ui.gap(this, 6)
                    addView(ui.text("仅作格式示例，请在群机器人设置页复制您自己的真实地址。", 12f, R.color.subtle))
                })

                ui.gap(contentContainer, 14)
                addSectionHeading(ui, contentContainer, "常见避坑提示")
                contentContainer.addView(ui.column().apply {
                    background = ui.round(R.color.warning_surface, 14)
                    setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
                    guide.pitfalls.forEachIndexed { idx, pitfall ->
                        if (idx > 0) ui.gap(this, 8)
                        addView(ui.text("• $pitfall", 13.5f, R.color.ink))
                    }
                })
            }

            tabButtons.forEach { (type, tab) ->
                tab.setOnClickListener {
                    ui.haptic(it)
                    if (type != currentType) {
                        renderGuide(type)
                        onTypeChanged?.invoke(type)
                        scroll.post { scroll.smoothScrollTo(0, 0) }
                        contentContainer.announceForAccessibility("已切换为${DestinationGuides.forType(type).title}")
                    }
                }
            }

            renderGuide(initialType)

            ui.gap(body, 16)
            body.addView(ui.button("我知道了", primary = true) { dialog.dismiss() })
        }.also { it.show() }
    }

    private fun addSectionHeading(ui: Ui, parent: LinearLayout, title: String) {
        parent.addView(ui.text(title, 13f, R.color.muted, bold = true).apply {
            isAccessibilityHeading = true
            setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(8))
        })
    }

    private fun tileColorFor(type: DestinationType): Int = when (type) {
        DestinationType.FEISHU -> R.color.tile_blue
        DestinationType.WECOM -> R.color.tile_green
        DestinationType.DINGTALK -> R.color.tile_indigo
        DestinationType.CUSTOM -> R.color.tile_gray
    }
}
