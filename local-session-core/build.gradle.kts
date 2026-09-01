plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

val localSessionGroup = "dev.aarso"
val localSessionArtifact = "local-session-core"
val localSessionVersion = "0.1.0"

group = localSessionGroup
version = localSessionVersion

kotlin {
    jvmToolchain(17)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = localSessionGroup
            artifactId = localSessionArtifact
            version = localSessionVersion
        }
    }
}

dependencies {
    testImplementation(libs.junit)
}
