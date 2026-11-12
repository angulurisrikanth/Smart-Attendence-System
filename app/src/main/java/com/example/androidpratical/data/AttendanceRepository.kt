package com.example.androidpratical.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONArray
import org.json.JSONObject

class AttendanceRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("attendance_prefs", Context.MODE_PRIVATE)

    private val firestore: FirebaseFirestore? by lazy {
        try {
            FirebaseFirestore.getInstance()
        } catch (_: Exception) {
            null
        }
    }

    fun saveAttendance(record: AttendanceRecord) {
        val currentRecords = getAttendanceHistory(record.userId).toMutableList()
        currentRecords.add(0, record) // newest first

        val jsonArray = JSONArray()
        currentRecords.forEach { jsonArray.put(it.toJsonObject()) }
        prefs.edit { putString("records_${record.userId}", jsonArray.toString()) }

        // Sync to Firebase Firestore if available
        firestore?.let { db ->
            try {
                val recordMap = hashMapOf(
                    "id" to record.id,
                    "userId" to record.userId,
                    "userEmail" to record.userEmail,
                    "dateString" to record.dateString,
                    "timestampFormatted" to record.timestampFormatted,
                    "timestampMillis" to record.timestampMillis,
                    "status" to record.status,
                    "distanceMeters" to record.distanceMeters,
                    "address" to record.address,
                    "qrContent" to record.qrContent,
                )
                db.collection("attendance").document(record.id).set(recordMap)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getAttendanceHistory(userId: String): List<AttendanceRecord> {
        val jsonStr = prefs.getString("records_$userId", null) ?: return emptyList()
        val list = mutableListOf<AttendanceRecord>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj: JSONObject = jsonArray.getJSONObject(i)
                list.add(AttendanceRecord.fromJsonObject(obj))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // Return sorted newest first
        return list.sortedByDescending { it.timestampMillis }
    }

    fun hasAttendanceForToday(userId: String, todayDateString: String): Boolean {
        val history = getAttendanceHistory(userId)
        return history.any { it.dateString == todayDateString }
    }
}
