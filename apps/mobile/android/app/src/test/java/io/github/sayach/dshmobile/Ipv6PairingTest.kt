package io.github.sayach.dshmobile

import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Exercise the actual App-private CA consumer for domain and IPv6 pairing links. */
class Ipv6PairingTest {
    private val fixture = JSONObject(javaClass.classLoader!!.getResource("ipv6-tls-fixture.json")!!.readText())
    private val ca = Base64.getDecoder().decode(fixture.getString("ca"))
    private val leaf = CertificateFactory.getInstance("X.509")
        .generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(fixture.getString("leaf")))) as X509Certificate
    private val key = "dsh2.${fixture.getString("fingerprint")}.${"A".repeat(43)}"

    @Test
    fun scanningOrPastingADomainLinkSelectsItsCaWithoutSystemInstallation() {
        val link = "https://phone.example.com:3445/mobile-access/pair#key=$key"
        val connection = GatewayConnection.parse(link)!!
        assertTrue(RemoteHostPolicy.isAllowed(AccessMode.REMOTE, connection.origin.host))
        val pairingKey = GatewayUrlPolicy.pairingKey(link)!!
        assertTrue(pairingKey.requiresCa)
        val selected = PairingTrust.selectTrustAnchor(AccessMode.REMOTE, pairingKey) { ca }!!
        assertArrayEquals(ca, selected.first)
        assertNotNull(PinnedTls.socketFactory(selected.first!!))
        assertTrue(PinnedTls.acceptsWebViewLeaf(connection.origin, selected.first!!, leaf))
    }

    @Test
    fun aLiteralIpv6LinkRetainsItsPortAndUsesTheSamePrivateCa() {
        val link = "https://[2606:4700::1111]:3445/mobile-access/pair#key=$key"
        val connection = GatewayConnection.parse(link)!!
        assertEquals("https://[2606:4700::1111]:3445", connection.origin.serialized)
        assertTrue(RemoteHostPolicy.isAllowed(AccessMode.REMOTE, connection.origin.host))
        assertTrue(PinnedTls.acceptsWebViewLeaf(connection.origin, ca, leaf))
        assertEquals(connection.origin, GatewayOrigin.parse("https://[2606:4700:0:0:0:0:0:1111]:3445"))
        assertTrue(GatewayUrlPolicy.isSameOrigin(connection.origin, "https://[2606:4700:0:0:0:0:0:1111]:3445/"))
        assertFalse(GatewayUrlPolicy.isSameOrigin(connection.origin, "https://[2606:4700::1111]:8443/"))
    }

    @Test
    fun absentOrSubstitutedCaAndAnUnrelatedHostAreRejected() {
        val parsed = PairingKey.parse(key)!!
        assertNull(PairingTrust.selectTrustAnchor(AccessMode.REMOTE, parsed) { null })
        assertNull(PairingTrust.selectTrustAnchor(AccessMode.REMOTE, parsed) { TestCertificates.foreignCa })
        assertFalse(PinnedTls.acceptsWebViewLeaf(GatewayOrigin.parse("https://other.example.com:3445")!!, ca, leaf))
        assertFalse(PinnedTls.acceptsWebViewLeaf(GatewayOrigin.parse("https://[2606:4700::1001]:3445")!!, ca, leaf))
        assertFalse(PinnedTls.acceptsWebViewLeaf(GatewayOrigin.parse("https://phone.example.com:3445")!!, TestCertificates.foreignCa, leaf))
    }
}
