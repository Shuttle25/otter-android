package app.ottermsg.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import app.ottermsg.R
import app.ottermsg.core.data.news.UpdateInstaller
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/**
 * Opens the system installer for a verified update APK (D68). Without the «install unknown apps» permission it opens
 * that setting for Otter with a hint; the user taps «Обновить» again afterwards (the verified file is reused).
 */
class ApkInstaller @Inject constructor(@ApplicationContext private val context: Context) : UpdateInstaller {
    override fun install(file: File) {
        val pm = context.packageManager
        if (!pm.canRequestPackageInstalls()) {
            Toast.makeText(context, R.string.news_install_permission, Toast.LENGTH_LONG).show()
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * Own subclass so this provider does not merge with the photo FileProvider of :feature:conversation (same class name
 * would make the manifest merger treat both as one element).
 */
class ApkFileProvider : FileProvider(R.xml.file_paths)
