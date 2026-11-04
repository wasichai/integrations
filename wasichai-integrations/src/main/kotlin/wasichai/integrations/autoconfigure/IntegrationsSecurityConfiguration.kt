package wasichai.integrations.autoconfigure

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers

// the only routes of the module a provider calls without a wasichai token: the oauth callback (the state row
// carries the tenant) and inbound webhooks (the signature does). everything else stays behind core's chain.
@Configuration(proxyBeanMethods = false)
class IntegrationsSecurityConfiguration {
    @Bean
    @Order(-10)
    fun integrationsPublicChain(http: ServerHttpSecurity): SecurityWebFilterChain =
        http
            .securityMatcher(ServerWebExchangeMatchers.pathMatchers(*PUBLIC_PATHS))
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .authorizeExchange { it.anyExchange().permitAll() }
            .build()

    companion object {
        val PUBLIC_PATHS = arrayOf("/api/integrations/oauth/callback", "/api/integrations/webhooks/**")
    }
}
