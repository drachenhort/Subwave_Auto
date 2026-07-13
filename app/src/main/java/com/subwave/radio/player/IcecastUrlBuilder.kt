package com.subwave.radio.player

import java.net.URI
import java.net.URISyntaxException

private const val DEFAULT_PORT = 7700
private const val STREAM_PATH = "/stream.mp3"

/**
 * Normalizes user-entered Icecast server addresses into a full stream URL.
 *
 * - Adds "http://" if no scheme was given.
 * - Adds port [DEFAULT_PORT] if the user didn't specify one.
 * - Always targets [STREAM_PATH], discarding any path the user typed.
 *
 * Examples:
 *   "example.com"              -> "http://example.com:7700/stream.mp3"
 *   "example.com:8000"         -> "http://example.com:8000/stream.mp3"
 *   "https://example.com"      -> "https://example.com:7700/stream.mp3"
 *   "http://example.com:7700/live" -> "http://example.com:7700/stream.mp3"
 */
fun buildIcecastStreamUrl(rawInput: String): String {
    var input = rawInput.trim()

    if (!input.startsWith("http://") && !input.startsWith("https://")) {
        input = "http://$input"
    }

    val uri = try {
        URI(input)
    } catch (e: URISyntaxException) {
        throw IllegalArgumentException("Invalid server address: $rawInput", e)
    }

    val scheme = uri.scheme ?: "http"
    val host = uri.host
        ?: throw IllegalArgumentException("Could not parse host from: $rawInput")
    val port = if (uri.port != -1) uri.port else DEFAULT_PORT

    return "$scheme://$host:$port$STREAM_PATH"
}
