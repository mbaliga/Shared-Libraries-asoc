package dev.aarso.platformports

/** All ports for one host, and the one place that lists every stub among them. */
class PlatformPorts(
    val appDirectories: AppDirectoriesProvider,
    val secrets: SecretStore,
    val filePicker: FilePicker,
    val shareSheet: ShareSheet,
    val power: PowerProbe,
    val thermal: ThermalProbe,
    val network: NetworkProbe,
    val rfcomm: RfcommTransport,
) {
    fun components(): List<PortComponent> =
        listOf(appDirectories, secrets, filePicker, shareSheet, power, thermal, network, rfcomm)

    /** One notice per stub, for an "About this build / what is not real here" screen. */
    fun stubNotices(): List<StubNotice> = components().mapNotNull { it.notice() }

    /** Components that break the stub contract (should always be empty; tests assert it). */
    fun contractViolations(): List<String> = components().filter { it.violatesStubContract() }.map { it.name }

    fun keyStorageReport(): KeyStorageReport = secrets.report()
}

/**
 * The default ports for the host this was compiled for. JVM: real where the host can be
 * reached from plain Java (directories, file-based secret tiers, Linux sysfs probes, Swing
 * picker). Android and iOS: see their actuals; without an Android Context everything is a stub.
 */
expect fun defaultPlatformPorts(appId: String): PlatformPorts
