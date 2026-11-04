plugins {
    id("wasichai.kotlin-library")
    id("wasichai.publishing")
}

description = "Wasichai integrations connector SDK: the contracts a connector implements. No Spring."

dependencies {
    api(platform(libs.spring.boot.bom))
    api(platform(libs.jackson3.bom))
    api(libs.kotlinx.coroutines.core)
    api(libs.jackson.databind)
}
