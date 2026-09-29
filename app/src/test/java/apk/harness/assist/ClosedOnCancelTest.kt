package apk.harness.assist

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClosedOnCancelTest {

    /** A read that returns only once the resource is closed, as a sender's pipe that never writes. */
    private class Stuck : Closeable {
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        fun read(): Int {
            entered.countDown()
            closed.await()
            throw IOException("closed")
        }
        override fun close() = closed.countDown()
    }

    @Test
    fun `a cancel closes the resource, which ends a blocked read`() = runBlocking {
        val stuck = Stuck()
        val job = launch(Dispatchers.IO) { closedOnCancel(stuck) { stuck.read() } }
        assertTrue(stuck.entered.await(2, TimeUnit.SECONDS))
        job.cancel()
        withTimeout(2000) { job.join() }
        assertEquals(0, stuck.closed.count)
    }

    @Test
    fun `a read that ends returns its value and leaves the resource open`() = runBlocking {
        val stuck = Stuck()
        assertEquals(7, closedOnCancel(stuck) { 7 })
        assertEquals(1, stuck.closed.count)
    }
}
