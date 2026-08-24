package com.example.englishreader.data.sync

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SyncRetryPolicyTest {

    @Test
    fun `retries a complete read after transport failures`() = runBlocking {
        var calls = 0

        val response = retrySyncRead(
            maxAttempts = 3,
            delayBetweenAttempts = {},
        ) {
            calls += 1
            if (calls < 3) throw IOException("response stalled")
            "synced"
        }

        assertEquals("synced", response)
        assertEquals(3, calls)
    }

    @Test
    fun `does not replay a read for non transport failures`() = runBlocking {
        var calls = 0

        try {
            retrySyncRead(maxAttempts = 3, delayBetweenAttempts = {}) {
                calls += 1
                throw IllegalStateException("invalid response")
            }
            fail("Expected the non-transport error")
        } catch (_: IllegalStateException) {
            assertEquals(1, calls)
        }
    }
}
