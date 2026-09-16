package io.github.teykaijun.netblocker.vpn

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import java.io.FileDescriptor
import java.io.IOException

/**
 * Reads every packet that blocked apps send into the tunnel and answers it through [PacketRejecter].
 *
 * Runs on its own thread until [shutdown] is called, then closes the tunnel. If the tunnel fails
 * on its own, [onDied] is invoked (from this thread) so the service can react.
 */
internal class PacketLoop(
    private val tunnel: ParcelFileDescriptor,
    private val onDied: (PacketLoop) -> Unit,
) : Thread("tunnel-packet-loop") {

    // A pipe lets shutdown() wake the thread from poll() without relying on closing the tunnel fd.
    private val wakeUpRead: FileDescriptor
    private val wakeUpWrite: FileDescriptor

    @Volatile
    private var stopping = false

    init {
        isDaemon = true
        val pipe = try {
            Os.pipe()
        } catch (e: ErrnoException) {
            tunnel.close()
            throw e
        }
        wakeUpRead = pipe[0]
        wakeUpWrite = pipe[1]
    }

    override fun run() {
        val tun = tunnel.fileDescriptor
        val buffer = ByteArray(BUFFER_SIZE)
        val pollFds = arrayOf(pollFd(tun), pollFd(wakeUpRead))
        try {
            while (!stopping) {
                pollFds.forEach { it.revents = 0 }
                try {
                    Os.poll(pollFds, -1)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) continue
                    throw e
                }
                if (stopping || pollFds[1].revents.toInt() != 0) break

                val events = pollFds[0].revents.toInt()
                if ((events and OsConstants.POLLIN) != 0) {
                    drain(tun, buffer)
                } else if ((events and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL)) != 0) {
                    throw IOException("Tunnel closed (poll events: $events)")
                }
            }
        } catch (e: Exception) {
            if (!stopping) {
                Log.w(TAG, "Packet loop failed", e)
                onDied(this)
            }
        } finally {
            ignoringErrors { tunnel.close() }
            ignoringErrors { Os.close(wakeUpRead) }
        }
    }

    /** Asks the thread to stop and close the tunnel. Call from the main thread only. */
    fun shutdown() {
        if (stopping) return
        stopping = true
        ignoringErrors { Os.write(wakeUpWrite, byteArrayOf(1), 0, 1) }
        ignoringErrors { Os.close(wakeUpWrite) }
    }

    private fun drain(tun: FileDescriptor, buffer: ByteArray) {
        while (!stopping) {
            val length = try {
                Os.read(tun, buffer, 0, buffer.size)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EAGAIN) return // no more packets queued
                throw e
            }
            if (length <= 0) return

            val reply = PacketRejecter.reject(buffer, length) ?: continue
            try {
                Os.write(tun, reply, 0, reply.size)
            } catch (e: ErrnoException) {
                // The reply only makes the app fail faster; if it can't be written, the packet is just dropped.
            }
        }
    }

    private fun pollFd(descriptor: FileDescriptor) = StructPollfd().apply {
        fd = descriptor
        events = OsConstants.POLLIN.toShort()
    }

    private inline fun ignoringErrors(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            // Best-effort cleanup.
        }
    }

    private companion object {
        const val TAG = "PacketLoop"
        const val BUFFER_SIZE = 32_767
    }
}
