package dev.aarso.crashrecovery

import android.content.Context
import java.io.InputStream
import java.io.OutputStream

/**
 * A host-supplied bridge that lets the recovery screen save and restore an app's *real* data —
 * the projects a Fonebrew user would be heartbroken to lose — off-device, in a form the app
 * itself understands. Register once via [CrashRecovery.setSalvager].
 *
 * This is optional: with no salvager, recovery still protects data generically by
 * quarantining it on-device (moving it aside instead of deleting it). A salvager adds the
 * ability to write a portable backup the user can keep anywhere and restore later.
 *
 * Implementations must not assume any particular thread but should stream (not buffer whole)
 * for large data — the recovery screen calls these with a user-chosen file's stream.
 */
interface CrashSalvager {
    /** Short human summary of what a backup would contain, e.g. "3 projects · 1.2 GB". Optional. */
    fun describe(context: Context): String? = null

    /** Write a single self-contained backup of the app's precious data to [out] (e.g. a zip). */
    fun exportTo(context: Context, out: OutputStream)

    /** Whether [importFrom] can restore a backup produced by [exportTo]. */
    fun canImport(): Boolean = false

    /** Restore a backup previously written by [exportTo]. Only called when [canImport] is true. */
    fun importFrom(context: Context, source: InputStream) {}
}
