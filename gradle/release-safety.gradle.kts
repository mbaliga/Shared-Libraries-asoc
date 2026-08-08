/**
 * Drop this into the HOST APP's build script (or apply it from a Hyle convention plugin).
 *
 * A diagnostics collector alive in a shipped APK is a privacy problem, not just dead weight. Three
 * things are meant to prevent it: debugImplementation scoping, the runtime FLAG_DEBUGGABLE check in
 * install(), and this. Only this one fails the build, which makes it the one that matters — the
 * other two assume someone wired the dependency correctly, and that is exactly the mistake being
 * guarded against.
 *
 *     apply(from = "$rootDir/gradle/release-safety.gradle.kts")
 */

afterEvaluate {
    val forbidden = listOf("diagnostics-android", "diagnostics-overlay")

    configurations.matching { c ->
        c.name.contains("release", ignoreCase = true) &&
            (c.name.contains("CompileClasspath") || c.name.contains("RuntimeClasspath"))
    }.configureEach {
        incoming.afterResolve {
            val hits = resolutionResult.allComponents
                .map { it.id.displayName }
                .filter { id -> forbidden.any { id.contains(it) } }

            if (hits.isNotEmpty()) throw GradleException(
                """
                |
                |  RELEASE BUILD RESOLVED A DIAGNOSTICS COLLECTOR.
                |
                |  Configuration : ${this@configureEach.name}
                |  Offending     : ${hits.distinct().joinToString(", ")}
                |
                |  These artifacts are debugImplementation only. Release variants must resolve
                |  dev.aarso:diagnostics-noop, which mirrors the API and does nothing.
                |
                |      debugImplementation("dev.aarso:diagnostics-android:0.2.0")
                |      debugImplementation("dev.aarso:diagnostics-overlay:0.2.0")
                |      releaseImplementation("dev.aarso:diagnostics-noop:0.2.0")
                |
                """.trimMargin())
        }
    }
}

/**
 * Second guard: assert the merged release manifest never gains an INTERNET permission from this
 * module. Its own manifest deliberately declares none — that absence is what makes "cannot transmit
 * anything" checkable rather than merely stated, so it deserves a test that notices if it ever
 * stops being true.
 */
tasks.register("assertNoDiagnosticsInternetPermission") {
    group = "verification"
    doLast {
        fileTree("$buildDir/intermediates/merged_manifest") {
            include("**/release/**/AndroidManifest.xml")
        }.forEach { m ->
            val text = m.readText()
            if (text.contains("android.permission.INTERNET") && text.contains("diagnostics"))
                throw GradleException("diagnostics contributed INTERNET to ${m.path}")
        }
    }
}

/**
 * Third guard: the no-op must mirror the real facade. Drift here does not fail until someone's
 * release build breaks — see scripts/check-noop-parity.py for why that is the worst possible
 * moment. This IS the library's own CI job (run there directly against its own checkout, not
 * relative to a consumer), so this task is best-effort for a host app: `$rootDir` here is the
 * HOST APP's root (this file's own top comment: "drop this into the host app's build script"),
 * and the script only exists inside whatever submodule checkout the host pinned. README.md's
 * documented convention is `shared-libraries`; if a host used a different submodule directory
 * name, or hasn't added the submodule at all, this degrades to a skipped task with an
 * explanatory message rather than a hard failure over a path guess this file cannot verify.
 */
tasks.register("checkNoopParity") {
    group = "verification"
    val candidateScript = file("$rootDir/shared-libraries/scripts/check-noop-parity.py")
    onlyIf {
        if (!candidateScript.exists()) {
            logger.warn(
                "checkNoopParity: no script at ${candidateScript.path} (expects the " +
                    "shared-libraries submodule at that path per README.md's documented " +
                    "convention) -- skipping. Run it directly in the shared-libraries checkout " +
                    "if your submodule uses a different directory name."
            )
        }
        candidateScript.exists()
    }
    doLast {
        exec { commandLine("python3", candidateScript.path) }
    }
}
