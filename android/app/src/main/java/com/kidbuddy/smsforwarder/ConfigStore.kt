package com.kidbuddy.smsforwarder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 多目的地与多规则配置存储；原子快照 + 失败恢复，首次升级自动迁移旧版单飞书配置。 */
object ConfigStore {
    const val LEGACY_DESTINATION_ID = "legacy-feishu"
    const val LEGACY_RULE_ID = "legacy-rule"
    private const val FILE = "forward_config"
    private const val KEY = "snapshot"
    private const val MAX_SNAPSHOT_LENGTH = 200_000
    private var failedRestore = false

    fun newId(): String = UUID.randomUUID().toString()

    fun load(context: Context): ForwardConfig = synchronized(State.gate) {
        if (failedRestore) throw IllegalArgumentException("转发配置保存失败，请重新保存")
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY)) {
            return@synchronized migrateLegacyIfNeeded(context)
        }
        try {
            val snapshot = prefs.getString(KEY, null) ?: throw IllegalArgumentException()
            require(snapshot.length <= MAX_SNAPSHOT_LENGTH)
            parseSnapshot(snapshot)
        } catch (_: Exception) {
            throw IllegalArgumentException("转发配置异常，请重新检查并保存")
        }
    }

    fun save(context: Context, config: ForwardConfig): Boolean = synchronized(State.gate) {
        if (config.validationError() != null) return@synchronized false
        val snapshot = serializeSnapshot(config)
        if (snapshot.length > MAX_SNAPSHOT_LENGTH) return@synchronized false
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val hadPrevious = prefs.contains(KEY)
        val previous = prefs.all[KEY]
        val success = runCatching { prefs.edit().putString(KEY, snapshot).commit() }.getOrDefault(false)
        if (success) {
            failedRestore = false
            // 同步更新 generation，确保正在运行中的发送任务重新校验配置。
            val settings = State.prefs(context)
            settings.edit().putLong("generation", settings.getLong("generation", 0) + 1).apply()
            return@synchronized true
        }
        val restored = runCatching {
            val editor = prefs.edit()
            when {
                !hadPrevious -> editor.remove(KEY)
                previous is String -> editor.putString(KEY, previous)
                else -> throw IllegalArgumentException()
            }
            editor.commit()
        }.getOrDefault(false)
        failedRestore = !restored
        false
    }

    /** 兼容旧版未绑定 destinationId 的历史待发队列条目。 */
    fun resolveDestination(context: Context, config: ForwardConfig, destinationId: String): Destination? {
        if (destinationId.isNotBlank()) return config.destinationById(destinationId)
        config.destinationById(LEGACY_DESTINATION_ID)?.let { return it }
        config.destinations.firstOrNull { it.type == DestinationType.FEISHU }?.let { return it }
        val legacyWebhook = State.prefs(context).getString("webhook", "").orEmpty().trim()
        if (Protocol.validWebhook(legacyWebhook)) {
            return Destination(
                id = LEGACY_DESTINATION_ID,
                name = "飞书（原配置）",
                type = DestinationType.FEISHU,
                webhook = legacyWebhook,
                secret = State.prefs(context).getString("secret", "").orEmpty().trim(),
            )
        }
        return null
    }

    private fun migrateLegacyIfNeeded(context: Context): ForwardConfig {
        val settings = State.prefs(context)
        val legacyWebhook = settings.getString("webhook", "").orEmpty().trim()
        if (!Protocol.validWebhook(legacyWebhook)) return ForwardConfig()
        val legacySecret = settings.getString("secret", "").orEmpty().trim()
        // 如果旧过滤配置损坏，不静默降级为全部转发，而是抛出异常提醒修复。
        val legacyFilter = FilterSettings.load(context)
        val migrated = ForwardConfig(
            destinations = listOf(
                Destination(
                    id = LEGACY_DESTINATION_ID,
                    name = "默认飞书群",
                    type = DestinationType.FEISHU,
                    webhook = legacyWebhook,
                    secret = legacySecret,
                ),
            ),
            rules = listOf(
                ForwardRule(
                    id = LEGACY_RULE_ID,
                    name = "默认规则",
                    enabled = true,
                    destinationId = LEGACY_DESTINATION_ID,
                    filter = legacyFilter,
                ),
            ),
        )
        save(context, migrated)
        return migrated
    }

    private fun serializeSnapshot(config: ForwardConfig): String {
        val destArray = JSONArray()
        config.destinations.forEach { d ->
            destArray.put(
                JSONObject()
                    .put("id", d.id)
                    .put("name", d.name.trim())
                    .put("type", d.type.id)
                    .put("webhook", d.webhook.trim())
                    .put("secret", d.secret.trim()),
            )
        }
        val ruleArray = JSONArray()
        config.rules.forEach { r ->
            val filterJson = JSONObject()
                .put("enabled", r.filter.enabled)
                .put("includeKeywords", JSONArray(r.filter.includeKeywords))
                .put("requireDigits", r.filter.requireDigits)
                .put("matchAll", r.filter.matchAll)
                .put("excludeKeywords", JSONArray(r.filter.excludeKeywords))
                .put("allowedSenders", JSONArray(r.filter.allowedSenders))
                .put("requireCodeWord", r.filter.requireCodeWord)
            ruleArray.put(
                JSONObject()
                    .put("id", r.id)
                    .put("name", r.name.trim())
                    .put("enabled", r.enabled)
                    .put("destinationId", r.destinationId)
                    .put("filter", filterJson),
            )
        }
        return JSONObject()
            .put("version", 1)
            .put("destinations", destArray)
            .put("rules", ruleArray)
            .toString()
    }

    private fun parseSnapshot(snapshot: String): ForwardConfig {
        val json = JSONObject(snapshot)
        val version = json.get("version")
        require(version is Int && version == 1)

        val destArray = json.getJSONArray("destinations")
        require(destArray.length() <= ForwardConfig.MAX_DESTINATIONS)
        val destinations = (0 until destArray.length()).map { i ->
            val obj = destArray.getJSONObject(i)
            val type = DestinationType.fromId(obj.getString("type"))
                ?: throw IllegalArgumentException("未知目的地类型")
            Destination(
                id = obj.getString("id"),
                name = obj.getString("name"),
                type = type,
                webhook = obj.getString("webhook"),
                secret = obj.optString("secret", ""),
            )
        }

        val ruleArray = json.getJSONArray("rules")
        require(ruleArray.length() <= ForwardConfig.MAX_RULES)
        val rules = (0 until ruleArray.length()).map { i ->
            val obj = ruleArray.getJSONObject(i)
            val f = obj.getJSONObject("filter")
            fun boolean(key: String): Boolean {
                val value = f.get(key)
                require(value is Boolean)
                return value
            }
            fun optionalBoolean(key: String, default: Boolean): Boolean {
                if (!f.has(key)) return default
                val value = f.get(key)
                require(value is Boolean)
                return value
            }
            fun strings(key: String): List<String> {
                val arr = f.getJSONArray(key)
                require(arr.length() <= FilterRules.MAX_ITEMS)
                return (0 until arr.length()).map { idx ->
                    val value = arr.get(idx)
                    require(value is String)
                    value
                }
            }
            val filter = FilterRules(
                enabled = boolean("enabled"),
                includeKeywords = strings("includeKeywords"),
                requireDigits = boolean("requireDigits"),
                matchAll = boolean("matchAll"),
                excludeKeywords = strings("excludeKeywords"),
                allowedSenders = strings("allowedSenders"),
                requireCodeWord = optionalBoolean("requireCodeWord", false),
            )
            val ruleEnabled = obj.get("enabled")
            require(ruleEnabled is Boolean)
            ForwardRule(
                id = obj.getString("id"),
                name = obj.getString("name"),
                enabled = ruleEnabled,
                destinationId = obj.getString("destinationId"),
                filter = filter,
            )
        }

        return ForwardConfig(destinations, rules).also { require(it.validationError() == null) }
    }
}
