package com.example.flare_capstone.data.database

import android.content.Context
import android.content.SharedPreferences
import com.example.flare_capstone.data.model.User
import com.google.gson.Gson

object UserSession {
    private const val PREF_NAME = "user_session"
    private const val KEY_USER_INFO = "user_info"
    private const val KEY_LAST_LOGIN = "last_login_time"
    private const val KEY_USER_STATUS = "user_status"
    private const val KEY_HEALTH_CONDITIONS = "health_conditions"
    private const val KEY_HEALTH_FETCH_TIME = "health_fetch_time"
    private const val OFFLINE_ACCESS_WINDOW_MS = 24 * 60 * 60 * 1000L // 1 day

    private lateinit var prefs: SharedPreferences
    private val gson = Gson()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun setCurrentUser(user: User) {
        val json = gson.toJson(user)
        prefs.edit()
            .putString(KEY_USER_INFO, json)
            .putLong(KEY_LAST_LOGIN, System.currentTimeMillis())
            .apply()
    }

    fun getCurrentUser(): User? {
        val json = prefs.getString(KEY_USER_INFO, null)
        return if (json != null) gson.fromJson(json, User::class.java) else null
    }

    fun isLoggedIn(): Boolean = getCurrentUser() != null

    fun clearSession() {
        prefs.edit().clear().apply()
    }

    fun saveLastLoginTime() {
        prefs.edit().putLong(KEY_LAST_LOGIN, System.currentTimeMillis()).apply()
    }

    fun getLastLoginTime(): Long = prefs.getLong(KEY_LAST_LOGIN, 0)

    fun isOfflineAccessStillValid(): Boolean {
        val now = System.currentTimeMillis()
        return now - getLastLoginTime() <= OFFLINE_ACCESS_WINDOW_MS
    }

    fun saveUserStatus(isActive: Boolean) {
        prefs.edit().putBoolean(KEY_USER_STATUS, isActive).apply()
    }

    fun getUserStatus(): Boolean = prefs.getBoolean(KEY_USER_STATUS, false)

    fun saveHealthConditions(conditions: List<String>) {
        prefs.edit()
            .putString(KEY_HEALTH_CONDITIONS, gson.toJson(conditions))
            .putLong(KEY_HEALTH_FETCH_TIME, System.currentTimeMillis())
            .apply()
    }

    fun getCachedHealthConditions(): List<String>? {
        val json = prefs.getString(KEY_HEALTH_CONDITIONS, null) ?: return null
        val time = prefs.getLong(KEY_HEALTH_FETCH_TIME, 0)
        val now = System.currentTimeMillis()
        val validFor = 24 * 60 * 60 * 1000L
        return if (now - time <= validFor) {
            gson.fromJson(json, Array<String>::class.java).toList()
        } else null
    }
}
