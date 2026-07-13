package com.subwave.radio.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

data class LookupResult(val artworkUrl: String?, val year: Int?)

class TrackMetadataLookup(private val client: OkHttpClient = OkHttpClient()) {

    /** Looks up artwork + release year for a given artist/title via the iTunes Search API. */
    fun query(artist: String, title: String): LookupResult {
        val term = URLEncoder.encode("$artist $title", "UTF-8")
        val url = "https://itunes.apple.com/search?term=$term&limit=1&entity=song"
        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: return LookupResult(null, null)
            val json = JSONObject(body)
            val results = json.optJSONArray("results")
            if (results == null || results.length() == 0) return LookupResult(null, null)

            val result = results.getJSONObject(0)
            val artworkUrl = result.optString("artworkUrl100")
                .takeIf { it.isNotBlank() }
                ?.replace("100x100", "600x600")
            val releaseDate = result.optString("releaseDate") // e.g. "2024-03-15T00:00:00Z"
            val year = releaseDate.take(4).toIntOrNull()

            return LookupResult(artworkUrl, year)
        }
    }

    /** Splits a raw ICY "Artist - Title" string into its parts. */
    fun parseIcyString(raw: String): Pair<String, String> {
        val parts = raw.split(" - ", limit = 2)
        val artist = parts.getOrNull(0)?.trim() ?: "Unknown artist"
        val title = parts.getOrNull(1)?.trim() ?: raw.trim()
        return artist to title
    }
}
