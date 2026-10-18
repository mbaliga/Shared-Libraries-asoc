# `word-graph` — the word-relationship graph, as a shared **asset** package

An offline, 2D ego-network view of a word: pick a headword, see its synonyms,
antonyms, hypernyms and friends as a force-directed graph you can pan, zoom and
walk. Rendered with [AntV G6](https://g6.antv.antgroup.com/) v5, vendored, over a
WordNet-derived edge list.

```
word-graph/
  assets/
    graph.html              the whole UI — markup, CSS and JS in one file
    vendor/g6.min.js        AntV G6 v5.1.1, vendored (no network at runtime)
    graph/nodes.tsv         id → headword + part of speech
    graph/edges.tsv         relation triples (synonym / antonym / hypernym / …)
    graph/words.tsv         headword → node id lookup
```

## This is deliberately NOT a Gradle module

Every other module here (`:search-core`, `:crash-recovery`, `:cell-shell`) is a
Gradle project published as a `dev.aarso` coordinate and consumed through
`includeBuild`. This one is a **plain directory of assets** with no
`build.gradle.kts`, and it is deliberately **not** in `settings.gradle.kts`.

The reason is the AGP lockstep documented in the root README. An Android library
module puts its AGP version into every consumer's composite build graph, so all
participants must pin the *same* AGP or Gradle hard-fails with *"Using multiple
versions of the Android Gradle plugin … is not allowed"*. This repo is on **AGP
8.9.1**, and its Android consumers are aligned to that. Clackpad — the first
consumer of this package — is on **AGP 8.13.2** and cannot move: AGP 8.13 is the
floor for `compileSdk 36`, which Play requires for app updates from 2026-08-31.
Neither side can yield without breaking something real.

A package that ships no Gradle plugin never enters that graph, so the lockstep
simply does not apply and a consumer on any AGP can take it. The shared unit here
genuinely *is* the JavaScript and the data — the hosting is a dozen lines each
consumer already has to write for its own navigation and theming anyway — so
there is nothing to gain from wrapping it in a module and a hard version
constraint to lose.

## Consuming it

Add the repo as a submodule (same mechanism as the Gradle modules — see the root
README), then point your asset source set at this directory:

```kotlin
// app/build.gradle.kts
android {
    sourceSets["main"].assets.srcDir("../shared-libraries/word-graph/assets")
}
```

Android merges those assets into your APK, so `graph.html`, `vendor/g6.min.js`
and `graph/*.tsv` are then reachable through your `AssetManager` exactly as if
they lived in your own `src/main/assets`.

> **CI:** the submodule must actually be checked out or the assets silently go
> missing and the graph renders blank. `actions/checkout@v4` does **not** fetch
> submodules by default — set `submodules: recursive`.

## The host contract

`graph.html` cannot fetch its siblings directly: a modern WebView blocks a
`file://` page from reading another `file://` asset, and the page's origin is
opaque so CORS rejects it too. Every cross-file load therefore goes over a **fake
host**, which the hosting app answers from its own `AssetManager` in
`WebViewClient.shouldInterceptRequest`.

A host must serve these three, each with `Access-Control-Allow-Origin: *`:

| Request | Serve from | Notes |
|---|---|---|
| `https://graph.wordgraph.local/{nodes,edges,words}.tsv` | `graph/…` in this package | `text/plain`. Reject any other filename. |
| `https://vendor.wordgraph.local/g6.min.js` | `vendor/g6.min.js` in this package | `application/javascript`. |
| `https://dict.wordgraph.local/defs/{a-z}.txt` | **the host's own** gloss corpus | `text/plain`. See below. |

Two rules that are easy to get wrong:

- **Answer 404, don't return `null`,** for a bad path under a host you own.
  Returning `null` means "handle this normally", which sends the WebView off to
  do a real DNS lookup for a `.local` name that exists nowhere.
- **Set the CORS header on every response,** including the 404s. The page fetches
  the `.tsv` files and the defs shards with `fetch()`, against an opaque origin.

### `dict.wordgraph.local` is yours, not ours

The gloss shown when a node is opened comes from the **host's** dictionary
corpus, not from this package — one file per initial letter, `defs/a.txt` …
`defs/z.txt`. That is on purpose: an app that already ships a dictionary should
show *its* definitions here, so a word never means one thing on the graph screen
and something else on the dictionary screen. An app with no corpus can serve 404
for this host; nodes then render without glosses and everything else still works.

The format is the WordNet-derived one Clackpad's `Dict.kt` reads. If you are
starting from nothing, copy that.

### Opening the screen

`graph.html` reads its starting word from the query string:

```kotlin
val q = if (word.isNotEmpty()) "?word=" + Uri.encode(word) else ""
webView.loadUrl("file:///android_asset/graph.html$q")
```

With no `?word=`, the graph opens on its default landing view.

Clackpad's `GraphActivity` is a complete ~146-line reference host: a WebView, the
three interceptors above, and edge-to-edge inset padding.

## Licensing

The graph data and the vendored renderer are third-party. See
[NOTICE.md](NOTICE.md) — those notices are load-bearing and must be reproduced by
anything that ships these assets, including in the consuming app's own
attribution screen.
