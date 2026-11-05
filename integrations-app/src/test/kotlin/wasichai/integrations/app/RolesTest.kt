package wasichai.integrations.app

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource

// one image, two roles: the profile alone decides whether a replica runs background work
class RolesTest {
    private val switches =
        listOf(
            "wasichai.integrations.jobs.worker-enabled",
            "wasichai.integrations.sync.scheduler-enabled",
            "wasichai.integrations.workflows.scheduler-enabled"
        )

    private fun profile(name: String): Map<String, String> {
        val source = YamlPropertySourceLoader().load(name, ClassPathResource("application-$name.yml")).single()
        return switches.associateWith { source.getProperty(it).toString() }
    }

    @Test
    fun `the api role runs no worker and no scheduler`() {
        assertThat(profile("api").values).containsOnly("false")
    }

    @Test
    fun `the worker role runs the worker and both schedulers`() {
        assertThat(profile("worker").values).containsOnly("true")
    }
}
