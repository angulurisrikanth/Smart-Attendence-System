package com.example.androidpratical.data

import org.json.JSONObject

data class AttendanceRecord(
    val id: String,
    val userId: String,
    val userEmail: String,
    val dateString: String,
    val timestampFormatted: String,
    val timestampMillis: Long,
    val status: String,
    val distanceMeters: Double,
    val address: String,
    val qrContent: String
) {
    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("userId", userId)
            put("userEmail", userEmail)
            put("dateString", dateString)
            put("timestampFormatted", timestampFormatted)
            put("timestampMillis", timestampMillis)
            put("status", status)
            put("distanceMeters", distanceMeters)
            put("address", address)
            put("qrContent", qrContent)
        }
    }

    companion object {
        fun fromJsonObject(json: JSONObject): AttendanceRecord {
            return AttendanceRecord(
                id = json.optString("id", ""),
                userId = json.optString("userId", ""),
                userEmail = json.optString("userEmail", ""),
                dateString = json.optString("dateString", ""),
                timestampFormatted = json.optString("timestampFormatted", ""),
                timestampMillis = json.optLong("timestampMillis", 0L),
                status = json.optString("status", ""),
                distanceMeters = json.optDouble("distanceMeters", 0.0),
                address = json.optString("address", ""),
                qrContent = json.optString("qrContent", "")
            )
        }
    }
}
