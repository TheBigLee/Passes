package ch.bigli.passes.importing

import android.net.Uri
import java.net.URLDecoder

/**
 * Extracts the real pkpass download URL from a `walletpasses://import/<url-encoded https url>` link.
 * Returns null if the link has no encoded target or the decoded target is not an http(s) URL.
 */
fun walletPassesTargetUrl(uri: Uri): String? {
    val encoded = uri.encodedPath?.trimStart('/')?.takeIf { it.isNotBlank() } ?: return null
    val decoded = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull() ?: return null
    return decoded.takeIf { it.startsWith("http://") || it.startsWith("https://") }
}
