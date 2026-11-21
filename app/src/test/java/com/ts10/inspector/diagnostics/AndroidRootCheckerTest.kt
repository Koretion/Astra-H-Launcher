package com.ts10.inspector.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidRootCheckerTest {
    @Test fun `su binary is strong root evidence`() {
        val result = evaluateRootEvidence(listOf("/system/xbin/su"), emptyList(), "release-keys", "user")
        assertEquals(RootDetectionStatus.DETECTED, result.status)
        assertTrue(result.evidence.contains("/system/xbin/su"))
    }

    @Test fun `development firmware is possible rather than proven root`() {
        val result = evaluateRootEvidence(emptyList(), emptyList(), "test-keys", "userdebug")
        assertEquals(RootDetectionStatus.POSSIBLE, result.status)
    }

    @Test fun `magisk app without su is not root`() {
        val result = evaluateRootEvidence(emptyList(), listOf("com.topjohnwu.magisk"), "release-keys", "user")
        assertEquals(RootDetectionStatus.POSSIBLE, result.status)
        assertTrue(result.evidence.contains("without su"))
    }

    @Test fun `no evidence does not claim definitely unrooted`() {
        val result = evaluateRootEvidence(emptyList(), emptyList(), "release-keys", "user")
        assertEquals(RootDetectionStatus.NOT_DETECTED, result.status)
        assertTrue(result.evidence.contains("hidden root"))
    }
}
