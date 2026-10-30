package com.getdieter.core.presentation

import com.getdieter.api.v1.MessagePart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okio.ByteString
import okio.ByteString.Companion.decodeBase64

/**
 * Images a tool returned: Claude Code's Read of an image (`{"type":"image","file":{"base64":…}}`), MCP results
 * with image content blocks (browser screenshots), data URLs. The step shows them under its row; the tool's
 * output then shows each image as a short placeholder instead of its base64.
 */
object ToolImages {
    /** Images shown for one tool call. */
    const val MAX = 4

    private val hint =
        Regex(
            """"type"\s*:\s*"image"|"isImage"\s*:\s*true|data:image/|"(?:base64|data)"\s*:\s*"(?:iVBORw0KGgo|/9j/|R0lGOD|UklGR)"""
        )
    private val magic =
        listOf(
            "iVBORw0KGgo" to "image/png",
            "/9j/" to "image/jpeg",
            "R0lGOD" to "image/gif",
            "UklGR" to "image/webp",
        )
    private val dataUrl = Regex("""^data:(image/[\w.+-]+);base64,""")
    private val typeKeys = listOf("mimeType", "mime_type", "media_type", "mediaType", "type")

    /** Whether [part], a tool call, returned an image, as its output preview shows. */
    fun shown(part: MessagePart): Boolean = part.has_output && hint.containsMatchIn(part.output_preview)

    /** Up to [MAX] images in a tool's [outputJson], as image parts the attachment viewer opens. */
    fun find(outputJson: ByteString): List<MessagePart> =
        found(outputJson, MAX).mapIndexedNotNull { index, (type, data) ->
            data.decodeBase64()
                ?.takeIf { it.size > 0 }
                ?.let {
                    MessagePart(
                        type = "file",
                        media_type = type,
                        filename = "image-${index + 1}.${type.substringAfter('/').substringBefore('+')}",
                        data_ = it,
                    )
                }
        }

    /** [outputJson] as text, every image's base64 replaced by "‹image/png · 1.2 MB›". */
    fun text(outputJson: ByteString): String =
        found(outputJson, Int.MAX_VALUE).fold(outputJson.utf8()) { text, (type, data) ->
            text.replace(data, "‹$type · ${ByteSizes.format(data.length.toLong() * 3 / 4)}›")
        }

    private fun found(outputJson: ByteString, limit: Int): List<Pair<String, String>> {
        val root = runCatching { Json.parseToJsonElement(outputJson.utf8()) }.getOrNull() ?: return emptyList()
        return mutableListOf<Pair<String, String>>().also { collect(root, it, limit) }
    }

    private fun collect(value: JsonElement, out: MutableList<Pair<String, String>>, limit: Int) {
        if (out.size >= limit) return
        when (value) {
            is JsonObject -> {
                val type =
                    typeKeys.firstNotNullOfOrNull { key ->
                        value.string(key)?.takeIf { it.startsWith("image/") }
                    }
                val data = listOf("base64", "data").firstNotNullOfOrNull { key -> value.string(key)?.takeIf { it.length > 64 } }
                if (data != null && (type != null || value.string("type") == "image")) {
                    out += (type ?: sniff(data) ?: "image/png") to data
                    return
                }
                value.values.forEach { collect(it, out, limit) }
            }
            is JsonArray -> value.forEach { collect(it, out, limit) }
            is JsonPrimitive -> {
                val text = value.contentOrNull?.takeIf { value.isString && it.length > 64 } ?: return
                val url = dataUrl.find(text)
                when {
                    url != null -> out += url.groupValues[1] to text.substring(url.range.last + 1)
                    sniff(text) != null -> out += sniff(text)!! to text
                    // MCP results nest their content as a JSON string
                    text.first() in "[{" ->
                        runCatching { Json.parseToJsonElement(text) }.getOrNull()?.let { collect(it, out, limit) }
                }
            }
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun sniff(base64: String): String? = magic.firstOrNull { base64.startsWith(it.first) }?.second
}
