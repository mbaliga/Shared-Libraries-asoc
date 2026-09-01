plugins { alias(libs.plugins.kotlin.jvm) }

group = "dev.aarso"
version = "0.1.0"

dependencies { testImplementation(libs.junit) }
tasks.test { useJUnit() }
