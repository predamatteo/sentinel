package com.sentinel.app.vpn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Verifies the single-writer guarantee: enqueued packets are written
 * whole and in FIFO order by the single drain coroutine.
 */
class TunWriterTest {

    @Test
    fun writesEnqueuedPacketsIntactInOrder() = runBlocking {
        val out = ByteArrayOutputStream()
        val scope = CoroutineScope(Dispatchers.IO)
        val writer = TunWriter(out, scope)
        writer.enqueue(byteArrayOf(1, 2, 3))
        writer.enqueue(byteArrayOf(4, 5))
        writer.closeAndJoinForTest()
        scope.cancel()
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), out.toByteArray())
    }
}
