package dev.ide.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.ide.android.daemon.PackageLaunchBridge
import dev.ide.core.ApkInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * On-device [ApkInstaller]: opens Android's package installer for a built APK, the device "Run" for an
 * android-app. The OS shows its own install-confirmation (this app holds
 * `REQUEST_INSTALL_PACKAGES`); if the app isn't yet allowed to install unknown apps, it opens the relevant
 * Settings screen. Streams progress to the build console via [installAndLaunch]'s `log`.
 *
 * Under build-process isolation this runs in the `:build` process (no foreground activity), so the launch is
 * handed to the UI process via [PackageLaunchBridge] — firing the activity from `:build` would trip Android's
 * background-activity-launch block. Only when no UI is reachable (isolation off / unbound) does it launch here.
 */
class ApkInstallerImpl(context: Context) : ApkInstaller {
    private val launchContext = context
    private val context = context.applicationContext

    override suspend fun installAndLaunch(apk: Path, packageName: String, log: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        if (!Files.exists(apk)) { log("APK not found: $apk"); return@withContext false }
        if (PackageLaunchBridge.forwardApkInstall(apk.toString(), packageName)) {
            return@withContext true
        }
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !pm.canRequestPackageInstalls()) {
            log("Allow CodeAssist to install apps (Settings → Install unknown apps), then Run again.")
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return@withContext false
        }

        val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk.toFile())
        val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE)
        installIntent.setData(apkUri)
        installIntent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching {
            if (launchContext is Activity) {
                launchContext.startActivity(installIntent)
            } else {
                context.startActivity(installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }.onFailure {
            log("Couldn't open installer: ${it.message ?: it.javaClass.simpleName}")
            return@withContext false
        }
        log("Installing ${apk.fileName}…")
        true
    }
}
