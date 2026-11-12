package dev.aarso.viz

import org.json.JSONObject

/** The shared demo feed (src/test/resources/demo-feed.json): web/viz-kit/demo.html's example data as JSON. */
internal object Feed {
    private val root = JSONObject(Feed::class.java.getResourceAsStream("/demo-feed.json")!!.reader().readText())
    val builds = VizJson.data(root.getJSONArray("builds"))
    val storage = VizJson.data(root.getJSONArray("storage"))
    val time = VizJson.data(root.getJSONArray("time"))
    val repos = VizJson.data(root.getJSONArray("repos"))
    val stat = VizJson.stat(root.getJSONObject("stat"))
    val spark = VizJson.series(root.getJSONArray("spark"))
    val meter = VizJson.meter(root.getJSONObject("meter"))
    val commits = VizJson.series(root.getJSONObject("commits").getJSONArray("series"))
    val commitsOptions = VizJson.options(root.getJSONObject("commits").getJSONObject("options"))
    val hours = VizJson.matrix(root.getJSONObject("hours"))
}
