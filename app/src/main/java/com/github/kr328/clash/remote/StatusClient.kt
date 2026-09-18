package com.github.kr328.clash.remote

import android.content.Context
import android.net.Uri
import com.github.kr328.clash.common.constants.Authorities
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.StatusProvider

class StatusClient(private val context: Context) {
    private val uri: Uri
        get() {
            return Uri.Builder()
                .scheme("content")
                .authority(Authorities.STATUS_PROVIDER)
                .build()
        }

    fun currentProfile(): String? {
        return try {
            val result = context.contentResolver.call(
                uri,
                StatusProvider.METHOD_CURRENT_PROFILE,
                null,
                null
            )

            result?.getString("name")
        } catch (e: Exception) {
            Log.w("Query current profile: $e", e)

            null
        }
    }

    fun isServiceRunning(): Boolean {
        return try {
            context.contentResolver.call(
                uri,
                StatusProvider.METHOD_SERVICE_RUNNING,
                null,
                null
            )?.getBoolean(StatusProvider.KEY_SERVICE_RUNNING) == true
        } catch (e: Exception) {
            Log.w("Query service status: $e", e)

            false
        }
    }
}
