package dev.aarso.platformports

/**
 * The stub implementations. Each one reports `isStub = true`, carries the sentence a UI shows,
 * and refuses to do anything rather than pretend. A platform actual uses these for every part
 * it has not implemented against the host, so the "says so in the UI" text lives in one place.
 */
object Stubs {
    fun appDirectories(reason: String): AppDirectoriesProvider = object : AppDirectoriesProvider {
        override val name = "app-directories"
        override val isStub = true
        override val stubNotice: String = reason

        override fun resolve(): Reading<AppDirectories> = Reading.Unavailable(reason)
    }

    fun secretStore(reason: String): SecretStore = object : SecretStore {
        override val name = "secret-store"
        override val isStub = true
        override val stubNotice: String = reason
        override val tier = KeyStorageTier.UNAVAILABLE
        override val backend = "none"

        override fun put(name: String, value: ByteArray): Unit = throw SecretStoreException(reason)

        override fun get(name: String): ByteArray? = throw SecretStoreException(reason)

        override fun remove(name: String): Unit = throw SecretStoreException(reason)

        override fun names(): Set<String> = throw SecretStoreException(reason)
    }

    fun filePicker(reason: String): FilePicker = object : FilePicker {
        override val name = "file-picker"
        override val isStub = true
        override val stubNotice: String = reason

        override fun pick(request: PickRequest): PickOutcome = PickOutcome.Unavailable(reason)
    }

    fun shareSheet(reason: String): ShareSheet = object : ShareSheet {
        override val name = "share-sheet"
        override val isStub = true
        override val stubNotice: String = reason

        override fun share(request: ShareRequest): ShareOutcome = ShareOutcome.Unavailable(reason)
    }

    fun powerProbe(reason: String): PowerProbe = object : PowerProbe {
        override val name = "power-probe"
        override val isStub = true
        override val stubNotice: String = reason

        override fun read(): Reading<PowerState> = Reading.Unavailable(reason)
    }

    fun thermalProbe(reason: String): ThermalProbe = object : ThermalProbe {
        override val name = "thermal-probe"
        override val isStub = true
        override val stubNotice: String = reason

        override fun read(): Reading<ThermalState> = Reading.Unavailable(reason)
    }

    fun networkProbe(reason: String): NetworkProbe = object : NetworkProbe {
        override val name = "metered-network-probe"
        override val isStub = true
        override val stubNotice: String = reason

        override fun read(): Reading<MeteredState> = Reading.Unavailable(reason)
    }

    fun rfcomm(reason: String): RfcommTransport = object : RfcommTransport {
        override val name = "bluetooth-rfcomm"
        override val isStub = true
        override val stubNotice: String = reason

        override fun connect(address: BluetoothAddress, serviceUuid: String): RfcommOutcome =
            RfcommOutcome.Unavailable(reason)
    }

    /** Every part a stub, all citing the same [reason]. For hosts where nothing is implemented. */
    fun allStub(reason: String): PlatformPorts = PlatformPorts(
        appDirectories = appDirectories(reason),
        secrets = secretStore(reason),
        filePicker = filePicker(reason),
        shareSheet = shareSheet(reason),
        power = powerProbe(reason),
        thermal = thermalProbe(reason),
        network = networkProbe(reason),
        rfcomm = rfcomm(reason),
    )
}
