package net.portswigger.mcp.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaginateTest {

    private fun envelope(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun itemIds(envelope: JsonObject): List<Int> =
        envelope.getValue("items").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.int }

    private fun encodeId(id: Int) = """{"id":$id}"""

    @Test
    fun `encodes only the requested page of a large history`() {
        var encodeCalls = 0
        val matches = List(40_000) { it }

        val result = envelope(paginate(matches, offset = 0, count = 20) {
            encodeCalls++
            encodeId(it)
        })

        assertEquals(20, encodeCalls, "encode must run only for items on the page")
        assertEquals(40_000, result.getValue("total").jsonPrimitive.int)
        assertEquals(20, result.getValue("returned").jsonPrimitive.int)
        assertEquals(20, result.getValue("nextOffset").jsonPrimitive.int)
    }

    @Test
    fun `pages through every item exactly once`() {
        val matches = List(45) { it }
        val seen = mutableListOf<Int>()
        var offset = 0

        while (true) {
            val page = envelope(paginate(matches, offset, count = 10) { encodeId(it) })
            seen += itemIds(page)
            val next = page["nextOffset"] ?: break
            offset = next.jsonPrimitive.int
        }

        assertEquals(matches, seen)
    }

    @Test
    fun `last page omits nextOffset`() {
        val result = envelope(paginate(List(5) { it }, offset = 3, count = 10) { encodeId(it) })

        assertEquals(listOf(3, 4), itemIds(result))
        assertFalse(result.containsKey("nextOffset"))
    }

    @Test
    fun `count above the cap is reduced to MAX_PAGE_SIZE`() {
        var encodeCalls = 0
        val result = envelope(paginate(List(1_000) { it }, offset = 0, count = 500) {
            encodeCalls++
            encodeId(it)
        })

        assertEquals(MAX_PAGE_SIZE, encodeCalls)
        assertEquals(MAX_PAGE_SIZE, result.getValue("returned").jsonPrimitive.int)
        assertEquals(MAX_PAGE_SIZE, result.getValue("nextOffset").jsonPrimitive.int)
    }

    @Test
    fun `offset past the end returns an empty page with the total`() {
        var encodeCalls = 0
        val result = envelope(paginate(List(3) { it }, offset = 10, count = 5) {
            encodeCalls++
            encodeId(it)
        })

        assertEquals(0, encodeCalls)
        assertEquals(3, result.getValue("total").jsonPrimitive.int)
        assertEquals(0, result.getValue("returned").jsonPrimitive.int)
        assertTrue(result.getValue("items").jsonArray.isEmpty())
        assertFalse(result.containsKey("nextOffset"))
    }

    @Test
    fun `empty input returns total zero`() {
        val result = envelope(paginate(emptyList<Int>(), offset = 0, count = 10) { encodeId(it) })

        assertEquals(0, result.getValue("total").jsonPrimitive.int)
        assertTrue(result.getValue("items").jsonArray.isEmpty())
    }

    @Test
    fun `non-JSON encoder output is kept as a string item`() {
        val result = envelope(paginate(listOf("plain text"), offset = 0, count = 1) { it })

        assertEquals("plain text", result.getValue("items").jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `count below one is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            paginate(List(3) { it }, offset = 0, count = 0) { encodeId(it) }
        }
    }

    @Test
    fun `negative offset is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            paginate(List(3) { it }, offset = -1, count = 1) { encodeId(it) }
        }
    }
}
