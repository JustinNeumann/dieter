package com.getdieter.core.presentation

import com.getdieter.api.v1.MessagePart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8

class ToolImagesTest {
    private val png = "iVBORw0KGgo" + "A".repeat(80)

    @Test
    fun aToolShowsImagesWhenItsOutputPreviewHasOne() {
        assertTrue(ToolImages.shown(MessagePart(has_output = true, output_preview = """{"type":"image","file":{"base64":"iVBO""")))
        assertTrue(ToolImages.shown(MessagePart(has_output = true, output_preview = """[{"type":"text"},{"data":"/9j/4AAQ""")))
        assertFalse(ToolImages.shown(MessagePart(has_output = true, output_preview = """{"stdout":"ok"}""")))
        assertFalse(ToolImages.shown(MessagePart(output_preview = """{"type":"image"""")))
    }

    @Test
    fun imagesAreFoundInClaudeCodeReadsMcpResultsAndDataUrls() {
        val read = """{"type":"image","file":{"base64":"$png","type":"image/png","originalSize":12}}"""
        assertEquals(listOf("image/png"), ToolImages.find(read.encodeUtf8()).map { it.media_type })
        assertEquals(png.decodeBase64(), ToolImages.find(read.encodeUtf8()).single().data_)
        // an MCP screenshot: content blocks nested as a JSON string
        val mcp = """{"content":"[{\"type\":\"text\",\"text\":\"Took it\"},{\"type\":\"image\",\"data\":\"/9j/${"B".repeat(80)}\",\"mimeType\":\"image/jpeg\"}]"}"""
        assertEquals(listOf("image/jpeg" to "image-1.jpeg"), ToolImages.find(mcp.encodeUtf8()).map { it.media_type to it.filename })
        val url = """{"shot":"data:image/webp;base64,UklGR${"C".repeat(79)}","bare":"$png"}"""
        assertEquals(listOf("image/webp", "image/png"), ToolImages.find(url.encodeUtf8()).map { it.media_type })
        val many = (1..6).joinToString(",", "[", "]") { "\"$png\"" }
        assertEquals(ToolImages.MAX, ToolImages.find(many.encodeUtf8()).size)
        assertEquals(emptyList(), ToolImages.find("""{"stdout":"${"x".repeat(100)}"}""".encodeUtf8()))
        assertEquals(emptyList(), ToolImages.find("not json".encodeUtf8()))
    }

    @Test
    fun theOutputTextShowsAPlaceholderForEachImage() {
        val read = """{"type":"image","file":{"base64":"$png","type":"image/png"}}"""
        assertEquals("""{"type":"image","file":{"base64":"‹image/png · 68 B›","type":"image/png"}}""", ToolImages.text(read.encodeUtf8()))
        assertEquals("""{"stdout":"ok"}""", ToolImages.text("""{"stdout":"ok"}""".encodeUtf8()))
    }
}
