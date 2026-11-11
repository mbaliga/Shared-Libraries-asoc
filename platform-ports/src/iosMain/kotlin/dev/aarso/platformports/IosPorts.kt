package dev.aarso.platformports

/**
 * IOS ACTUAL: A STUB. Not compiled or run here (this container has no Mac and the Kotlin/Native
 * iOS targets are disabled on Linux). Keychain, document picker, share sheet, thermal state, power
 * and Network.framework probes and Core Bluetooth are all still to be written on a Mac.
 */
actual fun defaultPlatformPorts(appId: String): PlatformPorts =
    Stubs.allStub("The iOS version of this part is not written yet. It is a stub and does nothing.")
