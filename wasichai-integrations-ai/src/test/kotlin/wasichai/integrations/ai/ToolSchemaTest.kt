package wasichai.integrations.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.integrations.sdk.FieldDescriptor
import wasichai.integrations.sdk.FieldType
import wasichai.integrations.sdk.ResourceSchema

class ToolSchemaTest {
    private val json = JsonMapper.builder().build()

    private fun node(text: String): JsonNode = json.readTree(text)

    private fun checked(text: String): JsonNode {
        val (schema, violations) = ToolSchema.check(node(text), json)
        assertEquals(emptyList<Any>(), violations)
        return schema!!
    }

    private fun refused(text: String): Map<String, String> {
        val (schema, violations) = ToolSchema.check(node(text), json)
        assertNull(schema)
        return violations.associate { it.field to it.message }
    }

    private val message =
        """
        {"${'$'}schema": "https://json-schema.org/draft/2020-12/schema", "type": "object", "description": "a message",
         "properties": {
           "channel": {"type": "string", "minLength": 1, "maxLength": 80},
           "text": {"type": "string", "maxLength": 10},
           "priority": {"type": "string", "enum": ["low", "high"]},
           "retries": {"type": "integer", "minimum": 0, "maximum": 3},
           "ratio": {"type": "number", "minimum": 0.5},
           "urgent": {"type": "boolean"},
           "on": {"type": "string", "format": "date"},
           "at": {"type": "string", "format": "date-time"},
           "tags": {"type": "array", "items": {"type": "string", "maxLength": 5}, "minItems": 1, "maxItems": 2},
           "meta": {"type": "object", "properties": {"source": {"type": "string"}}, "required": ["source"]}
         },
         "required": ["channel", "text"]}
        """.trimIndent()

    @Test
    fun `a schema in the subset is accepted, objects closed and the dialect dropped`() {
        val schema = checked(message)
        assertFalse(schema.get("additionalProperties").asBoolean())
        assertFalse(
            schema
                .get("properties")
                .get("meta")
                .get("additionalProperties")
                .asBoolean()
        )
        assertNull(schema.get("\$schema"))
    }

    @Test
    fun `anything outside the subset is refused with its path`() {
        val v =
            refused(
                """
                {"type": "object", "properties": {
                  "a": {"type": "string", "pattern": "^x"},
                  "b": {"type": "string", "format": "email"},
                  "c": {"type": "null"},
                  "d": {"type": "array"},
                  "e": {"type": "integer", "enum": [1, 1.5]},
                  "f": {"type": "string", "minLength": 5, "maxLength": 2},
                  "g": {"oneOf": []},
                  "h": {"type": "object", "additionalProperties": true}
                }, "required": ["a", "zzz"]}
                """.trimIndent()
            )
        assertEquals(
            setOf(
                "inputSchema.properties.a.pattern",
                "inputSchema.properties.b.format",
                "inputSchema.properties.c.type",
                "inputSchema.properties.d.items",
                "inputSchema.properties.e.enum[1]",
                "inputSchema.properties.f.minLength",
                "inputSchema.properties.g.type",
                "inputSchema.properties.h.additionalProperties",
                "inputSchema.required[1]"
            ),
            v.keys
        )
    }

    @Test
    fun `the root is an object, and no property asks for a credential`() {
        assertTrue("inputSchema.type" in refused("""{"type": "string"}"""))
        assertTrue("inputSchema" in ToolSchema.check(null, json).second.map { it.field })
        val v = refused("""{"type": "object", "properties": {"apiKey": {"type": "string"}, "user_password": {"type": "string"}, "ok": {"type": "string"}}}""")
        assertEquals(setOf("inputSchema.properties.apiKey", "inputSchema.properties.user_password"), v.keys)
    }

    @Test
    fun `depth is bounded`() {
        var s = """{"type": "string"}"""
        repeat(9) { s = """{"type": "object", "properties": {"x": $s}}""" }
        assertTrue(refused(s).values.any { it.startsWith("nested deeper") })
    }

    @Test
    fun `a valid input has no violations`() {
        val schema = checked(message)
        val input =
            node(
                """
                {"channel": "general", "text": "hi", "priority": "high", "retries": 3.0, "ratio": 0.5, "urgent": true,
                 "on": "2026-11-05", "at": "2026-11-05T10:00:00+01:00", "tags": ["a"], "meta": {"source": "agent"}}
                """.trimIndent()
            )
        assertEquals(emptyList<Any>(), ToolSchema.validate(schema, input))
    }

    @Test
    fun `every violation of an input has its field path`() {
        val schema = checked(message)
        val input =
            node(
                """
                {"channel": "", "text": "far too long here", "priority": "mid", "retries": 4, "ratio": 0.1, "urgent": "yes",
                 "on": "05/11/2026", "at": "2026-11-05T10:00:00", "tags": ["abcdef", "b", "c"], "meta": {"source": 1, "x": 2}, "extra": 1}
                """.trimIndent()
            )
        val v = ToolSchema.validate(schema, input).associate { it.field to it.message }
        assertEquals(
            setOf(
                "input.channel",
                "input.text",
                "input.priority",
                "input.retries",
                "input.ratio",
                "input.urgent",
                "input.on",
                "input.at",
                "input.tags",
                "input.tags[0]",
                "input.meta.source",
                "input.meta.x",
                "input.extra"
            ),
            v.keys
        )
        assertEquals("not allowed", v["input.extra"])
        assertEquals("at most 3", v["input.retries"])
    }

    @Test
    fun `required properties, integers and the wrong root type`() {
        val schema = checked(message)
        val missing = ToolSchema.validate(schema, node("""{"retries": 1.5}""")).map { it.field }.toSet()
        assertEquals(setOf("input.channel", "input.text", "input.retries"), missing)
        assertEquals(listOf("input"), ToolSchema.validate(schema, node("[1]")).map { it.field })
        assertEquals(listOf("input"), ToolSchema.validate(schema, null).map { it.field })
    }

    @Test
    fun `a record tool's schema is derived from the resource, without read-only, reference or credential fields`() {
        val resource =
            ResourceSchema(
                "contacts",
                "Contacts",
                listOf(
                    FieldDescriptor("name", "Name", FieldType.TEXT, required = true),
                    FieldDescriptor("tier", "Tier", FieldType.CHOICE, options = listOf("gold", "silver")),
                    FieldDescriptor("amount", "Amount", FieldType.NUMBER),
                    FieldDescriptor("since", "Since", FieldType.DATE),
                    FieldDescriptor("computed", "Computed", FieldType.TEXT, readOnly = true),
                    FieldDescriptor("owner", "Owner", FieldType.REFERENCE),
                    FieldDescriptor("apiToken", "Token", FieldType.TEXT),
                    FieldDescriptor("extId", "External id", FieldType.TEXT, externalId = true)
                )
            )
        val (create, types) = ToolSchema.forResource(ToolOperation.CREATE, resource, null, json)
        assertEquals(setOf("name", "tier", "amount", "since", "extId"), types.keys)
        val fields = create.get("properties").get("fields")
        assertEquals("name", fields.get("required").get(0).asString())
        assertEquals(
            2,
            fields
                .get("properties")
                .get("tier")
                .get("enum")
                .size()
        )
        // what is derived passes the subset check, and an input is validated against it
        assertNotNull(ToolSchema.check(create, json).first)
        val v = ToolSchema.validate(create, node("""{"fields": {"tier": "bronze", "computed": "x"}}""")).map { it.field }.toSet()
        assertEquals(setOf("input.fields.name", "input.fields.tier", "input.fields.computed"), v)

        val (upsert, upsertTypes) = ToolSchema.forResource(ToolOperation.UPSERT, resource, "extId", json)
        assertFalse("extId" in upsertTypes)
        assertEquals(listOf("externalId", "fields"), (0 until upsert.get("required").size()).map { upsert.get("required").get(it).asString() })
        assertFalse(upsert.get("properties").get("fields").has("required"))
        val (read, _) = ToolSchema.forResource(ToolOperation.READ, null, null, json)
        assertEquals(listOf("input.id"), ToolSchema.validate(read, node("{}")).map { it.field })
        val (list, _) = ToolSchema.forResource(ToolOperation.LIST, null, null, json)
        assertEquals(listOf("input.pageSize"), ToolSchema.validate(list, node("""{"pageSize": 500}""")).map { it.field })
    }
}
