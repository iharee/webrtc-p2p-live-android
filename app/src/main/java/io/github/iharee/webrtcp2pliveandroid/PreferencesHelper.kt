package io.github.iharee.webrtcp2pliveandroid

import android.content.Context

object PreferencesHelper {
    private const val PREFS_NAME = "stream_config"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_ROOM_ID = "room_id"
    private const val KEY_TOKEN = "token"

    data class SavedConfig(
        val serverUrl: String?,
        val roomId: String?,
        val token: String?
    )

    fun load(context: Context): SavedConfig {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return SavedConfig(
            serverUrl = p.getString(KEY_SERVER_URL, null),
            roomId = p.getString(KEY_ROOM_ID, null),
            token = p.getString(KEY_TOKEN, null)
        )
    }

    fun save(context: Context, key: String, value: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(key, value).apply()
    }

    fun keyServerUrl() = KEY_SERVER_URL
    fun keyRoomId() = KEY_ROOM_ID
    fun keyToken() = KEY_TOKEN
}
