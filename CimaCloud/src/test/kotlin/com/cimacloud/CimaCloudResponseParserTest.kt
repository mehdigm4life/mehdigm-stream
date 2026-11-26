package com.cimacloud

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CimaCloudResponseParserTest {
    @Test
    fun unwrapsNestedServerDataFromPlainJson() {
        val root = CimaCloudResponseParser.decodeBody(
            """{"apiStatus":true,"data":{"servers":[{"link":"https://video.example/embed/one"}]}}""",
        ) { error("Plain JSON must not be decrypted") }

        assertEquals(
            listOf("https://video.example/embed/one"),
            CimaCloudResponseParser.extractServerUrls(requireNotNull(root)),
        )
    }

    @Test
    fun decryptsJsonEnvelopeThenUnwrapsNestedData() {
        val root = CimaCloudResponseParser.decodeBody(
            """{"encrypted":true,"data":"opaque-ciphertext"}""",
        ) { ciphertext ->
            assertEquals("opaque-ciphertext", ciphertext)
            """{"status":true,"data":{"servers":[{"embed_url":"https://video.example/embed/two"}]}}"""
        }

        assertEquals(
            listOf("https://video.example/embed/two"),
            CimaCloudResponseParser.extractServerUrls(requireNotNull(root)),
        )
    }

    @Test
    fun unwrapsRawEncryptedArrayAndAcceptsProtocolRelativeLinks() {
        val root = CimaCloudResponseParser.decodeBody("opaque-ciphertext") {
            """[{"iframe_url":"//video.example/embed/three"}]"""
        }

        assertEquals(
            listOf("https://video.example/embed/three"),
            CimaCloudResponseParser.extractServerUrls(requireNotNull(root)),
        )
    }

    @Test
    fun collectsCommonNestedServerAliasesWithoutDuplicates() {
        val root = JSONObject(
            """{"items":["https://video.example/a",{"server":{"url":"https://video.example/b"},"src":"javascript:alert(1)"}],"data":{"links":[{"file":"https://video.example/a"}]}}""",
        )

        assertEquals(
            listOf("https://video.example/a", "https://video.example/b"),
            CimaCloudResponseParser.extractServerUrls(root),
        )
    }

    @Test
    fun acceptsStringOrProtocolRelativeServerData() {
        val root = JSONObject("""{"data":"//video.example/embed/four"}""")

        assertEquals(
            listOf("https://video.example/embed/four"),
            CimaCloudResponseParser.extractServerUrls(root),
        )
    }

    @Test
    fun ignoresBlockedPayloads() {
        assertNull(
            CimaCloudResponseParser.decodeBody(
                """{"blocked":true,"data":{"servers":[{"url":"https://video.example/nope"}]}}""",
            ) { error("Blocked JSON must not be decrypted") },
        )
    }
}
