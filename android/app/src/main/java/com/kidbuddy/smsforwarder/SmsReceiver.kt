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
                val rules = try { FilterSettings.load(context) } catch (_: IllegalArgumentException) {
                    State.status(context, "过滤配置异常，未转发，请重新保存过滤设置")
                    return
                }
                val decision = rules.evaluate(first.originatingAddress, body)
                if (!decision.allowed) {
                    State.status(context, decision.reason)
                    return
                }
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(first.timestampMillis))
                val text = "短信转发\n发件人：${first.originatingAddress ?: "未知"}\n时间：$time\n\n$body"
                val pdus = (intent.extras?.get("pdus") as? Array<*>)?.mapNotNull { it as? ByteArray } ?: return
                val hash = Protocol.fingerprint(intent.getIntExtra("subscription", -1), pdus)
                Store(context).use { if (it.add(text, hash)) State.status(context, "收到短信，等待发送") }
                ForwardJob.schedule(context)
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
