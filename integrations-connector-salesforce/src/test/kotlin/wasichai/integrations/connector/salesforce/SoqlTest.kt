package wasichai.integrations.connector.salesforce

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class SoqlTest {
    @Test
    fun `api names pass, anything else is refused`() {
        listOf("Account", "Custom__c", "ns__Field__c", "Parent_Id", "A").forEach { Soql.identifier(it) }
        listOf("", "1Account", "Name, (SELECT Id FROM Contacts)", "Id FROM User --", "Name'", "a.b", "Field_", "x y").forEach {
            assertThrows<IllegalArgumentException> { Soql.identifier(it) }
        }
    }

    @Test
    fun `string literals escape quotes, backslashes and control characters`() {
        assertEquals("""'O\'Brien'""", Soql.string("O'Brien"))
        assertEquals("""'a\\b'""", Soql.string("a\\b"))
        assertEquals("""'x\' OR Name != \'y'""", Soql.string("x' OR Name != 'y"))
        assertEquals("""'l1\nl2\t\"q\"'""", Soql.string("l1\nl2\t\"q\""))
    }

    @Test
    fun `select assembles validated parts`() {
        assertEquals(
            "SELECT Id, Name FROM Account WHERE SystemModstamp >= 2026-11-04T12:30:15Z ORDER BY SystemModstamp, Id LIMIT 10",
            Soql.select(
                listOf("Id", "Name"),
                "Account",
                "SystemModstamp >= ${Soql.datetime(Instant.parse("2026-11-04T12:30:15.999Z"))}",
                listOf("SystemModstamp", "Id"),
                10
            )
        )
        assertThrows<IllegalArgumentException> { Soql.select(listOf("Id"), "Account; DELETE") }
    }

    @Test
    fun `salesforce timestamps parse in native and iso forms`() {
        assertEquals(Instant.parse("2026-11-04T12:00:00.123Z"), SalesforceTimes.parse("2026-11-04T12:00:00.123+0000"))
        assertEquals(Instant.parse("2026-11-04T10:00:00Z"), SalesforceTimes.parse("2026-11-04T12:00:00+02:00"))
        assertEquals("Wed, 4 Nov 2026 12:00:00 GMT", SalesforceTimes.httpDate(Instant.parse("2026-11-04T12:00:00Z")))
    }
}
