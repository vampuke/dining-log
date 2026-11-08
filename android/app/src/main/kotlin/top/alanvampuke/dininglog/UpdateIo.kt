package top.alanvampuke.dininglog

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import top.alanvampuke.dininglog.core.verifyApkSha256

// Instance-owned I/O collaborators. URL/redirect/version/hash decisions stay in
// InAppUpdates; these adapters only perform the same platform operations.
internal interface UpdateConnection {
    val responseCode: Int
    val contentLengthLong: Long
    val inputStream: InputStream
    fun getHeaderField(name: String): String?
    fun disconnect()
}

internal fun interface UpdateTransport {
    fun open(address: String): UpdateConnection
}

internal object HttpUpdateTransport : UpdateTransport {
    override fun open(address: String): UpdateConnection {
        val request = URL(address).openConnection() as HttpURLConnection
        request.instanceFollowRedirects = false
        request.connectTimeout = 15_000
        request.readTimeout = 15_000
        request.setRequestProperty("Accept", "application/vnd.android.package-archive")
        return object : UpdateConnection {
            override val responseCode: Int get() = request.responseCode
            override val contentLengthLong: Long get() = request.contentLengthLong
            override val inputStream: InputStream get() = request.inputStream
            override fun getHeaderField(name: String): String? = request.getHeaderField(name)
            override fun disconnect() = request.disconnect()
        }
    }
}

internal interface UpdateInstallPlatform {
    suspend fun verify(file: File, expected: String): Boolean
    fun canRequestPackageInstalls(activity: Activity): Boolean
    fun startInstaller(activity: Activity, file: File)
}

internal class AndroidUpdateInstallPlatform(private val context: Context) : UpdateInstallPlatform {
    override suspend fun verify(file: File, expected: String) = verifyApkSha256(file, expected)
    override fun canRequestPackageInstalls(activity: Activity) = activity.packageManager.canRequestPackageInstalls()
    override fun startInstaller(activity: Activity, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(intent)
    }
}
