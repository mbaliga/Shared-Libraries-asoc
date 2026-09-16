package dev.aarso.diagnostics.export

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import dev.aarso.diagnostics.Config
import dev.aarso.diagnostics.collect.DeviceContext
import dev.aarso.diagnostics.core.*
import java.io.File

/**
 * Writes the one file that leaves the device.
 *
 * Location is `getExternalFilesDir(null)/diagnostics/`: no runtime permission on any supported API
 * level, visible in any file manager, removed on uninstall. Retention is a plain count cap enforced
 * at write time, oldest first.
 */
internal class ReportWriter(private val context: Context, private val config: Config) {

    private val dir: File
        get() = File(context.getExternalFilesDir(null), "diagnostics").apply { mkdirs() }

    fun write(report: Report, labelOverride: String? = null): File {
        val md = MarkdownReporter(config.profile, config.redactor).render(report)
        val name = (labelOverride?.let { "${report.session.id}_$it" } ?: report.session.id) + ".md"
        val out = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "-"))
        out.writeText(md)
        prune()
        Log.i("Diag", "REPORT_PATH=${out.absolutePath} (${out.length()} bytes)")
        return out
    }

    fun list(): List<File> = dir.listFiles()
        ?.filter { it.isFile && it.name.endsWith(".md") }
        ?.sortedByDescending { it.lastModified() }
        ?: emptyList()

    private fun prune() {
        val files = list()
        if (files.size <= config.retainReports) return
        files.drop(config.retainReports).forEach { it.delete() }
    }
}

/**
 * Manual sharing only. There is no upload path in this module and no INTERNET permission in its
 * manifest — the absence is the enforcement, not the promise, and it can be verified by inspecting
 * the merged debug manifest.
 */
internal object Sharing {

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.diagnostics.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            // text/markdown is correct, but some targets (WhatsApp among them) filter it out;
            // text/plain keeps every target reachable in the chooser.
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share diagnostics report")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * Append-only journal so a process killed by the low-memory killer still leaves evidence.
 *
 * Writes go to internal storage (not the shared reports directory) because they are working state,
 * not deliverables. A journal that still exists at next launch belongs to a process that died — see
 * [recoverAll].
 */
internal class JournalWriter(private val context: Context, private val sessionId: String) {

    private val dir: File get() = File(context.filesDir, "diagnostics-journal").apply { mkdirs() }
    private val file: File get() = File(dir, "$sessionId.jrnl")

    fun begin(session: SessionInfo, app: AppInfo, profileId: String) {
        runCatching { file.appendText(Journal.header(session, app, profileId) + "\n") }
            .onFailure { Log.w("Diag", "journal begin failed: ${it.message}") }
    }

    fun checkpoint(
        tSec: Double,
        series: List<SeriesReport>,
        counters: Map<String, Long>,
        facts: Map<String, String>,
        trends: List<TrendGroup>,
    ) {
        runCatching { file.appendText(Journal.checkpoint(tSec, series, counters, facts, trends) + "\n") }
    }

    /**
     * On a clean stop the END marker is written and the journal is deleted — a session that
     * finished has already produced a real report, so keeping its journal would only create
     * phantom recoveries at next launch.
     */
    fun end(tSec: Double) {
        runCatching {
            file.appendText(Journal.end(tSec) + "\n")
            file.delete()
        }
    }

    companion object {

        /**
         * Any journal still on disk at launch is the residue of a process that did not exit
         * normally. Each is converted to a report marked `recovered`, whose confidence is
         * downgraded and whose survival invariant reports WARN.
         *
         * Worth calling early for the long-running profiles: without it, the runs that failed are
         * precisely the runs that leave no trace, and a history of clean reports can mean nothing
         * more than that the bad ones vanished.
         */
        fun recoverAll(context: Context, config: Config, excludeSessionId: String? = null): List<File> {
            val dir = File(context.filesDir, "diagnostics-journal")
            val journals = dir.listFiles { f -> f.isFile && f.name.endsWith(".jrnl") }
                ?: return emptyList()
            val out = mutableListOf<File>()
            for (j in journals) {
                // The journal file name is exactly "$sessionId.jrnl" (see `file` above), so this is
                // an exact, cheap exclusion of the currently-running session's own journal -- see
                // Diagnostics.recoverAbandonedSessions() for why that exclusion has to exist at all.
                if (excludeSessionId != null && j.name == "$excludeSessionId.jrnl") continue
                val rec = runCatching { Journal.parse(j.readLines()) }.getOrNull()
                if (rec == null) { j.delete(); continue }
                val profile = Profiles.byId(rec.profileId) ?: config.profile
                val device = (context.applicationContext as? android.app.Application)
                    ?.let { DeviceContext.read(it) } ?: continue
                val report = Journal.toReport(rec, profile, device)
                out += ReportWriter(context, config.copy(profile = profile)).write(report)
                j.delete()
                Log.i("Diag", "recovered abandoned session ${rec.sessionId} " +
                    "(${rec.checkpointCount} checkpoints, clean=${rec.cleanEnd})")
            }
            return out
        }
    }
}
