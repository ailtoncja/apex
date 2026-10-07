package app.apex.util

import java.security.MessageDigest

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun sha1Hex(text: String): String =
    MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
