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
        ProtocolRegistry.register(MailruProtocolHandler())
        ProtocolRegistry.register(AvitoProtocolHandler())
        ProtocolRegistry.register(CupsonlineProtocolHandler())
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

    @Test
    fun testMailruParsingAndDeduplication() {
        val mailruJson = """{"server":"cloud.mail.ru","port":443,"url":"https://cloud.mail.ru/public/abcd/1234","cookies":"act=test","format":"mailru"}"""
        val mailruB64 = java.util.Base64.getEncoder().encodeToString(mailruJson.toByteArray(Charsets.UTF_8))
        val mailruLink = "mailru://$mailruB64#MailruDoc"

        val p1 = ProtocolRegistry.parse(mailruLink)
        assertNotNull(p1)
        assertEquals(ProtocolType.MAILRU, p1.protocolType)
        assertEquals("MailruDoc", p1.name)

        // Generate openflux://v1/ format for same document
        val ofJson = """{"name":"OpenFluxDoc","mode":"stream","transports":[{"type":"mailru","url":"https://cloud.mail.ru/public/abcd/1234"}]}"""
        val raw = ofJson.toByteArray(Charsets.UTF_8)
        val deflater = java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION, true)
        deflater.setInput(raw)
        deflater.finish()
        val buf = ByteArray(1024)
        val out = java.io.ByteArrayOutputStream()
        while (!deflater.finished()) {
            val count = deflater.deflate(buf)
            out.write(buf, 0, count)
        }
        deflater.end()
        val b64url = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
        val openfluxLink = "openflux://v1/$b64url"

        val p2 = ProtocolRegistry.parse(openfluxLink)
        assertNotNull(p2)
        assertEquals(ProtocolType.MAILRU, p2.protocolType)
        assertEquals("OpenFluxDoc", p2.name)

        // Test deduplication: when subscription contains both openflux and mailru,
        // openflux duplicate must be dropped, keeping custom mailru client format!
        val combined = listOf(p2, p1)
        val deduplicated = nullvp.data.store.AppStateStore.deduplicateMailruProfiles(combined)
        assertEquals(1, deduplicated.size)
        assertEquals(true, deduplicated[0].protocolRawConfig.contains("mailru"))
        assertEquals(true, deduplicated[0].protocolRawConfig.contains("cookies"))
    }

    @Test
    fun testAvitoParsing() {
        val b64 = java.util.Base64.getEncoder().encodeToString("""{"rooms":"author1/room1,author2/room2","server":"code.avito.ru"}""".toByteArray())
        val link = "avito://$b64#TestAvito"
        val profile = ProtocolRegistry.parse(link)

        assertNotNull(profile)
        assertEquals("TestAvito", profile.name)
        assertEquals("code.avito.ru", profile.serverAddress)
        assertEquals(443, profile.serverPort)
        assertEquals(ProtocolType.AVITO, profile.protocolType)

        // Test openflux variant
        val ofJson = """{"name":"AvitoOpenFlux","mode":"stream","transports":[{"type":"avito","rooms":"author1/room1,author2/room2"}]}"""
        val raw = ofJson.toByteArray(Charsets.UTF_8)
        val deflater = java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION, true)
        deflater.setInput(raw)
        deflater.finish()
        val buf = ByteArray(1024)
        val out = java.io.ByteArrayOutputStream()
        while (!deflater.finished()) {
            val count = deflater.deflate(buf)
            out.write(buf, 0, count)
        }
        deflater.end()
        val b64url = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
        val openfluxLink = "openflux://v1/$b64url"
        val p2 = ProtocolRegistry.parse(openfluxLink)
        assertNotNull(p2)
        assertEquals(ProtocolType.AVITO, p2.protocolType)

        // Deduplication test
        val deduplicated = nullvp.data.store.AppStateStore.deduplicateMailruProfiles(listOf(p2, profile))
        assertEquals(1, deduplicated.size)
        assertEquals(true, deduplicated[0].protocolRawConfig.contains(""""format":"avito""""))
    }

    @Test
    fun testCupsonlineParsing() {
        val b64 = java.util.Base64.getEncoder().encodeToString("""{"rooms":"room-uuid-1,room-uuid-2","server":"interview.cups.online"}""".toByteArray())
        val link = "cupsonline://$b64#TestCups"
        val profile = ProtocolRegistry.parse(link)

        assertNotNull(profile)
        assertEquals("TestCups", profile.name)
        assertEquals("interview.cups.online", profile.serverAddress)
        assertEquals(443, profile.serverPort)
        assertEquals(ProtocolType.CUPSONLINE, profile.protocolType)

        // Test openflux variant
        val ofJson = """{"name":"CupsOpenFlux","mode":"stream","transports":[{"type":"cupsonline","rooms":"room-uuid-1,room-uuid-2"}]}"""
        val raw = ofJson.toByteArray(Charsets.UTF_8)
        val deflater = java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION, true)
        deflater.setInput(raw)
        deflater.finish()
        val buf = ByteArray(1024)
        val out = java.io.ByteArrayOutputStream()
        while (!deflater.finished()) {
            val count = deflater.deflate(buf)
            out.write(buf, 0, count)
        }
        deflater.end()
        val b64url = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
        val openfluxLink = "openflux://v1/$b64url"
        val p2 = ProtocolRegistry.parse(openfluxLink)
        assertNotNull(p2)
        assertEquals(ProtocolType.CUPSONLINE, p2.protocolType)

        // Deduplication test
        val deduplicated = nullvp.data.store.AppStateStore.deduplicateMailruProfiles(listOf(p2, profile))
        assertEquals(1, deduplicated.size)
        assertEquals(true, deduplicated[0].protocolRawConfig.contains(""""format":"cupsonline""""))
    }
}
