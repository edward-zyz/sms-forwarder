package com.kidbuddy.smsforwarder

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

class ForwardJob : JobService() {
    private class Run {
        val stopped = AtomicBoolean(false)
        @Volatile var connection: HttpsURLConnection? = null
    }
    @Volatile private var currentRun = Run()
    override fun onStartJob(params: JobParameters): Boolean {
        val run = Run()
        currentRun = run
        val stopped = run.stopped
        val generation = State.prefs(this).getLong("generation", 0)
        fun permitted() = !stopped.get() && State.enabled(this) && State.prefs(this).getLong("generation", 0) == generation
        Thread {
            var retry = false
            Store(this).use { store ->
                try {
                    repeat(20) {
                        if (!permitted()) return@repeat
                        val item = synchronized(State.gate) { store.next() } ?: return@repeat
                        val prefs = State.prefs(this)
                        val webhook = prefs.getString("webhook", "").orEmpty()
                        if (!Protocol.validWebhook(webhook)) {
                            State.status(this, "飞书地址无效，请重新配置")
                            return@repeat
                        }
                        try {
                            val conn = URL(webhook).openConnection() as HttpsURLConnection
                            run.connection = conn
                            conn.instanceFollowRedirects = false
                            conn.requestMethod = "POST"
                            conn.connectTimeout = 10000
                            conn.readTimeout = 10000
                            conn.doOutput = true
                            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                            val payload = Protocol.payload(item.text, prefs.getString("secret", "").orEmpty(), System.currentTimeMillis() / 1000).toByteArray(Charsets.UTF_8)
                            check(payload.size <= 20000) { "消息转义后超过飞书 20KB 限制，请清理该条积压" }
                            conn.setFixedLengthStreamingMode(payload.size)
                            if (!permitted()) { conn.disconnect(); return@repeat }
                            conn.outputStream.use { it.write(payload) }
                            val code = conn.responseCode
                            if (code != 200) throw IllegalStateException("HTTP $code")
                            val response = conn.inputStream.use { input ->
                                val output = ByteArrayOutputStream()
                                val buffer = ByteArray(4096)
                                while (true) {
                                    val size = input.read(buffer)
                                    if (size < 0) break
                                    check(output.size() + size <= 32768) { "响应过大" }
                                    output.write(buffer, 0, size)
                                }
                                JSONObject(output.toString("UTF-8"))
                            }
                            check(response.has("code") && response.optInt("code", -1) == 0) { "飞书错误码 ${response.optInt("code", -1)}" }
                            synchronized(State.gate) { store.done(item.id) }
                            State.status(this, "飞书已接收一条消息")
                        } catch (e: Exception) {
                            if (!stopped.get() && State.enabled(this)) {
                                synchronized(State.gate) { store.failed(item.id) }
                                val detail = if (e is IllegalStateException) e.message.orEmpty() else "网络连接失败"
                                State.status(this, "$detail；第 ${item.attempts + 1}/8 次失败，保留待发")
                                retry = true
                            }
                            return@use
                        } finally { run.connection?.disconnect(); run.connection = null }
                    }
                    retry = store.next() != null
                } catch (_: Exception) { State.status(this, "处理队列失败，稍后重试"); retry = true }
            }
            synchronized(State.gate) {
                if (!stopped.get()) {
                    // 与接收广播共用锁，收尾时重新读队列，防止恰在发完最后一条时新短信入队。
                    val remaining = Store(this).use { it.next() != null }
                    jobFinished(params, (retry || remaining) && State.enabled(this))
                }
            }
        }.start()
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        currentRun.stopped.set(true)
        currentRun.connection?.disconnect()
        return State.enabled(this)
    }
    companion object {
        private const val JOB_ID = 7101
        fun schedule(context: Context) {
            if (!State.enabled(context)) return
            val scheduler = context.getSystemService(JobScheduler::class.java)
            RecoveryJob.ensure(context)
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val result = scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, ForwardJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
                .setBackoffCriteria(30000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
            if (result != JobScheduler.RESULT_SUCCESS) State.status(context, "后台任务未能排程，请打开 App 重试")
        }
        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
            context.getSystemService(JobScheduler::class.java).cancel(RecoveryJob.JOB_ID)
        }
    }
}

/** 周期任务仅负责唤起主任务，不发送消息，避免与主任务并发投递。 */
class RecoveryJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        synchronized(State.gate) {
            if (State.enabled(this) && Store(this).use { it.next() != null }) ForwardJob.schedule(this)
        }
        return false
    }
    override fun onStopJob(params: JobParameters) = false
    companion object {
        const val JOB_ID = 7102
        fun ensure(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(JOB_ID) == null) {
                scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, RecoveryJob::class.java))
                    .setPeriodic(15 * 60 * 1000L).setPersisted(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build())
            }
        }
    }
}
