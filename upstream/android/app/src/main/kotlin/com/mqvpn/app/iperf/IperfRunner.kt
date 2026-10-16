// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.iperf

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.coroutines.coroutineContext

/** Runs the packaged iperf3 PIE through the active VPN route. */
class IperfRunner @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun run(
        options: IperfOptions,
        onProgress: (String) -> Unit = {},
        onInterval: (IperfInterval) -> Unit = {},
    ): IperfResult = withContext(Dispatchers.IO) {
        options.validationError()?.let { return@withContext IperfResult(error = it) }
        val executable = java.io.File(context.applicationInfo.nativeLibraryDir, "libiperf_exec.so")
        if (!executable.isFile || !executable.canExecute()) {
            return@withContext IperfResult(error = "iperf3 executable is unavailable")
        }
        var process: Process? = null
        try {
            val running = ProcessBuilder(command(executable.absolutePath, options)).start()
            process = running
            val raw = AtomicReference("")
            val err = AtomicReference("")
            val end = AtomicReference<JSONObject?>(null)
            val streamError = AtomicReference<String?>(null)
            val outputExceeded = AtomicBoolean(false)
            // Readers drain both pipes while the coroutine supervises the process.
            // A stream event is parsed before the final JSON, so the gauge uses
            // actual iperf throughput rather than elapsed-time interpolation.
            val outThread = thread(isDaemon = true, name = "iperf-stdout") {
                try {
                    val capture = readLinesBounded(running.inputStream, MAX_STDOUT_BYTES, MAX_LINE_BYTES) { line ->
                        when (val event = IperfJsonParser.streamEvent(line, options.direction)) {
                            is IperfJsonParser.StreamEvent.Interval -> onInterval(event.sample)
                            is IperfJsonParser.StreamEvent.End -> end.set(event.data)
                            is IperfJsonParser.StreamEvent.Error -> streamError.set(event.message)
                            IperfJsonParser.StreamEvent.Other -> Unit
                        }
                    }
                    raw.set(capture.text)
                    outputExceeded.set(capture.exceeded)
                } catch (_: IOException) { /* Child stopped; supervisor reports missing end. */ }
            }
            val errThread = thread(isDaemon = true, name = "iperf-stderr") {
                try { err.set(readBounded(running.errorStream, MAX_STDERR_BYTES)) }
                catch (_: IOException) { /* Child stopped. */ }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(options.durationSeconds.toLong() + 35)
            val start = System.nanoTime()
            var lastReported = -1L
            var timedOut = false
            while (!running.waitFor(200, TimeUnit.MILLISECONDS)) {
                coroutineContext.ensureActive()
                val elapsed = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start)
                if (elapsed != lastReported) {
                    lastReported = elapsed
                    onProgress("${elapsed}s / ${options.durationSeconds}s")
                }
                if (System.nanoTime() >= deadline) {
                    timedOut = true
                    stop(running)
                    break
                }
            }
            coroutineContext.ensureActive()
            outThread.join(2000)
            errThread.join(2000)
            val code = running.exitValue()
            val result = when {
                timedOut -> IperfResult(error = "iperf3 timed out", exitCode = code, rawJson = raw.get())
                outThread.isAlive || errThread.isAlive -> IperfResult(
                    error = "iperf3 output did not finish", exitCode = code, rawJson = raw.get())
                outputExceeded.get() -> IperfResult(
                    error = "iperf3 output exceeded the safety limit", exitCode = code, rawJson = raw.get())
                else -> IperfJsonParser.parseStreamFinal(end.get(), streamError.get(), options.protocol, code, raw.get())
            }
            val stderr = err.get().trim()
            if (result.error != null && stderr.isNotBlank())
                result.copy(error = result.error + ": " + stderr.take(300)) else result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            IperfResult(error = "Unable to run iperf3: " + (e.message ?: e.javaClass.simpleName))
        } finally {
            process?.let { if (it.isAlive) stop(it) }
        }
    }

    private fun command(executable: String, options: IperfOptions): List<String> = buildList {
        add(executable)
        addAll(listOf("-c", options.host, "-p", options.port.toString(),
            "-t", options.durationSeconds.toString(), "-P", options.streams.toString(),
            "--json-stream", "--forceflush", "-i", "1", "--connect-timeout", "5000",
            "--rcv-timeout", "15000", "-O", "1"))
        if (options.direction == IperfDirection.DOWN) add("-R")
        if (options.protocol == IperfProtocol.UDP) {
            val perStream = BigDecimal.valueOf(options.udpTargetMbps)
                .divide(BigDecimal.valueOf(options.streams.toLong()), 6, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString()
            addAll(listOf("-u", "-b", perStream + "M", "-l", "1200"))
        } else {
            addAll(listOf("-M", "1200", "-l", "1200"))
        }
    }

    private fun stop(process: Process) {
        process.destroy()
        if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
        process.waitFor(1, TimeUnit.SECONDS)
    }

    private data class Capture(val text: String, val exceeded: Boolean)

    /** Bounded memory even if a child writes a huge line; keeps draining to avoid deadlock. */
    private fun readLinesBounded(stream: InputStream, maxBytes: Int, maxLineBytes: Int,
        onLine: (String) -> Unit): Capture {
        val output = ByteArrayOutputStream()
        val line = ByteArrayOutputStream()
        var exceeded = false
        var lineExceeded = false
        fun finishLine() {
            if (!lineExceeded && line.size() > 0) {
                val text = line.toString(Charsets.UTF_8.name()).trimEnd('\r')
                onLine(text)
                val bytes = line.toByteArray()
                if (output.size() + bytes.size + 1 <= maxBytes) {
                    output.write(bytes); output.write('\n'.code)
                } else exceeded = true
            } else if (lineExceeded) exceeded = true
            line.reset(); lineExceeded = false
        }
        stream.use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                for (i in 0 until count) {
                    val b = buffer[i].toInt() and 0xff
                    if (b == '\n'.code) finishLine()
                    else if (!lineExceeded) {
                        if (line.size() < maxLineBytes) line.write(b) else lineExceeded = true
                    }
                }
            }
        }
        if (line.size() > 0 || lineExceeded) finishLine()
        return Capture(output.toString(Charsets.UTF_8.name()), exceeded)
    }

    private fun readBounded(stream: InputStream, maxBytes: Int): String {
        val output = ByteArrayOutputStream()
        stream.use {
            val buffer = ByteArray(8192)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                if (output.size() < maxBytes)
                    output.write(buffer, 0, minOf(count, maxBytes - output.size()))
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    companion object {
        private const val MAX_STDOUT_BYTES = 4 * 1024 * 1024
        private const val MAX_STDERR_BYTES = 32 * 1024
        private const val MAX_LINE_BYTES = 256 * 1024
    }
}

