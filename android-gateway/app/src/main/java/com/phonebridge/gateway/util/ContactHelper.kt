package com.phonebridge.gateway.util

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * دریافت نام مخاطب از دفترچه تلفن اندروید بر اساس شماره تلفن
 */
object ContactHelper {
    fun getContactName(context: Context, phoneNumber: String?): String? {
        if (phoneNumber.isNullOrBlank()) return null
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
