package com.kidbuddy.smsforwarder

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class Store(context: Context) : SQLiteOpenHelper(context, "outbox.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE queue (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "text TEXT NOT NULL, " +
                "attempts INTEGER NOT NULL DEFAULT 0, " +
                "created INTEGER NOT NULL, " +
                "destination_id TEXT NOT NULL DEFAULT '', " +
                "rule_name TEXT NOT NULL DEFAULT '')"
        )
        db.execSQL("CREATE TABLE seen (hash TEXT PRIMARY KEY, created INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 2) {
            db.execSQL("ALTER TABLE queue ADD COLUMN destination_id TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE queue ADD COLUMN rule_name TEXT NOT NULL DEFAULT ''")
        }
    }

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM queue", null).use { it.moveToFirst(); it.getInt(0) }

    fun add(text: String, hash: String? = null, destinationId: String = "", ruleName: String = ""): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            db.delete("seen", "created < ?", arrayOf((now - 7 * 86400000L).toString()))
            if (hash != null && db.rawQuery("SELECT 1 FROM seen WHERE hash=?", arrayOf(hash)).use { it.moveToFirst() }) return false
            require(count() < OutboxStateMachine.MAX_QUEUE_SIZE) {
                "待发队列已满（${OutboxStateMachine.MAX_QUEUE_SIZE} 条），本条短信未入队，请处理积压"
            }
            require(text.toByteArray(Charsets.UTF_8).size <= 16000) { "短信过长（超过 16KB），本条未入队" }
            db.insertOrThrow("queue", null, ContentValues().apply {
                put("text", text)
                put("created", now)
                put("destination_id", destinationId)
                put("rule_name", ruleName)
            })
            if (hash != null) db.insertOrThrow("seen", null, ContentValues().apply { put("hash", hash); put("created", now) })
            db.execSQL("DELETE FROM seen WHERE hash NOT IN (SELECT hash FROM seen ORDER BY created DESC LIMIT 2000)")
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    data class Item(
        val id: Long,
        val text: String,
        val attempts: Int,
        val destinationId: String = "",
        val ruleName: String = "",
    ) {
        val status: OutboxStateMachine.QueueItemStatus
            get() = OutboxStateMachine.itemStatus(attempts)
    }

    fun next(excludedDestinationIds: Set<String> = emptySet()): Item? {
        val where = buildString {
            append("attempts < ${OutboxStateMachine.MAX_ATTEMPTS}")
            if (excludedDestinationIds.isNotEmpty()) {
                val placeholders = excludedDestinationIds.joinToString(",") { "?" }
                append(" AND destination_id NOT IN ($placeholders)")
            }
        }
        val args = if (excludedDestinationIds.isEmpty()) null else excludedDestinationIds.toTypedArray()
        return readableDatabase.rawQuery(
            "SELECT id,text,attempts,destination_id,rule_name FROM queue WHERE $where ORDER BY attempts ASC, id ASC LIMIT 1",
            args,
        ).use {
            if (it.moveToFirst()) Item(it.getLong(0), it.getString(1), it.getInt(2), it.getString(3), it.getString(4)) else null
        }
    }

    fun done(id: Long) { writableDatabase.delete("queue", "id=?", arrayOf(id.toString())) }
    fun failed(id: Long) {
        writableDatabase.execSQL(
            "UPDATE queue SET attempts=MIN(attempts+1, ?) WHERE id=?",
            arrayOf(OutboxStateMachine.MAX_ATTEMPTS, id),
        )
    }
    fun retry() { writableDatabase.execSQL("UPDATE queue SET attempts=0") }
    fun clear() { writableDatabase.delete("queue", null, null) }
}

object State {
    val gate = Any()
    fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)
    fun deviceLabel(context: Context): String = MessageFormatter.sanitizeDeviceLabel(prefs(context).getString("deviceLabel", ""))
    fun setDeviceLabel(context: Context, value: String): Boolean {
        val clean = MessageFormatter.sanitizeDeviceLabel(value)
        return prefs(context).edit().putString("deviceLabel", clean).commit()
    }
    fun status(context: Context, message: String) {
        prefs(context).edit().putString("status", message).putLong("statusTime", System.currentTimeMillis()).apply()
    }
}
