package com.excellanto.mycrmsync.data.api

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Builds a plaintext / markdown-friendly report from a completed `GET /v2/transcript/{id}` body.
 * Sections use pipe tables; **speaker diarization is always last**.
 */
object AssemblyTranscriptTableFormatter {

    fun format(completed: JSONObject): String {
        return buildString {
            val modelUsed = completed.optString("speech_model_used").trim()
            if (modelUsed.isNotEmpty()) {
                appendLine("Transcript engine: $modelUsed")
                appendLine()
            }

            val fullText = completed.optString("text").trim()
            appendLine("### Full transcript")
            appendLine()
            appendLine(if (fullText.isNotEmpty()) fullText else "(empty)")
            appendLine()

            appendSentimentTable(completed.optJSONArray("sentiment_analysis_results"))
            appendChaptersTable(completed.optJSONArray("chapters"))
            appendEntitiesTable(completed.optJSONArray("entities"))
            appendIabTables(completed.optJSONObject("iab_categories_result"))
            appendHighlightsTable(completed.optJSONObject("auto_highlights_result"))

            appendLine("### Speaker diarization (utterances)")
            appendLine()
            appendUtterancesTable(completed.optJSONArray("utterances"))
        }
    }

    private fun StringBuilder.appendSentimentTable(arr: JSONArray?) {
        appendLine("### Sentiment analysis")
        appendLine()
        if (arr == null || arr.length() == 0) {
            appendLine("_No sentiment rows._")
            appendLine()
            return
        }
        appendLine("| Start | End | Speaker | Sentiment | Confidence | Text |")
        appendLine("| :--- | :--- | :--- | :--- | :--- | :--- |")
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            append("| ")
            append(msCell(o.optInt("start")))
            append(" | ")
            append(msCell(o.optInt("end")))
            append(" | ")
            append(cell(o.optString("speaker").ifEmpty { "—" }))
            append(" | ")
            append(cell(o.optString("sentiment")))
            append(" | ")
            append(confidenceCell(o.optDouble("confidence")))
            append(" | ")
            append(cell(o.optString("text")))
            append(" |")
            appendLine()
        }
        appendLine()
    }

    private fun StringBuilder.appendChaptersTable(arr: JSONArray?) {
        appendLine("### Auto chapters")
        appendLine()
        if (arr == null || arr.length() == 0) {
            appendLine("_No chapters._")
            appendLine()
            return
        }
        appendLine("| Start | End | Gist | Headline | Summary |")
        appendLine("| :--- | :--- | :--- | :--- | :--- |")
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            append("| ")
            append(msCell(o.optInt("start")))
            append(" | ")
            append(msCell(o.optInt("end")))
            append(" | ")
            append(cell(o.optString("gist")))
            append(" | ")
            append(cell(o.optString("headline")))
            append(" | ")
            append(cell(o.optString("summary")))
            append(" |")
            appendLine()
        }
        appendLine()
    }

    private fun StringBuilder.appendEntitiesTable(arr: JSONArray?) {
        appendLine("### Entity detection")
        appendLine()
        if (arr == null || arr.length() == 0) {
            appendLine("_No entities._")
            appendLine()
            return
        }
        appendLine("| Type | Text | Start | End |")
        appendLine("| :--- | :--- | :--- | :--- |")
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val typeStr =
                when (val t = o.opt("entity_type")) {
                    null, JSONObject.NULL -> "—"
                    is String -> t
                    is JSONObject -> t.optString("type").ifEmpty { t.optString("entity_type") }.ifEmpty { t.toString() }
                    else -> t.toString()
                }
            append("| ")
            append(cell(typeStr))
            append(" | ")
            append(cell(o.optString("text")))
            append(" | ")
            append(msCell(o.optInt("start")))
            append(" | ")
            append(msCell(o.optInt("end")))
            append(" |")
            appendLine()
        }
        appendLine()
    }

    private fun StringBuilder.appendIabTables(root: JSONObject?) {
        appendLine("### IAB / topic detection")
        appendLine()
        if (root == null) {
            appendLine("_No topic detection result._")
            appendLine()
            return
        }
        val summary = root.optJSONObject("summary")
        if (summary != null && summary.length() > 0) {
            appendLine("**Topic relevance (summary)**")
            appendLine()
            appendLine("| Topic | Relevance |")
            appendLine("| :--- | :--- |")
            val it = summary.keys()
            while (it.hasNext()) {
                val key = it.next()
                val v = summary.optDouble(key, Double.NaN)
                append("| ")
                append(cell(key))
                append(" | ")
                append(
                    when {
                        v.isNaN() -> "—"
                        else -> String.format("%.4f", v)
                    },
                )
                append(" |")
                appendLine()
            }
            appendLine()
        }
        val results = root.optJSONArray("results")
        if (results == null || results.length() == 0) {
            appendLine("_No per-segment topic hits._")
            appendLine()
            return
        }
        appendLine("| Transcript excerpt | Topics (label / relevance) |")
        appendLine("| :--- | :--- |")
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            val labels = o.optJSONArray("labels")
            val labelLines =
                buildString {
                    if (labels == null || labels.length() == 0) {
                        append("—")
                    } else {
                        for (j in 0 until labels.length()) {
                            val lb = labels.optJSONObject(j) ?: continue
                            if (j > 0) append("; ")
                            append(lb.optString("label"))
                            append(" (")
                            append(String.format("%.3f", lb.optDouble("relevance")))
                            append(")")
                        }
                    }
                }
            append("| ")
            append(cell(o.optString("text")))
            append(" | ")
            append(cell(labelLines))
            append(" |")
            appendLine()
        }
        appendLine()
    }

    private fun StringBuilder.appendHighlightsTable(root: JSONObject?) {
        appendLine("### Key phrases (auto highlights)")
        appendLine()
        if (root == null) {
            appendLine("_No highlights._")
            appendLine()
            return
        }
        val arr = root.optJSONArray("results")
        if (arr == null || arr.length() == 0) {
            appendLine("_No highlights._")
            appendLine()
            return
        }
        appendLine("| Phrase | Count | Rank | Sample times (ms) |")
        appendLine("| :--- | :--- | :--- | :--- |")
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val ts = o.optJSONArray("timestamps")
            val times =
                buildString {
                    if (ts != null && ts.length() > 0) {
                        val max = minOf(4, ts.length())
                        for (j in 0 until max) {
                            if (j > 0) append(", ")
                            val t = ts.optJSONObject(j)
                            if (t != null) {
                                append(t.optInt("start"))
                            }
                        }
                        if (ts.length() > max) append("…")
                    } else {
                        append("—")
                    }
                }
            append("| ")
            append(cell(o.optString("text")))
            append(" | ")
            append(o.optInt("count"))
            append(" | ")
            append(String.format("%.4f", o.optDouble("rank")))
            append(" | ")
            append(cell(times))
            append(" |")
            appendLine()
        }
        appendLine()
    }

    private fun StringBuilder.appendUtterancesTable(arr: JSONArray?) {
        if (arr == null || arr.length() == 0) {
            appendLine("_No utterances (enable speaker_labels or check audio)._")
            return
        }
        appendLine("| Start | End | Speaker | Confidence | Text |")
        appendLine("| :--- | :--- | :--- | :--- | :--- |")
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            append("| ")
            append(msCell(o.optInt("start")))
            append(" | ")
            append(msCell(o.optInt("end")))
            append(" | ")
            append(cell(o.optString("speaker").ifEmpty { "?" }))
            append(" | ")
            append(confidenceCell(o.optDouble("confidence")))
            append(" | ")
            append(cell(o.optString("text")))
            append(" |")
            appendLine()
        }
    }

    private fun msCell(ms: Int): String = "${ms} ms (${clock(ms)})"

    private fun clock(ms: Int): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format("%d:%02d", m, s)
    }

    private fun confidenceCell(v: Double): String =
        when {
            v.isNaN() -> "—"
            else -> "${(v * 1000).roundToInt() / 10.0}%"
        }

    private fun cell(raw: String): String {
        val t =
            raw
                .replace("\r\n", " ")
                .replace("\n", " ")
                .replace("|", "¦")
                .trim()
        return if (t.length > 400) t.take(397) + "…" else t
    }
}
