package fr.smartenergy.android

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/** Only exposes the verified update file, by an OS-granted one-off read URI. */
class SmartEnergyApkProvider : ContentProvider() {
    private fun verifiedFile(uri: Uri): File {
        val app = context ?: throw FileNotFoundException()
        if (uri.path != "/smart-energy-update.apk" ||
            uri.authority != "${app.packageName}.updates") {
            throw FileNotFoundException("Unknown APK")
        }
        val file = File(app.cacheDir, "smart-energy-update.apk")
        if (!file.isFile || file.canonicalFile.parentFile != app.cacheDir.canonicalFile) {
            throw FileNotFoundException("Update unavailable")
        }
        return file
    }

    override fun onCreate(): Boolean = true
    override fun getType(uri: Uri): String =
        if (verifiedFile(uri).isFile) SmartEnergyApkInstaller.APK_MIME
        else throw FileNotFoundException()

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        return ParcelFileDescriptor.open(verifiedFile(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val file = verifiedFile(uri)
        val fields = projection ?: arrayOf("_display_name", "_size")
        val result = MatrixCursor(fields)
        result.addRow(fields.map {
            when (it) {
                "_display_name" -> file.name
                "_size" -> file.length()
                else -> null
            }
        })
        return result
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read only")
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException("Read only")
}
