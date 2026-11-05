package wasichai.integrations.connector.github

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class GitHubValuesTest {
    @Test
    fun `urls are https, or loopback http for a stub provider`() {
        GitHubConfig(webUrl = "https://ghe.example.com", apiUrl = "https://ghe.example.com/api/v3")
        GitHubConfig(apiUrl = "http://127.0.0.1:8123")
        assertThrows<IllegalArgumentException> { GitHubConfig(apiUrl = "http://api.github.com") }
    }

    @Test
    fun `only urls under the configured api count as api urls`() {
        val ghes = GitHubConfig(webUrl = "https://ghe.example.com", apiUrl = "https://ghe.example.com/api/v3/")
        assertTrue(ghes.isApiUrl("https://ghe.example.com/api/v3/repositories/1/issues?page=2"))
        assertFalse(ghes.isApiUrl("https://ghe.example.com/api/v30/x"))
        assertFalse(ghes.isApiUrl("https://ghe.example.com/login"))
        assertFalse(ghes.isApiUrl("https://ghe.example.com.evil.example/api/v3/x"))
        assertFalse(ghes.isApiUrl("https://u@ghe.example.com/api/v3/x"))
        assertFalse(ghes.isApiUrl("http://ghe.example.com/api/v3/x"))
        assertFalse(ghes.isApiUrl("not a url"))
        assertTrue(GitHubConfig().isApiUrl("https://api.github.com/user/repos?page=2"))
    }

    @Test
    fun `the next link is read from github's Link header`() {
        val header =
            "<https://api.github.com/repositories/1300192/issues?page=2>; rel=\"prev\", " +
                "<https://api.github.com/repositories/1300192/issues?page=4>; rel=\"next\", " +
                "<https://api.github.com/repositories/1300192/issues?page=515>; rel=\"last\", " +
                "<https://api.github.com/repositories/1300192/issues?page=1>; rel=\"first\""
        assertEquals("https://api.github.com/repositories/1300192/issues?page=4", GitHubLinks.next(header))
        assertNull(GitHubLinks.next("<https://api.github.com/x?page=1>; rel=\"first\""))
        assertNull(GitHubLinks.next(null))
    }

    @Test
    fun `checkpoints round-trip`() {
        val checkpoint = GitHubCheckpoint(Instant.parse("2026-11-04T12:00:00Z"), 3)
        assertEquals(checkpoint, GitHubCheckpoint.parse(checkpoint.render()))
        assertEquals(GitHubCheckpoint(null), GitHubCheckpoint.parse(null))
        assertEquals("{}", GitHubCheckpoint(null).render())
    }
}
