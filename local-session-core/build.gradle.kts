import org.gradle.api.tasks.compile.JavaCompile

// Platform-neutral local multiplayer protocol/state core. Discovery and sockets stay in hosts.
plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

group = "dev.aarso"
version = "0.1.0"

kotlin { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
java { withSourcesJar() }

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = "dev.aarso"
            artifactId = "local-session-core"
            version = "0.1.0"
        }
    }
}

dependencies { testImplementation(libs.junit) }

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
