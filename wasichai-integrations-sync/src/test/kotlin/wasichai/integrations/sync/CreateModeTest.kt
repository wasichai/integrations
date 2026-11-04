package wasichai.integrations.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wasichai.integrations.sdk.FieldDescriptor
import wasichai.integrations.sdk.FieldType
import wasichai.integrations.sdk.FieldValue
import wasichai.integrations.sdk.ProviderRecord

// issue #32: which way a create can be found again, and which externalIdField a side may name
class CreateModeTest {
    private val plain = MemConnector("mem-x")
    private val upserter = MemUpserter("mem-y")

    @Test
    fun `the mode follows the field and what the target connector implements`() {
        assertEquals(CreateMode.NONE, CreateMode.of(null, plain))
        assertEquals(CreateMode.NONE, CreateMode.of(null, upserter))
        assertEquals(CreateMode.MARKER, CreateMode.of("marker", plain))
        assertEquals(CreateMode.UPSERT, CreateMode.of("extId", upserter))
    }

    @Test
    fun `a token is read only from a non blank text value`() {
        fun record(value: FieldValue?) = ProviderRecord("r1", null, null, if (value == null) emptyMap() else mapOf("marker" to value))
        assertEquals("t-1", CreateMode.token(record(FieldValue.Text("t-1")), "marker"))
        assertNull(CreateMode.token(record(FieldValue.Text(" ")), "marker"))
        assertNull(CreateMode.token(record(FieldValue.Null), "marker"))
        assertNull(CreateMode.token(record(FieldValue.Choice("t-1")), "marker"))
        assertNull(CreateMode.token(record(null), "marker"))
    }

    @Test
    fun `an upserting connector needs a writable external id field`() {
        val ext = FieldDescriptor("extId", "Ext", FieldType.TEXT, externalId = true)
        assertNull(DefinitionValidator.externalIdProblem("extId", ext, upserts = true, label = "Contacts"))
        assertEquals("no field 'nope' in Contacts", DefinitionValidator.externalIdProblem("nope", null, upserts = true, label = "Contacts"))
        assertEquals(
            "'extId' is read-only",
            DefinitionValidator.externalIdProblem("extId", ext.copy(readOnly = true), upserts = true, label = "Contacts")
        )
        assertEquals(
            "'marker' is not an external id field of Contacts",
            DefinitionValidator.externalIdProblem("marker", FieldDescriptor("marker", "M", FieldType.TEXT), upserts = true, label = "Contacts")
        )
    }

    @Test
    fun `any other connector needs a writable text field for the marker`() {
        assertNull(DefinitionValidator.externalIdProblem("marker", FieldDescriptor("marker", "M", FieldType.TEXT), upserts = false, label = "Contacts"))
        // an external id flag does not matter without upsert: the token is written and looked up like any text
        assertNull(
            DefinitionValidator.externalIdProblem("extId", FieldDescriptor("extId", "E", FieldType.TEXT, externalId = true), upserts = false, label = "C")
        )
        val number = DefinitionValidator.externalIdProblem("amount", FieldDescriptor("amount", "A", FieldType.NUMBER), upserts = false, label = "C")
        assertTrue(number!!.contains("TEXT"), number)
        assertEquals(
            "'computed' is read-only",
            DefinitionValidator.externalIdProblem("computed", FieldDescriptor("computed", "C", FieldType.TEXT, readOnly = true), upserts = false, label = "C")
        )
    }
}
