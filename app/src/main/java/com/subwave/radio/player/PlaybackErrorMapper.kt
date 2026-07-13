package com.subwave.radio.player

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource

/** Turns an ExoPlayer [PlaybackException] into a short, user-facing message. */
fun getReadableErrorMessage(error: PlaybackException): String {
    return when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "Couldn't reach the server. Check the address and your connection."

        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
            val statusCode = extractHttpStatusCode(error)
            when (statusCode) {
                404 -> "Stream not found at this address (404)."
                403 -> "Access denied by the server (403)."
                in 500..599 -> "Server error ($statusCode). Try again later."
                else -> "Server returned an error${if (statusCode != null) " ($statusCode)" else ""}."
            }
        }

        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            "Stream not found at this address."

        PlaybackException.ERROR_CODE_IO_UNSPECIFIED ->
            "Connection failed. Check the server address."

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
            "This doesn't look like a valid audio stream."

        PlaybackException.ERROR_CODE_TIMEOUT ->
            "Connection timed out."

        else ->
            "Couldn't connect: ${error.message ?: "unknown error"}"
    }
}

private fun extractHttpStatusCode(error: PlaybackException): Int? {
    var cause: Throwable? = error.cause
    while (cause != null) {
        if (cause is HttpDataSource.InvalidResponseCodeException) {
            return cause.responseCode
        }
        cause = cause.cause
    }
    return null
}
