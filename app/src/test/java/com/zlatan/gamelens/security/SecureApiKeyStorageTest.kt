package com.zlatan.gamelens.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureApiKeyStorageTest {

    @Test
    fun testValidHttpsEndpoints() {
        assertTrue(SecureApiKeyStorage.isValidEndpointUrl("https://api.openai.com/v1").first)
        assertTrue(SecureApiKeyStorage.isValidEndpointUrl("https://custom-ai.company.org/v1").first)
        assertTrue(SecureApiKeyStorage.isValidEndpointUrl("https://localhost:8080/v1").first)
    }

    @Test
    fun testValidHttpPrivateAndLoopbackEndpoints() {
        // Loopback
        assertTrue(SecureApiKeyStorage.isValidEndpointUrl("http://localhost:8080/v1").first)
        assertTrue(SecureApiKeyStorage.isValidEndpointUrl("http://127.0.0.1:11434/v1").first)

        // Class A private network
        assertTrue(SecureApiKeyStorage.isValidEndpointUrl("http://10.0.0.5:8000/v1").first)

        // Class C private network
        assertTrue(SecureApiKeyStorage.isValidEndpointUrl("http://192.168.1.100:5000/v1").first)

        // Class B private network
        assertTrue(SecureApiKeyStorage.isValidEndpointUrl("http://172.16.0.2:8080/v1").first)
        assertTrue(SecureApiKeyStorage.isValidEndpointUrl("http://172.31.255.1:8080/v1").first)
    }

    @Test
    fun testBlockedInsecureHttpPublicEndpoints() {
        // Public internet over HTTP must be blocked to prevent API key leak in cleartext
        assertFalse(SecureApiKeyStorage.isValidEndpointUrl("http://api.openai.com/v1").first)
        assertFalse(SecureApiKeyStorage.isValidEndpointUrl("http://my-ai-proxy.com/v1").first)
        assertFalse(SecureApiKeyStorage.isValidEndpointUrl("http://8.8.8.8:8080/v1").first)
        assertFalse(SecureApiKeyStorage.isValidEndpointUrl("ftp://localhost:8080").first)
        assertFalse(SecureApiKeyStorage.isValidEndpointUrl("").first)
    }
}
