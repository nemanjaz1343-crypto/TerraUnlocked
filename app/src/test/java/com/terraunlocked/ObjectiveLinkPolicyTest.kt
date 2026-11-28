package com.terraunlocked

import org.junit.Assert.*
import org.junit.Test

class ObjectiveLinkPolicyTest {
    @Test fun osmSourceLinkIsStableWithoutWebsite() {
        val o=ReconObjective("osm_way_12345","Example museum","ART",
            48.2,2.3,"Mapped museum","https://old-site.example.com/closed")
        assertEquals("https://www.openstreetmap.org/way/12345",
            ObjectiveLinkPolicy.stableSource(o))
        assertEquals("way" to 12345L,ObjectiveLinkPolicy.osmElementId(o.id))
    }
    @Test fun curatedSiteAlwaysHasReliableMapFallback() {
        val o=ReconObjective("world_test","Landmark","ARCH",41.0,12.0,
            "A landmark","https://example.com/old")
        val fallback=ObjectiveLinkPolicy.stableSource(o)
        assertTrue(fallback.startsWith("https://www.openstreetmap.org/?mlat=41.0&mlon=12.0"))
        assertNull(ObjectiveLinkPolicy.osmElementId(o.id))
    }
    @Test fun untrustedLinksAreNotOpenedAutomatically() {
        assertNull(ObjectiveLinkPolicy.safeWebsite("javascript:alert(1)"))
        assertNull(ObjectiveLinkPolicy.safeWebsite("http://example.com"))
        assertNull(ObjectiveLinkPolicy.safeWebsite("https://localhost/private"))
        assertEquals("https://example.org",
            ObjectiveLinkPolicy.safeWebsite("https://example.org"))
        assertNull(ObjectiveLinkPolicy.osmElementId("osm_way_foo"))
    }
}
