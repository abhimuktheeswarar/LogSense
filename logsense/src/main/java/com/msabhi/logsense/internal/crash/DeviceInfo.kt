package com.msabhi.logsense.internal.crash

import android.content.Context
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat

internal object DeviceInfo {

    /** Human-readable device/app summary, computed once at init so crash-time work is minimal. */
    fun collect(context: Context): String {
        val versionName: String
        val versionCode: Long
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            versionName = info.versionName ?: "?"
            versionCode = PackageInfoCompat.getLongVersionCode(info)
        } catch (e: Exception) {
            return baseInfo(context, "?", -1)
        }
        return baseInfo(context, versionName, versionCode)
    }

    private fun baseInfo(context: Context, versionName: String, versionCode: Long): String = """
        Device: ${Build.MANUFACTURER} ${Build.MODEL}
        Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
        App: ${context.packageName} $versionName ($versionCode)
    """.trimIndent()
}
