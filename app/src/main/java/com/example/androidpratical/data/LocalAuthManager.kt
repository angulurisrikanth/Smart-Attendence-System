package com.example.androidpratical.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.UUID

class LocalAuthManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("local_auth_prefs", Context.MODE_PRIVATE)

    fun login(email: String): String {
        val userId = prefs.getString("user_id_$email", null) ?: UUID.randomUUID().toString()
        prefs.edit {
            putString("user_id_$email", userId)
            putString("current_user_email", email)
            putString("current_user_id", userId)
            putBoolean("is_logged_in", true)
        }
        return userId
    }

    fun getCurrentUserEmail(): String? {
        return prefs.getString("current_user_email", null)
    }

    fun getCurrentUserId(): String? {
        return prefs.getString("current_user_id", null)
    }

    fun isLoggedIn(): Boolean {
        return prefs.getBoolean("is_logged_in", false) && !getCurrentUserEmail().isNullOrEmpty()
    }

    fun logout() {
        prefs.edit {
            remove("current_user_email")
            remove("current_user_id")
            putBoolean("is_logged_in", false)
        }
    }
}
