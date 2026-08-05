// :search-testkit — conformance fixtures for anything implementing the :search-core contracts.
//
// Separate from :search-core's own test source set on purpose: a host that writes its own
// FieldRegistry, DocumentAdapter or Scorer needs to prove it upholds the same invariants, and
// those checks have to ship as a consumable artifact rather than living in a test source set
// nobody downstream can see.
plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

val testkitGroup = "dev.aarso"
val testkitArtifact = "search-testkit"
val testkitVersion = "0.1.0"

group = testkitGroup
version = testkitVersion

kotlin { jvmToolchain(17) }
java { withSourcesJar() }

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = testkitGroup
            artifactId = testkitArtifact
            version = testkitVersion
        }
    }
}

dependencies {
    api(project(":search-core"))
    // junit is an `api` dependency here by design: this module hands out assertions, so a
    // consumer that depends on it is necessarily writing tests.
    api(libs.junit)
}
