package com.wallhub.android.data.vpn

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress

internal object Socks5Protocol {
    data class Target(
        val host: String,
        val address: InetAddress?,
        val port: Int,
    )

    fun negotiate(input: InputStream, output: OutputStream): Target {
        val data = DataInputStream(input)
        requireByte(data, VERSION, "SOCKS version")
        val methodCount = data.readUnsignedByte()
        if (methodCount == 0) throw Socks5Exception(REPLY_GENERAL_FAILURE, "SOCKS method list is empty")
        var supportsNoAuth = false
        repeat(methodCount) {
            if (data.readUnsignedByte() == METHOD_NO_AUTH) supportsNoAuth = true
        }
        if (!supportsNoAuth) {
            output.write(byteArrayOf(VERSION.toByte(), METHOD_NOT_ACCEPTABLE.toByte()))
            output.flush()
            throw Socks5Exception(REPLY_CONNECTION_NOT_ALLOWED, "SOCKS client requires authentication")
        }
        output.write(byteArrayOf(VERSION.toByte(), METHOD_NO_AUTH.toByte()))
        output.flush()

        requireByte(data, VERSION, "SOCKS request version")
        val command = data.readUnsignedByte()
        requireByte(data, RESERVED, "SOCKS reserved byte")
        if (command != COMMAND_CONNECT) {
            sendReply(output, REPLY_COMMAND_NOT_SUPPORTED)
            throw Socks5Exception(REPLY_COMMAND_NOT_SUPPORTED, "Only SOCKS CONNECT is supported")
        }

        val addressType = data.readUnsignedByte()
        val target = when (addressType) {
            ADDRESS_IPV4 -> {
                val address = InetAddress.getByAddress(data.readExact(4))
                Target(address.hostAddress.orEmpty(), address, data.readUnsignedShort())
            }

            ADDRESS_IPV6 -> {
                val address = InetAddress.getByAddress(data.readExact(16))
                Target(address.hostAddress.orEmpty(), address, data.readUnsignedShort())
            }

            ADDRESS_DOMAIN -> {
                val length = data.readUnsignedByte()
                if (length == 0) throw Socks5Exception(REPLY_ADDRESS_NOT_SUPPORTED, "SOCKS domain is empty")
                val host = data.readExact(length).toString(Charsets.US_ASCII)
                Target(host, null, data.readUnsignedShort())
            }

            else -> {
                sendReply(output, REPLY_ADDRESS_NOT_SUPPORTED)
                throw Socks5Exception(REPLY_ADDRESS_NOT_SUPPORTED, "Unsupported SOCKS address type")
            }
        }
        if (target.port !in 1..65_535) {
            sendReply(output, REPLY_ADDRESS_NOT_SUPPORTED)
            throw Socks5Exception(REPLY_ADDRESS_NOT_SUPPORTED, "SOCKS destination port is invalid")
        }
        return target
    }

    fun sendSuccess(output: OutputStream) = sendReply(output, REPLY_SUCCEEDED)

    fun sendFailure(output: OutputStream, reply: Int = REPLY_HOST_UNREACHABLE) = sendReply(output, reply)

    private fun sendReply(output: OutputStream, reply: Int) {
        output.write(
            byteArrayOf(
                VERSION.toByte(),
                reply.toByte(),
                RESERVED.toByte(),
                ADDRESS_IPV4.toByte(),
                0,
                0,
                0,
                0,
                0,
                0,
            ),
        )
        output.flush()
    }

    private fun requireByte(input: DataInputStream, expected: Int, label: String) {
        if (input.readUnsignedByte() != expected) {
            throw Socks5Exception(REPLY_GENERAL_FAILURE, "$label is invalid")
        }
    }

    private fun DataInputStream.readExact(size: Int): ByteArray = ByteArray(size).also { bytes ->
        readFully(bytes)
    }

    class Socks5Exception(
        val reply: Int,
        message: String,
    ) : IOException(message)

    const val REPLY_GENERAL_FAILURE = 1
    const val REPLY_CONNECTION_NOT_ALLOWED = 2
    const val REPLY_HOST_UNREACHABLE = 4
    private const val REPLY_SUCCEEDED = 0
    private const val REPLY_COMMAND_NOT_SUPPORTED = 7
    private const val REPLY_ADDRESS_NOT_SUPPORTED = 8
    private const val VERSION = 5
    private const val RESERVED = 0
    private const val METHOD_NO_AUTH = 0
    private const val METHOD_NOT_ACCEPTABLE = 0xff
    private const val COMMAND_CONNECT = 1
    private const val ADDRESS_IPV4 = 1
    private const val ADDRESS_DOMAIN = 3
    private const val ADDRESS_IPV6 = 4
}
