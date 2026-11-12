package com.example.androidpratical.utils

import android.content.Context
import android.util.TypedValue
import androidx.annotation.AttrRes

object ThemeUtils {

    fun getThemeColor(context: Context, @AttrRes attrRes: Int, defaultColor: Int): Int {
        val typedValue = TypedValue()
        return if (context.theme.resolveAttribute(attrRes, typedValue, true)) {
            typedValue.data
        } else {
            defaultColor
        }
    }
}
