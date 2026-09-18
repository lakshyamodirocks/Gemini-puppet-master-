package `in`.aasmaan.puppetmaster

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

/**
 * Communicates with Termux using com.termux.permission.RUN_COMMAND.
 * Dispatches `ai serve` directly to Termux's RunCommandService.
 */
object TermuxBridge {

    const val TERMUX_PACKAGE_NAME = "com.termux"
    private const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"

    private const val EXTRA_RUN_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_RUN_COMMAND_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_RUN_COMMAND_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val EXTRA_RUN_COMMAND_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"

    const val FDROID_TERMUX_URL = "https://f-droid.org/packages/com.termux/"

    /**
     * Checks if Termux is installed on this device.
     */
    fun isTermuxInstalled(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    TERMUX_PACKAGE_NAME,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(TERMUX_PACKAGE_NAME, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Sends intent to Termux's RunCommandService to execute `ai serve`.
     */
    fun startAiServeInTermux(context: Context): Boolean {
        if (!isTermuxInstalled(context)) return false

        val intent = Intent().apply {
            setClassName(TERMUX_PACKAGE_NAME, TERMUX_RUN_COMMAND_SERVICE)
            action = ACTION_RUN_COMMAND
            putExtra(EXTRA_RUN_COMMAND_PATH, "/data/data/com.termux/files/usr/bin/bash")
            putExtra(EXTRA_RUN_COMMAND_ARGUMENTS, arrayOf("-l", "-c", "ai serve"))
            putExtra(EXTRA_RUN_COMMAND_WORKDIR, "/data/data/com.termux/files/home")
            putExtra(EXTRA_RUN_COMMAND_BACKGROUND, true)
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            true
        } catch (_: Exception) {
            try {
                context.startService(intent)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Opens F-Droid to install Termux.
     */
    fun openFdroidTermux(context: Context) {
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(FDROID_TERMUX_URL)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(browserIntent)
    }
}
