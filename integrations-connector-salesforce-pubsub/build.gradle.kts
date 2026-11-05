plugins {
    id("wasichai.kotlin-library")
    id("wasichai.publishing")
    alias(libs.plugins.protobuf)
}

description = "Wasichai integrations Salesforce Change Data Capture: the Salesforce connector with a Pub/Sub API (gRPC) change feed"

// kept out of integrations-connector-salesforce so polling users never pull grpc, protobuf and avro (ADR-0024)
dependencies {
    api(project(":integrations-connector-salesforce"))
    implementation(libs.grpc.okhttp)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.kotlin.stub)
    implementation(libs.protobuf.java)
    implementation(libs.avro)
    // avro reads its schemas with jackson 2: the same security floor as the spring modules
    implementation(platform(libs.jackson2.bom))
    testImplementation(project(":integrations-connector-sdk-test"))
    testImplementation(libs.grpc.inprocess)
}

fun coordinates(dependency: Provider<MinimalExternalModuleDependency>): String = dependency.get().let { "${it.module}:${it.versionConstraint.requiredVersion}" }

// messages and the PubSub service descriptors from src/main/proto; generated java is not ours to lint
protobuf {
    protoc { artifact = coordinates(libs.protobuf.protoc) }
    plugins {
        create("grpc") { artifact = coordinates(libs.grpc.protoc.gen.java) }
    }
    generateProtoTasks {
        all().configureEach {
            plugins { create("grpc") }
        }
    }
}

ktlint {
    filter { exclude { it.file.path.contains("/build/generated/") } }
}
