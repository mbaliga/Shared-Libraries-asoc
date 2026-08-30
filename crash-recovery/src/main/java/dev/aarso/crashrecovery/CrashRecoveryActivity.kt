package dev.aarso.crashrecovery

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
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
    private var hasQuarantine = false

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
        hasQuarantine = if (preview) true else CrashRecovery.hasRecoverableData(this)

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
        applySystemBarInsets(root)
        paneMain = buildMainPane()
        paneDetails = buildDetailsPane()
        toast = buildToast()

        root.addView(paneMain, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(paneDetails, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(
            toast,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = Look.Space.XL.dp
            },
        )

        // Details starts off-screen to the right; corrected once we know the width.
        paneDetails.post { paneDetails.translationX = root.width.toFloat() }
        return root
    }

    /**
     * Inset the whole screen out from under the system bars.
     *
     * This Activity draws on a bare `Theme.NoTitleBar` and previously handled insets
     * nowhere at all — so on a modern device, where the window extends edge to edge, the
     * status bar sat on top of the crash mark and clipped it. A recovery screen that looks
     * broken undermines the one job it has: being the calm, trustworthy thing a user meets
     * right after a crash.
     *
     * Deliberately the **framework** inset API, not `androidx.core`'s `ViewCompat`: this
     * module has zero dependencies on purpose, so that nothing it needs can itself be the
     * thing that is broken when it runs. One `@Suppress("DEPRECATION")` on the pre-30 path
     * is a cheap price for keeping that guarantee.
     *
     * Padding goes on the root rather than each pane, so the details pane, the toast and
     * anything added later inherit it for free; and it is applied on every dispatch, so a
     * rotation or a switch to gesture navigation is followed rather than baked in once.
     */
    @Suppress("DEPRECATION")
    private fun applySystemBarInsets(target: View) {
        target.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars() or
                        android.view.WindowInsets.Type.displayCutout(),
                )
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                view.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
            }
            insets
        }
        target.requestApplyInsets()
    }

    // ---------------- main pane ----------------

    private fun buildMainPane(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.paper)
            setPadding(Look.Space.GUTTER.dp, Look.Space.S.dp, Look.Space.GUTTER.dp, Look.Space.L.dp)
        }

        // Crash mark — centered, tappable to replay the glitch.
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.cr_crash_mark)
            setColorFilter(pal.ink)
            layoutParams = LinearLayout.LayoutParams(Look.Mark.WIDTH.dp, Look.Mark.HEIGHT.dp).apply {
                topMargin = Look.Space.L.dp
                bottomMargin = Look.Space.M.dp
                gravity = Gravity.CENTER_HORIZONTAL
            }
            isClickable = true
            contentDescription = "Replay the crash animation"
            setOnClickListener { glitch(this) }
        }
        col.addView(icon)
        startGlitchLoop(icon)

        col.addView(centered(text("Well, that happened.", Look.Type.DISPLAY, pal.ink, bold = true)))
        col.addView(
            centered(
                text(
                    "$appLabel hit a snag last time and had to close. Sorry about that — " +
                        "here's exactly what happened.",
                    Look.Type.SECONDARY,
                    pal.inkSoft,
                ).apply {
                    // A measure cap, not a width: long lines are the fastest way to make calm
                    // copy read as a wall of text.
                    maxWidth = 300.dp
                    setLineSpacing(0f, Look.Type.LEADING_BODY)
                },
            ).apply { (getChildAt(0) as TextView).gravity = Gravity.CENTER }
                .withTopMargin(Look.Space.XS),
        )
        col.addView(privacyCard().withTopMargin(Look.Space.XL))
        col.addView(whatCard().withTopMargin(Look.Space.S))

        col.addView(
            pillButton("Continue to $appLabel", pal.accent, pal.onAccent, filled = true) { continueToApp() }
                .withTopMargin(Look.Space.XL),
        )
        col.addView(
            pillButton("View the full report", pal.ink, pal.paper, filled = false) { showDetails() }
                .withTopMargin(Look.Space.S),
        )

        col.addView(shareRow(includeCopy = false).withTopMargin(Look.Space.S))

        col.addView(
            text("Discard this report", Look.Type.CAPTION, pal.inkSoft).apply {
                gravity = Gravity.CENTER
                paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                isClickable = true
                // A real touch target rather than a 12sp line of text: this is a destructive
                // action and a mis-tap on it is not recoverable.
                setPadding(0, Look.Space.L.dp, 0, Look.Space.S.dp)
                background = pressable(null, pal.ink)
                setOnClickListener { discard() }
            }.withTopMargin(Look.Space.XS),
        )

        staggerIn(col)

        return ScrollView(this).apply {
            setBackgroundColor(pal.paper)
            isFillViewport = true
            addView(col)
        }
    }

    /**
     * The main pane's rows fade and rise into place, one just behind the last.
     *
     * The screen appears at the moment an app has just died, and appearing *instantly and whole*
     * makes it read as another abrupt event. A short staggered settle — the same eased curve
     * everything else on this screen moves on — makes it read as a screen that arrived on
     * purpose. It is skipped entirely when the user has animations turned off; someone who has
     * asked for no motion is not asking less insistently on a crash screen.
     */
    private fun staggerIn(container: LinearLayout) {
        if (reduceMotion) return
        val rise = Look.Motion.ENTER_RISE_DP.dp.toFloat()
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            child.alpha = 0f
            child.translationY = rise
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(Look.Motion.ENTER_MS)
                .setStartDelay(
                    minOf(i, Look.Motion.ENTER_MAX_STAGGERED) * Look.Motion.ENTER_STAGGER_MS,
                )
                .setInterpolator(settle)
                .start()
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
            setPadding(Look.Space.M.dp, Look.Space.M.dp, Look.Space.M.dp, Look.Space.M.dp)
            background = outline(pal.line, Look.Radius.CARD)
            addView(text("🔒", Look.Type.SECONDARY, pal.ink).apply {
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                    topMargin = Look.Space.HAIR.dp
                    rightMargin = Look.Space.M.dp
                }
            })
            addView(TextView(this@CrashRecoveryActivity).apply {
                setText(body, TextView.BufferType.SPANNABLE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, Look.Type.CAPTION)
                setTextColor(pal.inkSoft)
                setLineSpacing(0f, Look.Type.LEADING_BODY)
            })
        }
    }

    private fun whatCard(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Look.Space.M.dp, Look.Space.M.dp, Look.Space.M.dp, Look.Space.M.dp)
            background = filled(pal.card, Look.Radius.CARD)
            addView(eyebrow("What happened"))
            addView(spacer(Look.Space.XS))
            addView(
                text(report.plainLanguage, Look.Type.BODY, pal.ink)
                    .apply { setLineSpacing(0f, Look.Type.LEADING_BODY) },
            )
            val meta = listOfNotNull(
                report.whenShort().takeIf { it.isNotBlank() },
                "$appLabel ${report.versionLabel() ?: ""}".trim(),
            ).joinToString("  ·  ")
            addView(spacer(Look.Space.S))
            addView(text(meta, Look.Type.CAPTION, pal.inkSoft))
        }

    /** An all-caps section eyebrow. One role, one look, wherever it appears. */
    private fun eyebrow(title: String): TextView =
        text(title.uppercase(), Look.Type.EYEBROW, pal.inkSoft, bold = true).apply {
            letterSpacing = Look.Type.EYEBROW_TRACKING
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
                setPadding(Look.Space.M.dp, Look.Space.M.dp, Look.Space.M.dp, Look.Space.S.dp)
                addView(text("‹  Back", Look.Type.BODY, pal.ink, bold = true).apply {
                    isClickable = true
                    setPadding(Look.Space.M.dp, Look.Space.S.dp, Look.Space.M.dp, Look.Space.S.dp)
                    background = pressable(null, pal.ink)
                    setOnClickListener { showMain() }
                })
            },
        )

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Look.Space.GUTTER.dp, 0, Look.Space.GUTTER.dp, Look.Space.L.dp)
        }
        col.addView(text("The full report", Look.Type.TITLE, pal.ink, bold = true))
        col.addView(spacer(Look.Space.S))
        col.addView(
            text(
                "Everything below is the entire report — if you share it, this is all that " +
                    "leaves your phone, word for word.",
                Look.Type.SECONDARY,
                pal.inkSoft,
            ).apply { setLineSpacing(0f, Look.Type.LEADING_BODY) },
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
                background = filled(pal.monoBg, Look.Radius.FIELD)
                layoutParams = LinearLayout.LayoutParams(MATCH, 220.dp)
                addView(HorizontalScrollView(this@CrashRecoveryActivity).apply {
                    addView(text(report.trace, Look.Type.MONO, pal.monoInk, monospace = true).apply {
                        setPadding(Look.Space.M.dp, Look.Space.M.dp, Look.Space.M.dp, Look.Space.M.dp)
                        setLineSpacing(0f, Look.Type.LEADING_BODY)
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
                setTextSize(TypedValue.COMPLEX_UNIT_SP, Look.Type.SECONDARY)
                setTextColor(pal.inkSoft)
                setLineSpacing(0f, Look.Type.LEADING_BODY)
                setPadding(Look.Space.M.dp, Look.Space.M.dp, Look.Space.M.dp, Look.Space.M.dp)
                background = dashed(pal.line, Look.Radius.FIELD)
            },
        )

        // Loop-gated recovery — offered once the crash has actually recurred (Continue already
        // failed), or whenever set-aside data is waiting to be restored. Kept in the full report,
        // never on the calm main screen. The reset here is non-destructive (data is moved aside),
        // so there is no data-wiping footgun to guard against.
        if (consecutive >= REPEAT_THRESHOLD || hasQuarantine) col.addView(recoverySection())

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
                    setPadding(
                        Look.Space.GUTTER.dp, Look.Space.M.dp, Look.Space.GUTTER.dp, Look.Space.L.dp,
                    )
                    addView(shareRow(includeCopy = true))
                })
            },
        )

        return outer
    }

    /**
     * Shown after a repeat crash, or whenever set-aside data is waiting to be restored. Offers,
     * in order of safety: save a copy off-device (if the host provided a salvager), reset by
     * moving data ASIDE (never deleting it), and restore data set aside by an earlier reset.
     * There is deliberately no "erase everything" here — a bare wipe is never the answer.
     */
    private fun recoverySection(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = Look.Space.XL.dp }
            addView(sectionHeader("Still happening?"))
            addView(
                text(
                    "$appLabel has crashed more than once in a row, so continuing may just hit the " +
                        "same problem. You can reset it to a clean start — your data isn't deleted, " +
                        "it's set aside, and you can put it back at any point before $appLabel is " +
                        "working again.",
                    Look.Type.SECONDARY,
                    pal.inkSoft,
                ).apply { setLineSpacing(0f, Look.Type.LEADING_BODY) },
            )

            // 1) Save a portable copy off-device — the real safety net for data-heavy apps.
            val salv = CrashRecovery.salvager()
            if (salv != null) {
                salv.describe(this@CrashRecoveryActivity)?.let { summary ->
                    addView(
                        text("Backup contains: $summary", Look.Type.EYEBROW, pal.inkSoft)
                            .apply { withTopMargin(Look.Space.M) },
                    )
                }
                addView(
                    pillButton("Save a copy of my data", pal.ink, pal.paper, filled = false) { exportData() }
                        .withTopMargin(Look.Space.M),
                )
            }

            // 2) Reset by setting data aside (non-destructive).
            addView(
                pillButton("Reset & set my data aside", pal.accent, pal.onAccent, filled = true) {
                    confirmQuarantineReset()
                }.withTopMargin(Look.Space.M),
            )

            // 3) Restore data set aside by an earlier reset (if any is waiting).
            if (hasQuarantine) {
                val size = CrashRecovery.recoverableSizeBytes(this@CrashRecoveryActivity)
                val label = if (size > 0) "Restore the data I set aside (${humanBytes(size)})" else "Restore the data I set aside"
                addView(
                    pillButton(label, pal.ink, pal.paper, filled = false) { restoreData() }
                        .withTopMargin(Look.Space.M),
                )
                if (salv?.canImport() == true) {
                    addView(
                        pillButton("Import a backup file", pal.ink, pal.paper, filled = false) { importData() }
                            .withTopMargin(Look.Space.S),
                    )
                }
            }
        }

    private fun confirmQuarantineReset() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Reset $appLabel?")
            .setMessage(
                "$appLabel will start fresh. Your existing data is moved aside — not deleted — so " +
                    "you can restore it right after if the reset doesn't help. It's cleared only " +
                    "once $appLabel is working again.",
            )
            .setPositiveButton("Reset") { _, _ -> performQuarantineReset() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performQuarantineReset() {
        // Inert in preview — previewing the screen must never touch real app data.
        if (preview) {
            showToast("Preview — no data was changed")
            return
        }
        val moved = CrashRecovery.quarantineAndReset(this)
        showToast(if (moved) "Data set aside — restarting clean" else "Restarting clean")
        paneMain.postDelayed({ relaunchApp() }, 500)
    }

    private fun restoreData() {
        if (preview) {
            showToast("Preview — nothing to restore")
            return
        }
        val ok = CrashRecovery.restoreQuarantine(this)
        showToast(if (ok) "Data restored — restarting" else "Nothing to restore")
        if (ok) paneMain.postDelayed({ relaunchApp() }, 500)
    }

    /** Stream the host salvager's backup to a user-picked file (survives any on-device reset). */
    private fun exportData() {
        if (preview || CrashRecovery.salvager() == null) return
        val name = "${packageName}-backup-${System.currentTimeMillis()}.bak"
        val create = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        runCatching { startActivityForResult(create, REQ_EXPORT) }
            .onFailure { showToast("No place to save the backup") }
    }

    /** Pick a backup file and hand its stream to the host salvager to restore. */
    private fun importData() {
        if (preview || CrashRecovery.salvager()?.canImport() != true) return
        val open = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        runCatching { startActivityForResult(open, REQ_IMPORT) }
            .onFailure { showToast("Couldn't open a file picker") }
    }

    private fun humanBytes(n: Long): String = when {
        n >= 1_000_000_000L -> String.format("%.1f GB", n / 1_000_000_000.0)
        n >= 1_000_000L -> String.format("%.0f MB", n / 1_000_000.0)
        n >= 1_000L -> "${n / 1000} KB"
        else -> "$n B"
    }

    private fun section(title: String, rows: List<Pair<String, String>>): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = Look.Space.L.dp }
            addView(sectionHeader(title))
            rows.forEachIndexed { i, (k, v) ->
                addView(kvRow(k, v))
                if (i != rows.lastIndex) addView(divider())
            }
        }

    private fun sectionHeader(title: String): View =
        eyebrow(title).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = Look.Space.L.dp
                bottomMargin = Look.Space.S.dp
            }
        }

    private fun kvRow(key: String, value: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Look.Space.M.dp, 0, Look.Space.M.dp)
            addView(text(key, Look.Type.SECONDARY, pal.inkSoft).apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 0.4f)
            })
            addView(text(value, Look.Type.SECONDARY, pal.ink).apply {
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
        (child.layoutParams as LinearLayout.LayoutParams).rightMargin = Look.Space.S.dp
    }

    private fun shareTile(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            bareButton()
            text = label
            setTextColor(pal.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Look.Type.CAPTION)
            typeface = Typeface.create(typeface, Typeface.BOLD)
            background = pressable(filled(pal.card, Look.Radius.FIELD), pal.ink, Look.Radius.FIELD)
            setPadding(Look.Space.XS.dp, Look.Space.M.dp, Look.Space.XS.dp, Look.Space.M.dp)
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
        relaunchApp()
    }

    /**
     * Relaunch the host app cleanly. The naive `startActivity(getLaunchIntentForPackage())`
     * + `finish()` failed in practice: [CrashRecovery.maybeShowRecovery] already finished the
     * launcher, so this recovery screen is the task's only activity; the launch intent's
     * `NEW_TASK` reuses that same task (shared affinity), and finishing here tears the task
     * down before the launcher appears — the app just closes. Build a proper restart task
     * (`NEW_TASK | CLEAR_TASK`) instead, then drop this process so the app comes up fresh
     * rather than layered on whatever the crash left half-initialized.
     */
    private fun relaunchApp() {
        val launch = runCatching { packageManager.getLaunchIntentForPackage(packageName) }.getOrNull()
        val component = launch?.component
        runCatching {
            when {
                component != null -> startActivity(Intent.makeRestartActivityTask(component))
                launch != null -> startActivity(
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                )
                // No resolvable launcher (rare) — nothing to relaunch; just leave.
            }
        }
        finish()
        // The launch is already queued with the system, so ending our own process here does not
        // cancel it — it guarantees the app restarts in a brand-new process.
        Runtime.getRuntime().exit(0)
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
        } else if (requestCode == REQ_EXPORT && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val salv = CrashRecovery.salvager() ?: return
            // Stream off the main thread — a real backup can be large.
            showToast("Saving a copy…")
            Thread {
                val ok = runCatching {
                    contentResolver.openOutputStream(uri)?.use { salv.exportTo(this, it) }
                }.isSuccess
                runOnUiThread { showToast(if (ok) "Backup saved" else "Couldn't save the backup") }
            }.start()
        } else if (requestCode == REQ_IMPORT && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val salv = CrashRecovery.salvager()?.takeIf { it.canImport() } ?: return
            showToast("Restoring from backup…")
            Thread {
                val ok = runCatching {
                    contentResolver.openInputStream(uri)?.use { salv.importFrom(this, it) }
                }.isSuccess
                runOnUiThread {
                    showToast(if (ok) "Backup restored — restarting" else "Couldn't read that backup")
                    if (ok) paneMain.postDelayed({ relaunchApp() }, 500)
                }
            }.start()
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

    /**
     * The constellation's settle curve, as a framework interpolator.
     *
     * Every animation on this screen runs on it — the pane slide, the entry stagger, the toast —
     * so the whole surface moves as one thing. See [Look.Motion] for why the numbers are
     * restated here rather than shared with `:cell-shell`.
     */
    private val settle by lazy {
        PathInterpolator(
            Look.Motion.EASE_X1, Look.Motion.EASE_Y1, Look.Motion.EASE_X2, Look.Motion.EASE_Y2,
        )
    }

    private fun showDetails() {
        val w = root.width.toFloat()
        // The main pane does not simply leave: it slides a short way and stays behind the
        // report, so the report reads as sitting on top of the screen you came from rather
        // than as a different screen you were sent to.
        paneMain.animate().translationX(-w * 0.28f)
            .setDuration(Look.Motion.SETTLE_MS).setInterpolator(settle).start()
        paneDetails.animate().translationX(0f)
            .setDuration(Look.Motion.SETTLE_MS).setInterpolator(settle).start()
    }

    private fun showMain() {
        val w = root.width.toFloat()
        paneMain.animate().translationX(0f)
            .setDuration(Look.Motion.SETTLE_MS).setInterpolator(settle).start()
        paneDetails.animate().translationX(w)
            .setDuration(Look.Motion.SETTLE_MS).setInterpolator(settle).start()
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Look.Type.SECONDARY)
            setTextColor(pal.paper)
            typeface = Typeface.create(typeface, Typeface.BOLD)
            setPadding(Look.Space.L.dp, Look.Space.M.dp, Look.Space.L.dp, Look.Space.M.dp)
            background = filled(pal.ink, Look.Radius.PILL)
            alpha = 0f
        }

    private fun showToast(message: String) {
        // Prefer the in-screen pill; fall back to a platform Toast if something's off.
        runCatching {
            toast.text = message
            toast.animate().alpha(1f).setDuration(200).setInterpolator(settle).withEndAction {
                toast.postDelayed({
                    toast.animate().alpha(0f).setDuration(250).setInterpolator(settle).start()
                }, 1600)
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

    /**
     * A pill button in one of two variants.
     *
     * [tint] is the button's ONE colour — the fill when [filled], the label and the stroke
     * when outlined — and [onTint] is only ever the label colour of a filled button. Naming
     * them for their role rather than "bg"/"fg" is the fix for a real defect: the previous
     * signature took `(bg, fg)`, ignored `bg` entirely in the outlined branch, hardcoded the
     * fill to [CrashRecoveryStyle.Palette.paper], and still painted the label `fg` — so
     * `pillButton("View the full report", pal.ink, pal.paper, filled = false)` rendered
     * paper-on-paper: an outlined pill with an invisible label, which is exactly the "empty
     * button" seen on device. The Reset button had been silently working around it by
     * re-setting its text colour after construction; that workaround is now gone too.
     */
    private fun pillButton(label: String, tint: Int, onTint: Int, filled: Boolean, onClick: () -> Unit): Button =
        Button(this).apply {
            bareButton()
            text = label
            val labelColor = PillColors.label(tint, onTint, filled)
            setTextColor(labelColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Look.Type.BODY)
            typeface = Typeface.create(typeface, Typeface.BOLD)
            val shape = GradientDrawable().apply {
                setColor(PillColors.fill(tint, pal.paper, filled))
                // Outlined buttons carry their own tint in the stroke, so the variant reads as
                // the same control in a quieter register rather than as a different one.
                PillColors.stroke(tint, filled)?.let { setStroke(Look.Space.HAIR.dp, it) }
                cornerRadius = Look.Radius.PILL.dp.toFloat()
            }
            background = pressable(shape, labelColor, Look.Radius.PILL)
            setPadding(Look.Space.L.dp, Look.Space.L.dp, Look.Space.L.dp, Look.Space.L.dp)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            setOnClickListener { onClick() }
        }

    /**
     * Strip a platform [Button] back to something this screen can style.
     *
     * `Button` arrives with a themed background, a minimum width and height, a caps transform
     * and — on most themes — a state-list animator that lifts it on press. Left in place those
     * fight every decision made here: the elevation shadow lands on a flat paper surface, and
     * the minimums mean the padding set at the call site is not the padding you get. Clearing
     * them is what makes the buttons on this screen actually the size and shape they say.
     */
    private fun Button.bareButton() {
        setAllCaps(false)
        stateListAnimator = null
        elevation = 0f
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
    }

    /**
     * Wrap a shape so it answers a touch.
     *
     * Nothing on this screen used to respond to a press at all: the backgrounds were plain
     * [GradientDrawable]s, so a tap on Continue gave no feedback until the app relaunched, which
     * on a slow cold start reads as a button that did not work — on the one screen where the
     * user is already primed to believe nothing works.
     *
     * [RippleDrawable] is `android.graphics.drawable`, so this stays inside the module's
     * zero-dependency rule. The highlight is derived from the control's own colour at
     * [Look.RIPPLE_ALPHA] rather than from a fixed grey, so it lands correctly on a filled
     * accent pill and on a paper-backed outlined one alike, whatever accent the host passed.
     *
     * @param content the shape to draw under the ripple; null for a borderless target (a link,
     *   the Back affordance) where a shape would invent a button that is not there.
     * @param radiusDp the corner radius the ripple is clipped to. Must match [content]'s own
     *   radius or the highlight will square off the corners of a pill. Null leaves the ripple
     *   unbounded, which is what a borderless target wants. A separate mask drawable is built
     *   rather than reusing [content]: a Drawable may have only one parent, and a
     *   [RippleDrawable] holds both its layers.
     */
    private fun pressable(content: Drawable?, source: Int, radiusDp: Int? = null): Drawable {
        val highlight = ColorStateList.valueOf(
            Color.argb(
                Look.RIPPLE_ALPHA, Color.red(source), Color.green(source), Color.blue(source),
            ),
        )
        val mask = radiusDp?.let { filled(Color.WHITE, it) }
        return RippleDrawable(highlight, content, mask)
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
        private const val REQ_EXPORT = 4472
        private const val REQ_IMPORT = 4473

        /** Offer the recovery affordances only once a crash has recurred (Continue already failed). */
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
