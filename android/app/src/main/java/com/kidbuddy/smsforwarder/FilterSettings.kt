package com.kidbuddy.smsforwarder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 一个快照一次 commit；与广播接收共用 gate，保存失败不会继续采用新内存快照。 */
object FilterSettings {
    private const val FILE = "sms_filter"
    private const val KEY = "snapshot"
    private const val MAX_SNAPSHOT_LENGTH = 20000
    private val persistence = ConfigPersistenceMachine()

    fun load(context: Context): FilterRules = synchronized(State.gate) {
        if (!persistence.canLoad()) throw IllegalArgumentException("过滤配置保存失败，请重新保存")
        try {
            val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            if (!prefs.contains(KEY)) return@synchronized FilterRules()
            val snapshot = prefs.getString(KEY, null) ?: throw IllegalArgumentException()
            require(snapshot.length <= MAX_SNAPSHOT_LENGTH)
            val json = JSONObject(snapshot)
            val version = json.get("version")
            require(version is Int && version == 1)
            fun boolean(name: String): Boolean {
                val value = json.get(name)
                require(value is Boolean)
                return value
            }
            fun optionalBoolean(name: String, default: Boolean): Boolean {
                if (!json.has(name)) return default
                val value = json.get(name)
                require(value is Boolean)
                return value
            }
            fun strings(name: String): List<String> {
                val values = json.getJSONArray(name)
                require(values.length() <= FilterRules.MAX_ITEMS)
                return (0 until values.length()).map { index ->
                    val value = values.get(index)
                    require(value is String)
                    value
                }
            }
            FilterRules(
                enabled = boolean("enabled"),
                includeKeywords = strings("includeKeywords"),
                requireDigits = boolean("requireDigits"),
                matchAll = boolean("matchAll"),
                excludeKeywords = strings("excludeKeywords"),
                allowedSenders = strings("allowedSenders"),
                requireCodeWord = optionalBoolean("requireCodeWord", false),
            ).also { require(it.validationError() == null) }
        } catch (_: Exception) {
            throw IllegalArgumentException("过滤配置异常，请重新保存后再转发")
        }
    }

    fun save(context: Context, rules: FilterRules): Boolean = synchronized(State.gate) {
        if (rules.validationError() != null) return@synchronized false
        val snapshot = JSONObject().put("version", 1).put("enabled", rules.enabled)
            .put("includeKeywords", JSONArray(rules.includeKeywords)).put("requireDigits", rules.requireDigits)
            .put("matchAll", rules.matchAll).put("excludeKeywords", JSONArray(rules.excludeKeywords))
            .put("allowedSenders", JSONArray(rules.allowedSenders))
            .put("requireCodeWord", rules.requireCodeWord).toString()
        if (snapshot.length > MAX_SNAPSHOT_LENGTH) return@synchronized false
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        // 保留原值（包括损坏值），不能把损坏配置恢复成默认放行。
        val hadPrevious = prefs.contains(KEY)
        val previous = prefs.all[KEY]
        val success = runCatching { prefs.edit().putString(KEY, snapshot).commit() }.getOrDefault(false)
        if (success) {
            persistence.onSaveSucceeded()
            return@synchronized true
        }
        // Android commit 失败仍已更新内存，第二次 commit 会先恢复内存再尝试恢复磁盘。
        val restored = runCatching {
            val editor = prefs.edit()
            when {
                !hadPrevious -> editor.remove(KEY)
                previous is String -> editor.putString(KEY, previous)
                previous is Boolean -> editor.putBoolean(KEY, previous)
                previous is Int -> editor.putInt(KEY, previous)
                previous is Long -> editor.putLong(KEY, previous)
                previous is Float -> editor.putFloat(KEY, previous)
                previous is Set<*> -> editor.putStringSet(KEY, previous.filterIsInstance<String>().toSet())
                else -> throw IllegalArgumentException()
            }
            editor.commit()
        }.getOrDefault(false)
        persistence.onSaveFailed(restored)
        false
    }

    fun summary(context: Context): String {
        return FilterDescriptions.describe(load(context))
    }
}
