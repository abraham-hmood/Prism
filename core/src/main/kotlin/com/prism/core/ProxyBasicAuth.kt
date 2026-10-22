package com.prism.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/** Shared HTTP Basic authentication rules for Prism's proxy clients and server. */
object ProxyBasicAuth {
    fun authorizationHeader(username: String, password: String): String {
        val credentialBytes = (username + ":" + password).toByteArray(StandardCharsets.UTF_8)
        val token = Base64.getEncoder().encodeToString(credentialBytes)
        return "Proxy-Authorization: Basic $token"
    }

    /** Finds and validates a Proxy-Authorization header in an HTTP request head. */
    fun isAuthorized(requestHead: String, username: String, password: String): Boolean {
        val supplied = requestHead.lineSequence()
            .map { it.trim() }
            .firstNotNullOfOrNull { line ->
                val colon = line.indexOf(':')
                if (colon < 0 || !line.substring(0, colon).trim().equals("Proxy-Authorization", true)) {
                    null
                } else {
                    line.substring(colon + 1).trim()
                }
            } ?: return false

        val schemeSeparator = supplied.indexOfFirst { it.isWhitespace() }
        if (schemeSeparator <= 0 || !supplied.substring(0, schemeSeparator).equals("Basic", true)) {
            return false
        }
        val encoded = supplied.substring(schemeSeparator).trim()
        val decoded = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return false
        val expected = (username + ":" + password).toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.isEqual(expected, decoded)
    }
}
