/*
 * USB ADB packet transport adapted from Dioxamine.
 * Copyright (C) 2026 rhythmcache. Licensed under Apache-2.0; see THIRD_PARTY_NOTICES.txt.
 */
package com.hkpick.phoneinstaller

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import io.github.rhythmcache.adb.AdbException
import io.github.rhythmcache.adb.AdbPacket
import io.github.rhythmcache.adb.PacketTransport
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class UsbInterfaceMatcher(val interfaceClass: Int, val interfaceSubclass: Int, val interfaceProtocol: Int)

class UsbPacketTransport(
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint,
    private val timeoutMs: Int = 10000,
) : PacketTransport {
    private val sendLock = ReentrantLock()
    private val recvLock = ReentrantLock()
    private val readBuffer = ByteArray(maxOf(inEndpoint.maxPacketSize, 4096))
    private var readBufferOffset = 0
    private var readBufferLength = 0

    @Volatile private var isClosed = false

    override fun send(pkt: AdbPacket) {
        if (isClosed) throw AdbException.StreamClosed("USB transport is closed")

        val payloadSize = pkt.payloadLength
        val payloadChecksum = AdbPacket.checksum(pkt.payload, pkt.payloadOffset, pkt.payloadLength)
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(pkt.command)
            putInt(pkt.arg0)
            putInt(pkt.arg1)
            putInt(payloadSize)
            putInt(payloadChecksum)
            putInt(pkt.command xor -1)
        }.array()

        sendLock.withLock {
            val headerRet = connection.bulkTransfer(outEndpoint, header, 0, header.size, timeoutMs)
            if (headerRet != 24) throw AdbException.Transport("USB bulk write header failed ($headerRet)")

            var bytesWritten = 0
            val chunkLimit = maxOf(outEndpoint.maxPacketSize, 4096)
            while (bytesWritten < payloadSize) {
                if (isClosed) throw AdbException.StreamClosed("USB transport closed during payload write")
                val chunkSize = minOf(payloadSize - bytesWritten, chunkLimit)
                val ret = connection.bulkTransfer(
                    outEndpoint,
                    pkt.payload,
                    pkt.payloadOffset + bytesWritten,
                    chunkSize,
                    timeoutMs,
                )
                if (ret <= 0) throw AdbException.Transport("USB bulk write payload failed ($ret)")
                bytesWritten += ret
            }

            if (outEndpoint.maxPacketSize > 0 && payloadSize > 0 && payloadSize % outEndpoint.maxPacketSize == 0) {
                connection.bulkTransfer(outEndpoint, ByteArray(0), 0, timeoutMs)
            }
        }
    }

    override fun recv(): AdbPacket {
        if (isClosed) throw AdbException.StreamClosed("USB transport is closed")

        return recvLock.withLock {
            val headerBytes = ByteArray(24)
            readExact(headerBytes, headerBytes.size, allowTimeoutRetry = true)
            val header = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
            val command = header.int
            val arg0 = header.int
            val arg1 = header.int
            val length = header.int
            val expectedChecksum = header.int
            val magic = header.int

            if (length < 0 || length > 16 * 1024 * 1024) {
                throw AdbException.Protocol("Invalid ADB USB packet length: $length")
            }
            if ((command xor magic) != -1) {
                throw AdbException.Protocol("Invalid ADB USB packet magic")
            }

            val payload = if (length > 0) ByteArray(length).also { bytes ->
                readExact(bytes, length, allowTimeoutRetry = false)
                if (expectedChecksum != 0 && AdbPacket.checksum(bytes) != expectedChecksum) {
                    throw AdbException.Protocol("USB ADB packet checksum mismatch")
                }
            } else {
                ByteArray(0)
            }
            AdbPacket(command, arg0, arg1, payload)
        }
    }

    private fun readChunk(buffer: ByteArray, offset: Int, length: Int): Int {
        if (readBufferOffset >= readBufferLength) {
            val ret = connection.bulkTransfer(inEndpoint, readBuffer, 0, readBuffer.size, timeoutMs)
            if (ret <= 0) return -1
            readBufferOffset = 0
            readBufferLength = ret
        }
        val copied = minOf(length, readBufferLength - readBufferOffset)
        System.arraycopy(readBuffer, readBufferOffset, buffer, offset, copied)
        readBufferOffset += copied
        return copied
    }

    private fun readExact(buffer: ByteArray, length: Int, allowTimeoutRetry: Boolean) {
        var bytesRead = 0
        while (bytesRead < length) {
            if (isClosed) throw AdbException.StreamClosed("USB transport closed during read")
            val ret = readChunk(buffer, bytesRead, length - bytesRead)
            if (ret == -1) {
                if (allowTimeoutRetry && bytesRead == 0) {
                    throw AdbException.Timeout("USB read timed out waiting for an ADB packet")
                }
                throw AdbException.Transport("USB read stopped after $bytesRead of $length bytes")
            }
            bytesRead += ret
        }
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        runCatching { connection.releaseInterface(iface) }
        runCatching { connection.close() }
    }

    companion object {
        val ADB_INTERFACE_MATCHER = UsbInterfaceMatcher(0xFF, 0x42, 0x01)

        fun findMatchingInterface(device: UsbDevice, matcher: UsbInterfaceMatcher): UsbInterface? {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass == matcher.interfaceClass &&
                    iface.interfaceSubclass == matcher.interfaceSubclass &&
                    iface.interfaceProtocol == matcher.interfaceProtocol
                ) return iface
            }
            return null
        }

        fun findAdbInterface(device: UsbDevice): UsbInterface? =
            findMatchingInterface(device, ADB_INTERFACE_MATCHER)

        fun findAdbEndpoints(iface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
            var input: UsbEndpoint? = null
            var output: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val endpoint = iface.getEndpoint(i)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (endpoint.direction == UsbConstants.USB_DIR_IN) input = endpoint
                if (endpoint.direction == UsbConstants.USB_DIR_OUT) output = endpoint
            }
            return if (input != null && output != null) Pair(input, output) else null
        }
    }
}
