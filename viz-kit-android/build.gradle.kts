// :viz-kit-android — the native chart layer of the shared dashboard: a Kotlin/Canvas port of
// web/viz-kit's look, so a chart reads the same in a WebView-free Android screen as it does on
// the web. Plain android.view.View subclasses (no Compose, no WebView, no network, no third-party
// dependency): one ink on one ground, identity carried by texture, bars from zero on a linear
// scale, one y axis, two-pixel ground gaps between touching parts.
//
// The data contract (CONTRACT.md) is deliberately identical to viz.js's, so ONE JSON feed can
// drive both the web kit and this one. Graph, rose and gantt are NOT ported: they sit on the
// Orrery engines (web/orrery-kit), which have no native counterpart.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

val vizGroup = "dev.aarso"
val vizArtifact = "viz-kit-android"

// 0.1.0 — first cut, ported from web/viz-kit/viz.js 0.2.0 (steps, bars, fan, stack, line, spark,
// halftone, meter, stat, legend) with virtual accessibility nodes on every mark. Keep this in
// step with Viz.VERSION (VersionTest pins the two together).
val vizVersion = "0.1.0"

group = vizGroup
version = vizVersion

android {
    namespace = "dev.aarso.viz"
    compileSdk = 36

    defaultConfig {
        // Same floor as :cell-shell. Nothing here needs more than API 26 (font weights above
        // 400 degrade to the light/medium/regular system families below API 28).
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Robolectric's native graphics mode rasterises the real Canvas, so the render tests can
    // write PNGs of every chart. isIncludeAndroidResources lets it build a real Context.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Opt-in render output directory: `-Dviz.render.dir=<dir>` is forwarded into the
                // test JVM only when set, so an ordinary run writes nothing outside build/.
                System.getProperty("viz.render.dir")?.let { d -> it.systemProperty("viz.render.dir", d) }
                if (System.getProperty("viz.render.dir") != null) it.outputs.upToDateWhen { false }
                it.maxHeapSize = "1536m"
            }
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    jvmToolchain(17)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = vizGroup
                artifactId = vizArtifact
                version = vizVersion
            }
        }
    }
}

dependencies {
    // ExploreByTouchHelper (virtual accessibility nodes). AndroidX, no network, no transitive
    // surprises: customview pulls only core + annotation, which every Android app carries.
    implementation(libs.androidx.customview)
    implementation(libs.androidx.core)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
