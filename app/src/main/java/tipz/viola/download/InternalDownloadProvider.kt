package tipz.viola.download

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Base64
import android.util.Log
import android.webkit.MimeTypeMap
import tipz.viola.R
import tipz.viola.utils.CommonUtils
import tipz.viola.webview.VJavaScriptInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

class InternalDownloadProvider(override val context: Context) : DownloadProvider {
    override val capabilities = listOf(
        // TODO: Enable for http/https downloads
        // DownloadCapabilities.SCHEMA_HTTP,
        // DownloadCapabilities.SCHEMA_HTTPS,
        DownloadCapabilities.PROTOCOL_DATA,
        DownloadCapabilities.PROTOCOL_BLOB)
    override var statusListener: DownloadProvider.Companion.DownloadStatusListener? = null

    override fun startDownload(downloadObject: DownloadObject) {
        super.startDownload(downloadObject)
        downloadObject.apply {
            when (DownloadCapabilities.fromString(downloadObject.getUriProtocol())) {
                DownloadCapabilities.PROTOCOL_DATA -> {
                    val dataInfo =
                        uriString.substring(uriString.indexOf(":") + 1, uriString.indexOf(","))
                    filename = (System.currentTimeMillis().toString() + "."
                            + MimeTypeMap.getSingleton().getExtensionFromMimeType(
                        dataInfo.substring(
                            0,
                            if (dataInfo.contains(";")) dataInfo.indexOf(";")
                            else dataInfo.length
                        )
                    ))

                    val dataString = getRawDataFromDataUri(uriString)
                    val writableBytes =
                        if (dataInfo.contains(";base64")) base64StringToByteArray(dataString)
                        else dataString.toByteArray()

                    byteArrayToFile(context, writableBytes, filename!!)
                }

                DownloadCapabilities.PROTOCOL_BLOB -> mimeType?.let {
                    if (vWebView != null)
                        vWebView!!.evaluateJavascript(
                            VJavaScriptInterface.getBase64StringFromBlobUrl(uriString, it),
                            null
                        )
                }

                else -> return@apply // TODO: Implement all
            }
        }
    }

    companion object {
        private val LOG_TAG = "IntDownloadProvider"

        fun getRawDataFromDataUri(dataString: String) : String =
            dataString.substring(dataString.indexOf(",") + 1)

        fun base64StringToByteArray(dataString: String) : ByteArray {
            return Base64.decode(dataString, Base64.DEFAULT)
        }

        fun byteArrayToFile(context: Context, barr: ByteArray, filename: String) {
            Log.i(LOG_TAG, "byteArrayToFile(): filename=${filename}")

            val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(path, filename)

            try {
                if (!path.exists()) path.mkdirs()
                if (!file.exists()) file.createNewFile()

                val os: OutputStream = FileOutputStream(file)
                os.write(barr)
                os.close()

                // Tell the media scanner about the new file so that it is immediately available to the user.
                MediaScannerConnection.scanFile(context, arrayOf(file.toString()), null, null)
                CommonUtils.showMessage(context, context.resources.getString(
                    R.string.notification_download_successful, filename))
            } catch (ignored: IOException) {
            }
        }
    }
}