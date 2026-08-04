package dev.aarso.crashrecovery

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * The recovery surface shown on the launch after a captured crash (see [CrashRecovery]).
 * Deliberately built on the bare platform (`android.app.Activity` + `android.widget`
 * views only — no AppCompat, no Compose, no Material) so it can never be brought down by
 * whatever broke in the app that hosted it, and so apps that don't ship Compose never have
 * to add it just for this one screen.
 *
 * Two panes on a neutral paper surface:
 *  - **Main** — the crash mark, a calm one-line explanation of what happened, a privacy
 *    reassurance, Continue, and the ways to share the report.
 *  - **Full report** — every field that would leave the device, word for word, behind an
 *    explicit "View the full report" so the first thing a user sees is legible, not a wall
 *    of stack frames.
 *
 * Nothing is ever sent anywhere by this screen; sharing is always user-initiated.
 */
class CrashRecoveryActivity : Activity() {

    private lateinit var pal: CrashRecoveryStyle.Palette
    private lateinit var appLabel: String
    private lateinit var report: CrashReport.Decoded
    private var contactEmail: String? = null
    private var consecutive = 1

    /**
     * Preview mode (see [CrashRecovery.previewIntent]): sample content, and the two actions
     * with real consequences are inert. Everything else — layout, Share, Copy, the details
     * pane — behaves exactly as it does after a real crash, because reviewing the real thing
     * is the entire point of a preview.
     */
    private var preview = false

    private lateinit var paneMain: View
    private lateinit var paneDetails: View
    private lateinit var toast: TextView
    private lateinit var root: FrameLayout

    private var pendingSaveText: String? = null

    @Suppress("DEPRECATION")
    private fun readStyle(): CrashRecoveryStyle =
        (intent.getSerializableExtra(EXTRA_STYLE) as? CrashRecoveryStyle) ?: CrashRecoveryStyle.Default

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appLabel = intent.getStringExtra(EXTRA_APP_LABEL) ?: "App"
        contactEmail = intent.getStringExtra(EXTRA_CONTACT_EMAIL)
        preview = intent.getBooleanExtra(EXTRA_PREVIEW, false)

        // In preview the report is synthesized, never read from disk — so previewing works
        // even when no crash has ever been captured, and can't consume a real pending one.
        val decoded = if (preview) CrashReport.samplePreview(appLabel) else CrashRecovery.pending(this)

        // Nothing to recover from (e.g. launched directly for testing, or cleared between the
        // check in maybeShowRecovery and here) — don't strand the user on a blank screen.
        if (decoded == null) {
            finish()
            return
        }
        report = decoded
        // Show the repeat-crash reset affordance in preview so it can be reviewed; a real
        // streak read would report 0 here and hide the section being previewed.
        consecutive = if (preview) REPEAT_THRESHOLD else CrashRecovery.consecutiveCount(this)

        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        pal = readStyle().resolve(night)

        setContentView(buildRoot())
    }

    private fun buildRoot(): View {
        root = FrameLayout(this).apply {
            setBackgroundColor(pal.paper)
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        paneMain = buildMainPane()
        paneDetails = buildDetailsPane()
        toast = buildToast()

        root.addView(paneMain, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(paneDetails, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(
            toast,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = 26.dp
            },
        )

        // Details starts off-screen to the right; corrected once we know the width.
        paneDetails.post { paneDetails.translationX = root.width.toFloat() }
        return root
    }

    // ---------------- main pane ----------------

    private fun buildMainPane(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.paper)
            setPadding(24.dp, 8.dp, 24.dp, 14.dp)
        }

        // Crash mark — centered, tappable to replay the glitch.
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.cr_crash_mark)
            setColorFilter(pal.ink)
            layoutParams = LinearLayout.LayoutParams(132.dp, 183.dp).apply {
                topMargin = 18.dp
                bottomMargin = 6.dp
                gravity = Gravity.CENTER_HORIZONTAL
            }
            isClickable = true
            contentDescription = "Replay the crash animation"
            setOnClickListener { glitch(this) }
        }
        col.addView(icon)
        startGlitchLoop(icon)

        col.addView(centered(text("Well, that happened.", 23f, pal.ink, bold = true)))
        col.addView(
            centered(
                text(
                    "$appLabel hit a snag last time and had to close. Sorry about that — " +
                        "here's exactly what happened.",
                    13f,
                    pal.inkSoft,
                ).apply { maxWidth = 300.dp },
            ).apply { (getChildAt(0) as TextView).gravity = Gravity.CENTER },
        )
        col.addView(spacer(10))
        col.addView(privacyCard())
        col.addView(spacer(8))
        col.addView(whatCard())

        col.addView(
            pillButton("Continue to $appLabel", pal.accent, pal.onAccent, filled = true) { continueToApp() }
                .withTopMargin(10),
        )
        col.addView(
            pillButton("View the full report", pal.ink, pal.paper, filled = false) { showDetails() }
                .withTopMargin(7),
        )

        col.addView(shareRow(includeCopy = false).withTopMargin(7))

        col.addView(
            text("Discard this report", 12f, pal.inkSoft).apply {
                gravity = Gravity.CENTER
                paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                isClickable = true
                setPadding(0, 10.dp, 0, 4.dp)
                setOnClickListener { discard() }
            },
        )

        return ScrollView(this).apply {
            setBackgroundColor(pal.paper)
            isFillViewport = true
            addView(col)
        }
    }

    private fun privacyCard(): View {
        val body = SpannableStringBuilder()
        appendBold(body, "This report exists only on your device.")
        body.append(
            " Nothing was sent anywhere — there is no server and no telemetry. It is shared " +
                "only if you choose to share it, and you can read every word of it first.",
        )
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(11.dp, 9.dp, 11.dp, 9.dp)
            background = outline(pal.line, 12)
            addView(text("🔒", 13f, pal.ink).apply {
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = 1.dp; rightMargin = 9.dp }
            })
            addView(TextView(this@CrashRecoveryActivity).apply {
                setText(body, TextView.BufferType.SPANNABLE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(pal.inkSoft)
                setLineSpacing(0f, 1.15f)
            })
        }
    }

    private fun whatCard(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp, 10.dp, 12.dp, 10.dp)
            background = filled(pal.card, 12)
            addView(text("WHAT HAPPENED", 10f, pal.inkSoft).apply { letterSpacing = 0.08f })
            addView(spacer(3))
            addView(text(report.plainLanguage, 13f, pal.ink).apply { setLineSpacing(0f, 1.15f) })
            val meta = listOfNotNull(
                report.whenShort().takeIf { it.isNotBlank() },
                "$appLabel ${report.versionLabel() ?: ""}".trim(),
            ).joinToString("  ·  ")
            addView(spacer(5))
            addView(text(meta, 11f, pal.inkSoft))
        }

    // ---------------- details pane ----------------

    private fun buildDetailsPane(): View {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.paper)
        }

        // header with Back
        outer.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(12.dp, 14.dp, 12.dp, 8.dp)
                addView(text("‹  Back", 15f, pal.ink, bold = true).apply {
                    isClickable = true
                    setPadding(8.dp, 8.dp, 8.dp, 8.dp)
                    setOnClickListener { showMain() }
                })
            },
        )

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 0, 24.dp, 14.dp)
        }
        col.addView(text("The full report", 21f, pal.ink, bold = true))
        col.addView(spacer(6))
        col.addView(
            text(
                "Everything below is the entire report — if you share it, this is all that " +
                    "leaves your phone, word for word.",
                12.5f,
                pal.inkSoft,
            ).apply { setLineSpacing(0f, 1.2f) },
        )

        col.addView(section("Error", listOf(
            "Type" to report.excType,
            "Message" to (report.excMessage ?: "(none)"),
            "Thread" to report.threadName.ifBlank { "?" },
            "When" to report.whenText(),
        )))
        col.addView(section("App", listOf(
            "App" to appLabel,
            "Version" to (report.versionLabel() ?: "?"),
            "Package" to (report.packageName ?: "?"),
            "Install source" to (report.installSource ?: "Unknown"),
        )))
        col.addView(section("Device", buildList {
            add("Model" to deviceModelText())
            add("Android" to (report.osSdkInt?.let { "API $it" } ?: "?"))
            memoryText()?.let { add("Free memory" to it) }
        }))

        // stack trace — mono, its own scroll, horizontal scroll for long frames
        col.addView(sectionHeader("Stack trace"))
        col.addView(
            ScrollView(this).apply {
                background = filled(pal.monoBg, 12)
                layoutParams = LinearLayout.LayoutParams(MATCH, 220.dp)
                addView(HorizontalScrollView(this@CrashRecoveryActivity).apply {
                    addView(text(report.trace, 11f, pal.monoInk, monospace = true).apply {
                        setPadding(14.dp, 12.dp, 14.dp, 12.dp)
                        setLineSpacing(0f, 1.35f)
                    })
                })
            },
        )

        col.addView(sectionHeader("Not in this report"))
        col.addView(
            TextView(this).apply {
                val sb = SpannableStringBuilder()
                appendBold(sb, "No identifiers of any kind.")
                sb.append(
                    " No account, no email, no advertising ID, no location, no contacts, no file " +
                        "names, no network activity. The report is plain text and you're looking at all of it.",
                )
                setText(sb, TextView.BufferType.SPANNABLE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(pal.inkSoft)
                setLineSpacing(0f, 1.25f)
                setPadding(14.dp, 12.dp, 14.dp, 12.dp)
                background = dashed(pal.line, 12)
            },
        )

        // Loop-gated reset — only once the crash has actually recurred (Continue already
        // failed). Kept in the full report, never on the calm main screen, and confirm-gated,
        // so a one-off crash never exposes a data-wiping footgun.
        if (consecutive >= REPEAT_THRESHOLD) col.addView(resetSection())

        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(col)
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        outer.addView(scroll)

        // bottom action bar — a hairline divider, then the share row, on paper.
        outer.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(pal.paper)
                addView(divider())
                addView(LinearLayout(this@CrashRecoveryActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(24.dp, 12.dp, 24.dp, 18.dp)
                    addView(shareRow(includeCopy = true))
                })
            },
        )

        return outer
    }

    /** Shown only after a repeat crash: an explanation and a confirm-gated, data-wiping reset. */
    private fun resetSection(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 22.dp }
            addView(sectionHeader("Still happening?"))
            addView(
                text(
                    "$appLabel has now crashed more than once in a row, so continuing may just " +
                        "hit the same problem. As a last resort you can reset its data — this wipes " +
                        "everything $appLabel has stored on this device and can't be undone.",
                    12.5f,
                    pal.inkSoft,
                ).apply { setLineSpacing(0f, 1.25f) },
            )
            addView(
                pillButton("Reset $appLabel's data", pal.danger, pal.paper, filled = false) { confirmReset() }
                    .withTopMargin(10).also {
                        (it as Button).setTextColor(pal.danger)
                        it.background = GradientDrawable().apply {
                            setColor(pal.paper); setStroke(2.dp, pal.danger); cornerRadius = 999.dp.toFloat()
                        }
                    },
            )
        }

    private fun confirmReset() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Reset $appLabel's data?")
            .setMessage(
                "This wipes everything $appLabel has stored on this device and restarts it. " +
                    "This cannot be undone.",
            )
            .setPositiveButton("Reset") { _, _ -> performReset() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performReset() {
        // Inert in preview — previewing the screen must never be able to wipe app data.
        if (preview) {
            showToast("Preview — no data was reset")
            return
        }
        CrashRecovery.clear(this)
        CrashRecovery.clearStreak(this)
        // The zero-arg self-clear is API 29+ only; several consumers have a lower minSdk
        // (Animalcules 24, Horizkeeb 28), so guiding to Settings is the honest fallback.
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                (getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
                    .clearApplicationUserData()
            }
        } else {
            showToast("Clear $appLabel's storage in Android Settings, then reopen it.")
        }
    }

    private fun section(title: String, rows: List<Pair<String, String>>): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 18.dp }
            addView(sectionHeader(title))
            rows.forEachIndexed { i, (k, v) ->
                addView(kvRow(k, v))
                if (i != rows.lastIndex) addView(divider())
            }
        }

    private fun sectionHeader(title: String): View =
        text(title.uppercase(), 10.5f, pal.inkSoft, bold = true).apply {
            letterSpacing = 0.09f
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 18.dp; bottomMargin = 8.dp }
        }

    private fun kvRow(key: String, value: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 7.dp, 0, 7.dp)
            addView(text(key, 13f, pal.inkSoft).apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 0.4f)
            })
            addView(text(value, 13f, pal.ink).apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 0.6f)
            })
        }

    private fun deviceModelText(): String {
        val man = report.deviceManufacturer?.takeIf { it.isNotBlank() && it != "?" }
        val model = report.deviceModel ?: "?"
        return if (man != null && !model.startsWith(man, ignoreCase = true)) "$man $model" else model
    }

    private fun memoryText(): String? {
        val free = report.freeMemMb ?: return null
        val total = report.totalMemMb ?: return null
        val totalText = if (total >= 1024) "${(total / 1024.0).let { if (it % 1.0 == 0.0) it.toInt().toString() else String.format("%.1f", it) }} GB" else "$total MB"
        return "$free MB of $totalText at crash"
    }

    // ---------------- share row ----------------

    private fun shareRow(includeCopy: Boolean): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            if (includeCopy) addView(shareTile("Copy") { copyReport() }.also { addGap(it) })
            addView(shareTile("Save file") { saveReport() }.also { addGap(it) })
            addView(shareTile("Email") { emailReport() }.also { addGap(it) })
            addView(shareTile("WhatsApp") { whatsappReport() })
        }

    private fun LinearLayout.addGap(child: View) {
        (child.layoutParams as LinearLayout.LayoutParams).rightMargin = 8.dp
    }

    private fun shareTile(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setAllCaps(false)
            setTextColor(pal.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create(typeface, Typeface.BOLD)
            background = filled(pal.card, 12)
            setPadding(4.dp, 12.dp, 4.dp, 12.dp)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            setOnClickListener { onClick() }
        }

    // ---------------- actions ----------------

    private fun reportText(): String = report.fullReport

    private fun continueToApp() {
        // Inert in preview beyond closing: no stored report to clear (none was read), and
        // relaunching the app from a preview would be a surprising side effect of looking.
        if (preview) {
            finish()
            return
        }
        CrashRecovery.clear(this)
        runCatching { packageManager.getLaunchIntentForPackage(packageName) }
            .getOrNull()
            ?.let { startActivity(it) }
        finish()
    }

    private fun discard() {
        CrashRecovery.clear(this)
        showToast("Report deleted from device")
        paneMain.postDelayed({ continueToApp() }, 650)
    }

    private fun copyReport() {
        runCatching {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Crash report", reportText()))
            // API 33+ shows its own copy confirmation; ours would be redundant there.
            if (Build.VERSION.SDK_INT < 33) showToast("Copied to clipboard")
        }
    }

    private fun saveReport() {
        pendingSaveText = reportText()
        val name = "crash-report-${report.packageName ?: packageName}-${System.currentTimeMillis()}.txt"
        val create = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        runCatching { startActivityForResult(create, REQ_SAVE) }
            .onFailure {
                // No document provider (rare) — fall back to a share sheet so the text still escapes.
                pendingSaveText = null
                shareVia(null)
            }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SAVE && resultCode == RESULT_OK) {
            val uri = data?.data
            val textToWrite = pendingSaveText
            pendingSaveText = null
            if (uri != null && textToWrite != null) {
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { it.write(textToWrite.toByteArray()) }
                    showToast("Report saved")
                }.onFailure { showToast("Couldn't save the file") }
            }
        }
    }

    private fun emailReport() {
        val subject = "Crash report — $appLabel ${report.versionLabel() ?: ""}".trim()
        val body = reportText()
        val email = contactEmail
        val intent = if (!email.isNullOrBlank()) {
            Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
            }
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = "message/rfc822"
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
            }
        }
        runCatching { startActivity(Intent.createChooser(intent, "Email crash report")) }
            .onFailure { showToast("No email app found") }
    }

    private fun whatsappReport() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, reportText())
            setPackage("com.whatsapp")
        }
        try {
            startActivity(send)
        } catch (e: ActivityNotFoundException) {
            // WhatsApp not installed — offer the generic chooser rather than dead-ending.
            shareVia(null)
        }
    }

    private fun shareVia(pkg: String?) {
        runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Crash report — $appLabel")
                putExtra(Intent.EXTRA_TEXT, reportText())
                if (pkg != null) setPackage(pkg)
            }
            startActivity(Intent.createChooser(send, "Share crash report"))
        }
    }

    // ---------------- pane transitions ----------------

    private fun showDetails() {
        val w = root.width.toFloat()
        paneMain.animate().translationX(-w * 0.28f).setDuration(320).start()
        paneDetails.animate().translationX(0f).setDuration(320).start()
    }

    private fun showMain() {
        val w = root.width.toFloat()
        paneMain.animate().translationX(0f).setDuration(320).start()
        paneDetails.animate().translationX(w).setDuration(320).start()
    }

    override fun onBackPressed() {
        // If the report is open, Back returns to the main pane rather than leaving the screen.
        if (paneDetails.translationX < root.width * 0.5f) showMain() else super.onBackPressed()
    }

    // ---------------- crash-mark glitch ----------------

    private val reduceMotion: Boolean
        get() = runCatching {
            android.provider.Settings.Global.getFloat(
                contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)

    /** A brief style-swap glitch: flicker between the solid mark and its wire outline, with
     *  a little positional jitter, then settle on the solid fill. */
    private fun glitch(icon: ImageView) {
        if (reduceMotion) {
            icon.setImageResource(R.drawable.cr_crash_mark)
            icon.setColorFilter(pal.ink)
            return
        }
        val frames = listOf(true, false, true, false, false) // true = wire
        var i = 0
        fun step() {
            if (i >= frames.size) {
                icon.setImageResource(R.drawable.cr_crash_mark)
                icon.setColorFilter(pal.ink)
                icon.translationX = 0f
                icon.translationY = 0f
                return
            }
            icon.setImageResource(if (frames[i]) R.drawable.cr_crash_mark_wire else R.drawable.cr_crash_mark)
            icon.setColorFilter(pal.ink)
            icon.translationX = ((Math.random() - 0.5) * 7).toFloat()
            icon.translationY = ((Math.random() - 0.5) * 3.5).toFloat()
            i++
            icon.postDelayed({ step() }, if (i == frames.size) 100 else (55 + Math.random() * 45).toLong())
        }
        step()
    }

    private fun startGlitchLoop(icon: ImageView) {
        if (reduceMotion) return
        icon.postDelayed({ glitch(icon) }, 450)
    }

    // ---------------- tiny view-builder helpers (no XML, no external UI dependency) ----------------

    private fun buildToast(): TextView =
        TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(pal.paper)
            typeface = Typeface.create(typeface, Typeface.BOLD)
            setPadding(18.dp, 10.dp, 18.dp, 10.dp)
            background = filled(pal.ink, 999)
            alpha = 0f
        }

    private fun showToast(message: String) {
        // Prefer the in-screen pill; fall back to a platform Toast if something's off.
        runCatching {
            toast.text = message
            toast.animate().alpha(1f).setDuration(200).withEndAction {
                toast.postDelayed({ toast.animate().alpha(0f).setDuration(250).start() }, 1600)
            }.start()
        }.onFailure { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    private fun text(value: CharSequence, size: Float, color: Int, bold: Boolean = false, monospace: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            if (monospace) typeface = Typeface.MONOSPACE
        }

    private fun centered(child: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            (child as? TextView)?.gravity = Gravity.CENTER
            addView(child)
        }

    private fun pillButton(label: String, bg: Int, fg: Int, filled: Boolean, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setAllCaps(false)
            setTextColor(fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create(typeface, Typeface.BOLD)
            background = if (filled) {
                filled(bg, 999)
            } else {
                GradientDrawable().apply {
                    setColor(pal.paper)
                    setStroke(2.dp, pal.line)
                    cornerRadius = 999.dp.toFloat()
                }
            }
            setPadding(16.dp, 12.dp, 16.dp, 12.dp)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            setOnClickListener { onClick() }
        }

    private fun View.withTopMargin(dp: Int): View = apply {
        val lp = (layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp.dp
        layoutParams = lp
    }

    private fun appendBold(sb: SpannableStringBuilder, s: String) {
        val start = sb.length
        sb.append(s)
        sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun filled(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = radiusDp.dp.toFloat() }

    private fun outline(stroke: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(0x00000000)
            setStroke(1.dp, stroke)
            cornerRadius = radiusDp.dp.toFloat()
        }

    private fun dashed(stroke: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(0x00000000)
            setStroke(1.dp, stroke, 6.dp.toFloat(), 4.dp.toFloat())
            cornerRadius = radiusDp.dp.toFloat()
        }

    /** A 1dp hairline divider row — used between table rows and above the action bar. */
    private fun divider(): View = View(this).apply {
        setBackgroundColor(pal.line)
        layoutParams = LinearLayout.LayoutParams(MATCH, Math.max(1, 1.dp))
    }

    private val Int.dp: Int
        get() = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, this.toFloat(), resources.displayMetrics).toInt()

    private fun spacer(heightDp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, heightDp.dp)
    }

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val REQ_SAVE = 4471

        /** Offer the data-wiping reset only once a crash has recurred (Continue already failed). */
        private const val REPEAT_THRESHOLD = 2

        private const val EXTRA_APP_LABEL = "dev.aarso.crashrecovery.APP_LABEL"
        private const val EXTRA_STYLE = "dev.aarso.crashrecovery.STYLE"
        private const val EXTRA_CONTACT_EMAIL = "dev.aarso.crashrecovery.CONTACT_EMAIL"
        private const val EXTRA_PREVIEW = "dev.aarso.crashrecovery.PREVIEW"

        fun intent(
            context: Context,
            appLabel: String,
            style: CrashRecoveryStyle = CrashRecoveryStyle.Default,
            contactEmail: String? = null,
            preview: Boolean = false,
        ): Intent =
            Intent(context, CrashRecoveryActivity::class.java).apply {
                putExtra(EXTRA_APP_LABEL, appLabel)
                putExtra(EXTRA_STYLE, style)
                if (contactEmail != null) putExtra(EXTRA_CONTACT_EMAIL, contactEmail)
                if (preview) putExtra(EXTRA_PREVIEW, true)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}
