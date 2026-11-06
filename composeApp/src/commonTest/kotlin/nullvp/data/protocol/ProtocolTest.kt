package nullvp.data.protocol

import nullvp.domain.model.ProtocolType
import nullvp.domain.protocol.ProtocolRegistry
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ProtocolTest {

    @BeforeTest
    fun setup() {
        ProtocolRegistry.register(MdnsProtocolHandler())
        ProtocolRegistry.register(VkTurnProtocolHandler())
        ProtocolRegistry.register(VlessProtocolHandler())
        ProtocolRegistry.register(Hysteria2ProtocolHandler())
        ProtocolRegistry.register(VmessProtocolHandler())
        ProtocolRegistry.register(TrojanProtocolHandler())
        ProtocolRegistry.register(ShadowsocksProtocolHandler())
    }

    @Test
    fun testVlessParsing() {
        val link = "vless://11111111-2222-3333-4444-555555555555@vpn.example.com:443?security=reality&type=tcp#My%20VLESS%20Server"
        val profile = ProtocolRegistry.parse(link)

        assertNotNull(profile)
        assertEquals("My VLESS Server", profile.name)
        assertEquals("vpn.example.com", profile.serverAddress)
        assertEquals(443, profile.serverPort)
        assertEquals(ProtocolType.VLESS, profile.protocolType)
    }

    @Test
    fun testMdnsParsing() {
        val b64 = "eyJET01BSU5TIjpbImRucy5teXRlc3QuY29tIl0sIkVOQ1JZUFRJT05fS0VZIjoic2VjcmV0IiwiREFUQV9FTkNSWVBUSU9OX01FVEhPRCI6MSwiTElTVEVOX1BPUlQiOjE4MDAwfQ=="
        val link = "mdns://$b64#TestDNS"
        val profile = ProtocolRegistry.parse(link)

        assertNotNull(profile)
        assertEquals("TestDNS", profile.name)
        assertEquals("dns.mytest.com", profile.serverAddress)
        assertEquals(18000, profile.serverPort)
        assertEquals(ProtocolType.MASTER_DNS, profile.protocolType)
    }

    @Test
    fun testFreeturnParsing() {
        val b64url = "eyJ2IjoxLCJuYW1lIjoiRnJlZVR1cm4gTm9kZSIsInBlZXIiOiIxLjIuMy40OjQ0MyIsInZrIjoiaHR0cHM6Ly92ay5jb20vY2FsbC9qb2luL3Rlc3QifQ"
        val link = "freeturn://$b64url"
        val profile = ProtocolRegistry.parse(link)

        assertNotNull(profile)
        assertEquals("FreeTurn Node", profile.name)
        assertEquals("1.2.3.4", profile.serverAddress)
        assertEquals(443, profile.serverPort)
        assertEquals(ProtocolType.VK_TURN, profile.protocolType)
    }

    @Test
    fun testHysteria2Parsing() {
        val link = "hy2://secretPass123@hy2.myserver.com:8443/?sni=mycdn.com&insecure=1&obfs=salamander&obfs-password=pwd#My%20Hysteria2"
        val profile = ProtocolRegistry.parse(link)

        assertNotNull(profile)
        assertEquals("My Hysteria2", profile.name)
        assertEquals("hy2.myserver.com", profile.serverAddress)
        assertEquals(8443, profile.serverPort)
        assertEquals(ProtocolType.HYSTERIA2, profile.protocolType)
    }

    @Test
    fun testTrojanParsing() {
        val link = "trojan://password123@trojan.example.com:443?sni=example.com#My%20Trojan"
        val profile = ProtocolRegistry.parse(link)

        assertNotNull(profile)
        assertEquals("My Trojan", profile.name)
        assertEquals("trojan.example.com", profile.serverAddress)
        assertEquals(443, profile.serverPort)
        assertEquals(ProtocolType.TROJAN, profile.protocolType)
    }
}
