/*
 * Copyright (c) 2023-2024 Tipz Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package tipz.viola.webview

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tipz.viola.settings.SettingsKeys
import tipz.viola.settings.SettingsSharedPreference
import tipz.viola.download.DownloadUtils
import java.io.BufferedReader
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Scanner

open class AdServersHandler(context: Context, settingsPreference: SettingsSharedPreference) {
    private var mContext: Context
    private var mSettingsPreference: SettingsSharedPreference
    var adServers: String? = null

    private val adServersFilePath = "ad_servers_hosts.txt"

    init {
        mContext = context
        mSettingsPreference = settingsPreference
    }

    fun importAdServers() {
        try {
            val inputStream: InputStream = mContext.openFileInput(adServersFilePath)
            val inputStreamReader = InputStreamReader(inputStream)
            val bufferedReader = BufferedReader(inputStreamReader)
            var receiveString: String?
            val stringBuilder = java.lang.StringBuilder()
            while (bufferedReader.readLine().also { receiveString = it } != null) {
                stringBuilder.append("\n").append(receiveString)
            }
            inputStream.close()
            adServers = stringBuilder.toString()
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
            downloadAdServers()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun downloadAdServers() {
        CoroutineScope(Dispatchers.IO).launch {
            var hostsUrl = adServersList[mSettingsPreference.getInt(SettingsKeys.adServerId)]
            if (hostsUrl == null) hostsUrl = mSettingsPreference.getString(SettingsKeys.adServerUrl)
            val scanner = Scanner(String(DownloadUtils.startFileDownload(hostsUrl)))
            val builder = StringBuilder()
            while (scanner.hasNextLine()) {
                val line = scanner.nextLine()
                if (line.startsWith("127.0.0.1") || line.startsWith("0.0.0.0")) builder.append(line)
                    .append(System.lineSeparator())
            }
            adServers = builder.toString()

            try {
                val outputStreamWriter =
                    OutputStreamWriter(
                        mContext.openFileOutput(
                            adServersFilePath,
                            Context.MODE_PRIVATE
                        )
                    )
                outputStreamWriter.write(adServers)
                outputStreamWriter.close()
            } catch (_: IOException) {
            }
        }
    }

    companion object {
        fun getCustomIndex(): Int {
            return adServersList.size - 1
        }

        val adServersList = arrayOf(
            "https://raw.githubusercontent.com/AdAway/adaway.github.io/master/hosts.txt",
            "https://cdn.jsdelivr.net/gh/jerryn70/GoodbyeAds@master/Hosts/GoodbyeAds.txt",
            "http://sbc.io/hosts/hosts",
            "https://hostfiles.frogeye.fr/firstparty-trackers-hosts.txt",
            null
        )
    }
}
