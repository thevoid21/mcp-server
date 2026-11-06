package net.portswigger.mcp.schema

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.portswigger.mcp.tools.GetProxyHttpHistory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JsonSchemaTest {

    private interface SharedParams {
        @ParamDoc("Shared description.")
        val shared: String
    }

    private data class SampleTool(
        @ParamDoc("Own description.")
        val own: Int,
        override val shared: String,
        val undocumented: Boolean? = null,
    ) : SharedParams

    @Test
    fun `annotated parameters carry their description in the schema`() {
        val schema = SampleTool::class.asInputSchema()
        val own = schema.properties!!.jsonObject.getValue("own").jsonObject

        assertEquals("integer", own.getValue("type").jsonPrimitive.content)
        assertEquals("Own description.", own.getValue("description").jsonPrimitive.content)
        assertTrue(schema.required!!.contains("own"))
    }

    @Test
    fun `descriptions declared on a supertype are inherited`() {
        val schema = SampleTool::class.asInputSchema()
        val shared = schema.properties!!.jsonObject.getValue("shared").jsonObject

        assertEquals("Shared description.", shared.getValue("description").jsonPrimitive.content)
    }

    @Test
    fun `undocumented optional parameters have no description and are not required`() {
        val schema = SampleTool::class.asInputSchema()
        val undocumented = schema.properties!!.jsonObject.getValue("undocumented").jsonObject

        assertFalse(undocumented.containsKey("description"))
        assertFalse(schema.required!!.contains("undocumented"))
    }

    @Test
    fun `pagination parameters are documented once and shared by every paginated tool`() {
        val properties = GetProxyHttpHistory::class.asInputSchema().properties!!.jsonObject

        val count = properties.getValue("count").jsonObject.getValue("description").jsonPrimitive.content
        val offset = properties.getValue("offset").jsonObject.getValue("description").jsonPrimitive.content

        assertTrue(count.contains("1 to 100"), count)
        assertTrue(offset.contains("nextOffset"), offset)
    }
}
