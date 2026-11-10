package me.rerere.rikkahub.data.ai

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

private fun <T : Any> assertNotNull(value: T?): T {
    org.junit.Assert.assertNotNull(value)
    return requireNotNull(value)
}

class RequestPrefixDiagnosticsTest {
    @Test
    fun identicalRequestReportsStablePrefix() {
        val diagnostics = RequestPrefixDiagnostics()
        val body = "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"a\"},{\"role\":\"assistant\",\"content\":\"b\"}]}".encodeToByteArray()

        assertNotNull(diagnostics.observe("https://example.test", "s", listOf("key-a"), body))
        val report = assertNotNull(diagnostics.observe("https://example.test", "s", listOf("key-a"), body))

        assertEquals(2, report.arrays.single { it.field == "messages" }.prefixCount)
        assertNull(report.arrays.single { it.field == "messages" }.firstChangedIndex)
        assertEquals(RequestPrefixDiagnostics.FieldState.ABSENT, report.frontFields["tools"])
    }

    @Test
    fun appendedMessageReportsOldPrefixAndFirstNewIndex() {
        val diagnostics = RequestPrefixDiagnostics()
        val first = "{\"messages\":[{\"content\":\"a\"}]}"
        val second = "{\"messages\":[{\"content\":\"a\"},{\"content\":\"b\"}]}"
        diagnostics.observe("e", "s", emptyList(), first.encodeToByteArray())
        val report = assertNotNull(diagnostics.observe("e", "s", emptyList(), second.encodeToByteArray()))
        val messages = report.arrays.single()

        assertEquals(1, messages.prefixCount)
        assertNull(messages.firstChangedIndex)
        assertTrue(messages.previousIsPrefix == true)
    }

    @Test
    fun editedHistoryReportsChangedIndex() {
        val diagnostics = RequestPrefixDiagnostics()
        val first = "{\"messages\":[{\"content\":\"a\"},{\"content\":\"b\"}]}"
        val second = "{\"messages\":[{\"content\":\"edited\"},{\"content\":\"b\"}]}"
        diagnostics.observe("e", "s", emptyList(), first.encodeToByteArray())
        val messages = assertNotNull(diagnostics.observe("e", "s", emptyList(), second.encodeToByteArray())).arrays.single()

        assertEquals(0, messages.prefixCount)
        assertEquals(0, messages.firstChangedIndex)
        assertFalse(messages.previousIsPrefix == true)
    }

    @Test
    fun toolsChangeIsReportedWithoutRemovingControlField() {
        val diagnostics = RequestPrefixDiagnostics()
        val first = "{\"messages\":[],\"tools\":[{\"name\":\"a\",\"cache_control\":{\"type\":\"ephemeral\"}}]}"
        val second = "{\"messages\":[],\"tools\":[{\"name\":\"b\",\"cache_control\":{\"type\":\"ephemeral\"}}]}"
        diagnostics.observe("e", "s", emptyList(), first.encodeToByteArray())
        val report = assertNotNull(diagnostics.observe("e", "s", emptyList(), second.encodeToByteArray()))

        assertEquals(RequestPrefixDiagnostics.FieldState.CHANGED, report.frontFields["tools"])
        assertTrue(report.cacheControlChanged.not())
    }

    @Test
    fun differentSessionsDoNotMixBaselines() {
        val diagnostics = RequestPrefixDiagnostics()
        val body = "{\"messages\":[{\"content\":\"a\"}]}".encodeToByteArray()
        diagnostics.observe("e", "session-a", emptyList(), body)
        diagnostics.observe("e", "session-b", emptyList(), body)

        assertEquals(1, diagnostics.observe("e", "session-a", emptyList(), body)!!.arrays.single().prefixCount)
        assertEquals(1, diagnostics.observe("e", "session-b", emptyList(), body)!!.arrays.single().prefixCount)
    }

    @Test
    fun rawWhitespaceAndFieldOrderAreNotRewrittenForComparison() {
        val diagnostics = RequestPrefixDiagnostics()
        val first = "{\"model\":\"m\",\"messages\":[ { \"content\": \"a\" } ]}"
        val second = "{ \"messages\" : [ {\"content\":\"a\"} ], \"model\":\"m\" }"
        diagnostics.observe("e", "s", emptyList(), first.encodeToByteArray())
        val report = assertNotNull(diagnostics.observe("e", "s", emptyList(), second.encodeToByteArray()))

        assertEquals(0, report.arrays.single().prefixCount)
        assertEquals(0, report.arrays.single().firstChangedIndex)
        assertFalse(report.arrays.single().arrayLayoutStable == true)
    }

    @Test
    fun escapedStringAndNestedJsonArrayRemainOneOriginalElement() {
        val diagnostics = RequestPrefixDiagnostics()
        val first = "{\"input\":[{\"text\":\"escaped \\\"quote\\\"\",\"nested\":[1,{\"x\":true}]}]}"
        val second = "{\"input\":[{\"text\":\"escaped \\\"quote\\\"\",\"nested\":[1,{\"x\":true}]},{\"text\":\"new\"}]}"
        diagnostics.observe("e", "s", emptyList(), first.encodeToByteArray())
        val input = assertNotNull(diagnostics.observe("e", "s", emptyList(), second.encodeToByteArray())).arrays.single()

        assertEquals(1, input.prefixCount)
        assertNull(input.firstChangedIndex)
        assertEquals(2, input.currentCount)
    }

    @Test
    fun capacityAndOversizedBodyStayBounded() {
        val diagnostics = RequestPrefixDiagnostics(maxEntries = 1, maxFingerprints = 8)
        val body = "{\"messages\":[{\"content\":\"a\"}]}".encodeToByteArray()
        diagnostics.observe("e-1", "s", emptyList(), body)
        diagnostics.observe("e-2", "s", emptyList(), body)
        assertNull(diagnostics.observe("e-1", "s", emptyList(), body)!!.arrays.single().previousCount)
        assertNull(diagnostics.observe("e-2", null, emptyList(), body))
        assertNull(diagnostics.observe("e-2", "s", emptyList(), ByteArray(RequestPrefixDiagnostics.MAX_BODY_BYTES + 1)))
    }
}
