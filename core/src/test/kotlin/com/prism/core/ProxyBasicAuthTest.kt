package com.prism.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProxyBasicAuthTest {
    @Test
    fun generatedHeaderAuthenticatesAgainstSameCredentials() {
        val header = ProxyBasicAuth.authorizationHeader("phone-user", "pass:word")

        assertTrue(ProxyBasicAuth.isAuthorized("CONNECT example:443 HTTP/1.1\r\n$header\r\n", "phone-user", "pass:word"))
        assertFalse(ProxyBasicAuth.isAuthorized("CONNECT example:443 HTTP/1.1\r\n$header\r\n", "phone-user", "wrong"))
    }

    @Test
    fun authorizationHeaderNameSchemeAndSpacingAreCaseInsensitive() {
        val canonical = ProxyBasicAuth.authorizationHeader("user", "secret")
        val token = canonical.substringAfter("Basic ")
        val request = "CONNECT example:443 HTTP/1.1\r\npRoXy-AuThOrIzAtIoN:   bAsIc   $token \r\n"

        assertTrue(ProxyBasicAuth.isAuthorized(request, "user", "secret"))
    }

    @Test
    fun utf8CredentialsRoundTripAcrossPlatforms() {
        val header = ProxyBasicAuth.authorizationHeader("téléphone", "päss🔐")

        assertTrue(ProxyBasicAuth.isAuthorized(header, "téléphone", "päss🔐"))
    }

    @Test
    fun missingMalformedOrWrongSchemeHeadersAreRejected() {
        assertFalse(ProxyBasicAuth.isAuthorized("CONNECT example:443 HTTP/1.1", "user", "secret"))
        assertFalse(ProxyBasicAuth.isAuthorized("Proxy-Authorization: Basic %%%", "user", "secret"))
        assertFalse(ProxyBasicAuth.isAuthorized("Proxy-Authorization: Bearer abc", "user", "secret"))
    }
}