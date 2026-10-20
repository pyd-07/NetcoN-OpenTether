package com.opentether.ui.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocsContentTest {
    @Test
    fun everyRequiredTopicHasContent() {
        val expected = listOf(
            "getting-started",
            "how-it-works",
            "lifecycle",
            "troubleshooting",
            "technical-reference",
            "privacy-security",
            "developer-info",
        )

        assertEquals(expected, DocsContent.topics.map { it.id })
        DocsContent.topics.forEach { topic ->
            assertTrue(topic.title.isNotBlank())
            assertTrue(topic.summary.isNotBlank())
            assertTrue(topic.blocks.isNotEmpty())
        }
    }

    @Test
    fun commandsAndRepositoryLinksArePresent() {
        val code = DocsContent.topics.flatMap { it.blocks }.filterIsInstance<DocsBlock.Code>()
        val links = DocsContent.topics.flatMap { it.blocks }.filterIsInstance<DocsBlock.Link>()

        assertTrue(code.any { it.text.contains("adb reverse") })
        assertTrue(code.any { it.text.contains("./gradlew assembleDebug") })
        assertTrue(links.any { it.url == DocsContent.ADB_GUIDE_URL })
    }
}
