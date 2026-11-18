package com.kidbuddy.smsforwarder

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class Store(context: Context) : SQLiteOpenHelper(context, "outbox.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE queue (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE seen (hash TEXT PRIMARY KEY, created INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit
    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM queue", null).use { it.moveToFirst(); it.getInt(0) }
    fun add(text: String, hash: String? = null): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            db.delete("seen", "created < ?", arrayOf((now - 7 * 86400000L).toString()))
            if (hash != null && db.rawQuery("SELECT 1 FROM seen WHERE hash=?", arrayOf(hash)).use { it.moveToFirst() }) return false
            require(count() < 200) { "待发队列已满（200 条），本条短信未入队，请处理积压" }
            require(text.toByteArray(Charsets.UTF_8).size <= 16000) { "短信过长（超过 16KB），本条未入队" }
            db.insertOrThrow("queue", null, ContentValues().apply { put("text", text); put("created", now) })
            if (hash != null) db.insertOrThrow("seen", null, ContentValues().apply { put("hash", hash); put("created", now) })
            db.execSQL("DELETE FROM seen WHERE hash NOT IN (SELECT hash FROM seen ORDER BY created DESC LIMIT 2000)")
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }
    data class Item(val id: Long, val text: String, val attempts: Int)
    fun next(): Item? = readableDatabase.rawQuery("SELECT id,text,attempts FROM queue WHERE attempts < 8 ORDER BY id LIMIT 1", null).use {
        if (it.moveToFirst()) Item(it.getLong(0), it.getString(1), it.getInt(2)) else null
    }
    fun done(id: Long) { writableDatabase.delete("queue", "id=?", arrayOf(id.toString())) }
    fun failed(id: Long) { writableDatabase.execSQL("UPDATE queue SET attempts=attempts+1 WHERE id=?", arrayOf(id)) }
    fun retry() { writableDatabase.execSQL("UPDATE queue SET attempts=0") }
    fun clear() { writableDatabase.delete("queue", null, null) }
}

object State {
    val gate = Any()
    fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)
    fun status(context: Context, message: String) {
        prefs(context).edit().putString("status", message).putLong("statusTime", System.currentTimeMillis()).apply()
    }
}
