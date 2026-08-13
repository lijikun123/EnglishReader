package com.example.englishreader.data.sync

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRetryPolicyTest {

    @Test
    fun `retries transport errors only for safe GET requests`() {
        assertTrue(shouldRetrySyncRead(HttpMethod.Get, IOException("socket stalled")))
        assertFalse(shouldRetrySyncRead(HttpMethod.Post, IOException("socket stalled")))
        assertFalse(shouldRetrySyncRead(HttpMethod.Get, IllegalStateException("bad payload")))
    }

    @Test
    fun `retries server errors only for safe GET requests`() {
        assertTrue(shouldRetrySyncReadResponse(HttpMethod.Get, HttpStatusCode.ServiceUnavailable))
        assertFalse(shouldRetrySyncReadResponse(HttpMethod.Post, HttpStatusCode.ServiceUnavailable))
        assertFalse(shouldRetrySyncReadResponse(HttpMethod.Get, HttpStatusCode.BadRequest))
    }
}
