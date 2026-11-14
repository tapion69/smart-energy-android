package fr.smartenergy.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * User-initiated upgrade only. No silent installs and no uninstall.
 * The downloaded archive must match the official release SHA-256, our
 * existing application ID and the certificate currently installed.
 * Android's package manager remains the final installer.
 */
internal object SmartEnergyApkInstaller {
    private const val MAX_APK_BYTES = 120L * 1024L * 1024L
    private const val FILENAME = "smart-energy-update.apk"
    const val APK_MIME = "application/vnd.android.package-archive"

    fun downloadAndVerify(
        context: Context,
        release: SmartEnergyUpdateFeed.AndroidRelease,
        progress: (String) -> Unit
    ): File {
        require(SmartEnergyUpdateFeed.officialApkUrl(release.downloadUrl)) {
            "Adresse de mise à jour non officielle"
        }
        val target = File(context.cacheDir, FILENAME)
        val temporary = File(context.cacheDir, "$FILENAME.part")
        temporary.delete()
        target.delete()
        try {
            progress("Téléchargement de l'APK officielle…")
            val connection = URL(release.downloadUrl).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 12000
                connection.readTimeout = 30000
                connection.setRequestProperty("Accept", "application/octet-stream")
                require(connection.responseCode == 200 && connection.url.protocol == "https") {
                    "Téléchargement HTTPS indisponible"
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                connection.inputStream.use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            require(total <= MAX_APK_BYTES) { "APK trop volumineuse" }
                            digest.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                        }
                    }
                }
                require(total >= 16384L) { "APK incomplète" }
                val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                require(hash.equals(release.sha256, true)) {
                    "SHA-256 différent : installation bloquée"
                }
            } finally {
                connection.disconnect()
            }
            progress("Contrôle de l'identité et du certificat Android…")
            require(temporary.renameTo(target)) { "Impossible de préparer l'APK" }
            verifyPackageIdentity(context, target, release.versionCode)
            return target
        } catch (error: Exception) {
            temporary.delete()
            target.delete()
            throw error
        }
    }

    private fun verifyPackageIdentity(context: Context, apk: File, expectedCode: Long) {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        @Suppress("DEPRECATION")
        val current = pm.getPackageInfo(context.packageName, flags)
        @Suppress("DEPRECATION")
        val candidate = pm.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: throw IllegalArgumentException("APK Android illisible")
        require(candidate.packageName == context.packageName) {
            "Paquet différent : mise à jour refusée"
        }
        require(candidate.longVersionCode == expectedCode &&
            candidate.longVersionCode > current.longVersionCode) {
            "Version Android incompatible ou déjà installée"
        }
        val installedSigners = current.signingInfo?.apkContentsSigners
            ?: throw IllegalArgumentException("Certificat installé indisponible")
        val proposedSigners = candidate.signingInfo?.apkContentsSigners
            ?: throw IllegalArgumentException("APK non signée")
        require(installedSigners.size == proposedSigners.size &&
            installedSigners.isNotEmpty() &&
            installedSigners.indices.all { i ->
                installedSigners[i].toByteArray().contentEquals(
                    proposedSigners[i].toByteArray())
            }) { "Signature différente : ne surtout pas désinstaller l'ancienne APK" }
    }

    /**
     * Returns false when the OS first needs the user's explicit permission.
     * Re-tap Install after returning from Android settings. The verified APK
     * stays in app cache, leaving all app data in place.
     */
    fun openAndroidInstaller(context: Context): Boolean {
        require(File(context.cacheDir, FILENAME).isFile) {
            "Aucune APK vérifiée à installer"
        }
        if (Build.VERSION.SDK_INT >= 26 &&
            !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")))
            return false
        }
        val contentUri = Uri.Builder()
            .scheme("content")
            .authority("${context.packageName}.updates")
            .appendPath(FILENAME)
            .build()
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
        return true
    }
}
