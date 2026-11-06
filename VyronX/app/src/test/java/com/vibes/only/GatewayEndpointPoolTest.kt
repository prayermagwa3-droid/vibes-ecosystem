package com.vibes.only

import org.junit.Assert.*
import org.junit.Test

class GatewayEndpointPoolTest {
    @Test fun acceptsHttpsRootAndNormalizesTrailingSlash() {
        val parsed = GatewayEndpointPool.parsePublicEndpoint("https://vibesquantum.tech/")
        assertNotNull(parsed)
        assertEquals("https://vibesquantum.tech/", parsed.toString())
    }

    @Test fun rejectsCleartextAndUrlCredentials() {
        assertNull(GatewayEndpointPool.parsePublicEndpoint("http://vibesquantum.tech"))
        assertNull(GatewayEndpointPool.parsePublicEndpoint("https://user:pass@vibesquantum.tech"))
        assertNull(GatewayEndpointPool.parsePublicEndpoint("https://vibesquantum.tech/api/v1"))
    }
}
