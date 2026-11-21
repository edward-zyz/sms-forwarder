package com.kidbuddy.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        synchronized(State.gate) {
            if (!State.enabled(context)) return
            try {
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                if (messages.isEmpty()) return
                val first = messages.first()
                val body = messages.joinToString("") { it.messageBody.orEmpty() }
                val config = try { ConfigStore.load(context) } catch (_: IllegalArgumentException) {
                    State.status(context, "转发配置异常，未转发，请重新检查规则与目的地")
                    return
                }
                val decision = RoutingEngine.evaluate(config, first.originatingAddress, body)
                if (!decision.allowed) {
                    State.status(context, decision.summaryReason)
                    return
                }
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(first.timestampMillis))
                val pdus = (intent.extras?.get("pdus") as? Array<*>)?.mapNotNull { it as? ByteArray } ?: return
                val smsHash = Protocol.fingerprint(intent.getIntExtra("subscription", -1), pdus)
                var queued = 0
                val deviceLabel = State.deviceLabel(context)
                Store(context).use { store ->
                    for (dispatch in decision.dispatches) {
                        val destHash = "$smsHash:${dispatch.destination.id}"
                        val text = MessageFormatter.format(dispatch.rule.name, first.originatingAddress, time, body, deviceLabel)
                        if (store.add(text, destHash, dispatch.destination.id, dispatch.rule.name)) {
                            queued++
                        }
                    }
                }
                if (queued > 0) {
                    val msg = if (queued == 1) {
                        val target = decision.dispatches.first().destination
                        "收到短信，等待发送到${target.type.displayName}「${target.name}」"
                    } else {
                        "收到短信，已入队等待发送到 $queued 个目的地"
                    }
                    State.status(context, msg)
                    ForwardJob.schedule(context)
                }
            } catch (e: IllegalArgumentException) {
                State.status(context, e.message ?: "短信未入队")
            } catch (_: Exception) { State.status(context, "短信入队失败，请检查设备存储空间") }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && State.enabled(context)) ForwardJob.schedule(context)
    }
}
