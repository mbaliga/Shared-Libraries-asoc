plugins { alias(libs.plugins.kotlin.jvm) }

group = "com.asystemofcells"
version = "0.1.0"

dependencies { testImplementation(libs.junit) }
tasks.test { useJUnit() }
