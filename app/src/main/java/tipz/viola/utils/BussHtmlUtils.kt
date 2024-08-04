package tipz.viola.utils

import android.util.Log
import tipz.viola.download.MiniDownloadHelper
import java.util.Scanner

object BussHtmlUtils {
    const val LOG_TAG = "BussHtmlUtils"

    // These are some defaults found in "napture/src/b9/css.rs" of the same variable name
    const val DEFAULT_CSS = "body { gap: 10; background-color: transparent; direction: column; align-items: fill; } h1 { font-size: 24pt; } h2 { font-size: 22pt; } h3 { font-size: 20pt; } h4 { font-size: 18pt; } h5 { font-size: 16pt; } h6 { font-size: 14pt; } a { border: none; color: #67B7D1; text-decoration: underline; } input { padding: 5px; border-color: #616161; border-width: 1px; border-style: solid; border-radius: 12px; } textarea { padding: 5px; border-color: #616161; border-width: 1px; border-style: solid; border-radius: 12px; width: 400px; height: 100px; }"

    private fun getLinkRegex(formats: String): Regex =
        "<link.*href=\".*.(${formats})\".*>".toRegex()

    private fun appendProperty(line: String, associate: String?,
                               property: String, isBefore: Boolean): String {
        associate!! // TODO: Handle null variants
        val startIndex = line.indexOf("${associate}=\"")
        val firstBracketIndex = startIndex + associate.length + 2
        val lastBracketIndex = line.indexOf('"', firstBracketIndex + 1)

        val replaceIndex = if (isBefore) startIndex else lastBracketIndex
        val replacementString = if (isBefore) "${property.trim()} " else " ${property.trim()}"
        return line.replaceRange(replaceIndex, replaceIndex,replacementString)
    }

    private fun getProperty(line: String, property: String): String {
        val startIndex = line.indexOf("${property}=\"")
        if (startIndex == -1) return ""

        val firstBracketIndex = startIndex + property.length + 2
        val lastBracketIndex = line.indexOf('"', firstBracketIndex + 1)

        return line.substring(firstBracketIndex, lastBracketIndex)
    }

    suspend fun parseHtml(realUrl: String, data: ByteArray?): String {
        if (data == null) return ""
        val inData = String(data).lineSequence()
            .map { it.trim() }
            .joinToString("\n")

        val scanner = Scanner(inData)
        val builder = StringBuilder()
        var inHtmlTag = false

        // For <head>
        var defaultCssAdded = false

        // For any
        var inScriptTag = false

        // Updatable
        var currentHtmlTag = ""

        while (scanner.hasNextLine()) {
            var line = scanner.nextLine()
            if (line.isBlank()) continue

            val endBracketStartIndex = line.length - 2
            if (line.endsWith("/>")) endBracketStartIndex - 1

            // TODO: Figure out the format of this (b/1)
            var cleanedLine = line
            if (cleanedLine.contains('<'))
                cleanedLine = line.replaceFirst("<", "")
            if (cleanedLine.contains('>'))
                cleanedLine = cleanedLine.replaceRange(endBracketStartIndex,
                    line.length - 1, CommonUtils.EMPTY_STRING)
            Log.d(LOG_TAG, "cleanedLine=${cleanedLine}")

            // Detect start of tag, assume an element is always multi-lined
            if (line.startsWith('<')) {
                inHtmlTag = true

                currentHtmlTag = if (cleanedLine.contains(' '))
                    cleanedLine.substringBefore(' ')
                else cleanedLine.substringBefore('>') // TODO: b/1
                Log.d(LOG_TAG, "currentHtmlTag=${currentHtmlTag}")
            }

            // Process some outer tags
            if (currentHtmlTag == "head") {
                builder.append(line).append(System.lineSeparator())
                if (!defaultCssAdded) {
                    builder.append("<style>${DEFAULT_CSS}</style>").append(System.lineSeparator())
                    defaultCssAdded = true
                }
                continue
            } else if (currentHtmlTag == "/head") {
                builder.append(line).append(System.lineSeparator())
                continue
            }

            if (currentHtmlTag == "link") {
                // Process CSS
                if (line.matches(getLinkRegex("css"))) {
                    var cssUrl = getProperty(line, "href")
                    if (!cssUrl.isBlank()) {
                        if (!cssUrl.matches(UrlUtils.httpUrlRegex.toRegex()))
                            cssUrl = realUrl.substringBeforeLast('/') + "/" + cssUrl

                        val cssData = MiniDownloadHelper.startDownload(cssUrl)!!
                        builder.append("<style>${String(cssData)}</style>").append(System.lineSeparator())
                        continue
                    }
                }

                // For "rel"
                val rel: String? =
                    if (line.matches(getLinkRegex("css"))) "stylesheet" // TODO: Remove?
                    else if (line.matches(getLinkRegex("ico|gif|png|svg|jpg|jpeg"))) "icon"
                    else null
                if (rel != null) line = appendProperty(line, "href",
                    "rel=\"${rel}\"", true)

                builder.append(line).append(System.lineSeparator())
                continue
            }

            // FIXME: Re-enable for Lua scripts
            if (currentHtmlTag == "script") {
                inScriptTag = true
                continue
            } else if (currentHtmlTag == "/script") { // Embedded scripts end tag
                inScriptTag = false
                continue
            } else if (inScriptTag && cleanedLine == line) { // Embedded scripts content
                continue
            }

            // Detect end of tag
            if (line.contains('>')) {
                inHtmlTag = false
            }
            builder.append(line).append(System.lineSeparator())
        }

        return builder.toString()
    }
}