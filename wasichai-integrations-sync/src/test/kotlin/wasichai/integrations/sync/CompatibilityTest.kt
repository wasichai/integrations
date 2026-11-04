package wasichai.integrations.sync

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wasichai.integrations.sdk.FieldDescriptor
import wasichai.integrations.sdk.FieldType
import java.time.ZoneOffset

class CompatibilityTest {
    private fun f(type: FieldType) = FieldDescriptor("f", "f", type)

    @Test
    fun `copy needs the same kind of value`() {
        assertTrue(DefinitionValidator.compatible(Transform.Copy, f(FieldType.TEXT), f(FieldType.CHOICE)))
        assertTrue(DefinitionValidator.compatible(Transform.Copy, f(FieldType.NUMBER), f(FieldType.NUMBER)))
        assertFalse(DefinitionValidator.compatible(Transform.Copy, f(FieldType.TEXT), f(FieldType.NUMBER)))
        assertFalse(DefinitionValidator.compatible(Transform.Copy, f(FieldType.DATE), f(FieldType.BOOLEAN)))
    }

    @Test
    fun `each transform states what it accepts`() {
        assertTrue(DefinitionValidator.compatible(Transform.DateTime(ZoneOffset.UTC, false, true), f(FieldType.DATETIME), f(FieldType.DATE)))
        assertFalse(DefinitionValidator.compatible(Transform.DateTime(ZoneOffset.UTC, false, true), f(FieldType.TEXT), f(FieldType.DATE)))
        assertTrue(DefinitionValidator.compatible(Transform.Number(2), f(FieldType.TEXT), f(FieldType.NUMBER)))
        assertFalse(DefinitionValidator.compatible(Transform.Number(2), f(FieldType.NUMBER), f(FieldType.TEXT)))
        assertTrue(DefinitionValidator.compatible(Transform.EnumMap(mapOf("a" to "b")), f(FieldType.CHOICE), f(FieldType.TEXT)))
        assertFalse(DefinitionValidator.compatible(Transform.EnumMap(mapOf("a" to "b")), f(FieldType.NUMBER), f(FieldType.CHOICE)))
    }
}
