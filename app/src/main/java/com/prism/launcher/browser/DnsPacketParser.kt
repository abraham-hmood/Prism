package com.prism.launcher.browser

import java.nio.ByteBuffer

/**
 * Parses incoming DNS query packets (RFC 1035).
 * Supports basic DNS format: question section extraction.
 */
object DnsPacketParser {

    data class DnsQuery(
        val transactionId: Short,
        val recursionDesired: Boolean,
        val questions: List<DnsQuestion>
    )

    data class DnsQuestion(
        val name: String,
        val type: Int,  // 1=A, 28=AAAA, 5=CNAME, etc.
        val qclass: Int // 1=IN
    )

    fun parseQuery(data: ByteArray): DnsQuery? {
        if (data.size < 12) return null

        val buffer = ByteBuffer.wrap(data)

        // Header (12 bytes)
        val transactionId = buffer.short
        val flags = buffer.short
        val qdcount = buffer.short.toInt() and 0xFFFF
        val ancount = buffer.short.toInt() and 0xFFFF
        val nscount = buffer.short.toInt() and 0xFFFF
        val arcount = buffer.short.toInt() and 0xFFFF

        val recursionDesired = (flags.toInt() and 0x0100) != 0

        val questions = mutableListOf<DnsQuestion>()

        // Parse questions
        for (i in 0 until qdcount) {
            val name = parseName(buffer, data) ?: return null
            if (buffer.remaining() < 4) return null

            val type = buffer.short.toInt() and 0xFFFF
            val qclass = buffer.short.toInt() and 0xFFFF

            questions.add(DnsQuestion(name, type, qclass))
        }

        return DnsQuery(transactionId, recursionDesired, questions)
    }

    /**
     * Parse a DNS domain name from the buffer.
     * Supports label compression (pointers starting with 0xC0).
     */
    private fun parseName(buffer: ByteBuffer, fullData: ByteArray): String? {
        val labels = mutableListOf<String>()

        while (true) {
            if (!buffer.hasRemaining()) return null

            val length = buffer.get().toInt() and 0xFF

            when {
                length == 0 -> break // End of name
                (length and 0xC0) == 0xC0 -> {
                    // Pointer: next byte is offset
                    if (!buffer.hasRemaining()) return null
                    val offset = ((length and 0x3F) shl 8) or (buffer.get().toInt() and 0xFF)

                    if (offset >= fullData.size) return null
                    val pointerBuffer = ByteBuffer.wrap(fullData, offset, fullData.size - offset)
                    val pointerName = parseName(pointerBuffer, fullData) ?: return null
                    labels.add(pointerName)
                    break
                }
                length <= 63 -> {
                    // Regular label
                    if (buffer.remaining() < length) return null
                    val labelBytes = ByteArray(length)
                    buffer.get(labelBytes)
                    labels.add(String(labelBytes, Charsets.US_ASCII))
                }
                else -> return null
            }
        }

        return labels.joinToString(".")
    }
}
