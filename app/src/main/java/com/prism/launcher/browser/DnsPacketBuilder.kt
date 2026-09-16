package com.prism.launcher.browser

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Builds DNS response packets (RFC 1035).
 * Supports A records (IPv4) and NXDOMAIN responses.
 */
object DnsPacketBuilder {

    fun buildResponseA(transactionId: Short, domain: String, ipAddress: String): ByteArray {
        val output = ByteArrayOutputStream()

        // Header (12 bytes)
        val header = ByteBuffer.allocate(12)
        header.putShort(transactionId)

        // Flags: QR=1 (response), Opcode=0, AA=0, TC=0, RD=1, RA=0, Z=0, AD=0, CD=0, RCODE=0 (NOERROR)
        // QR(1) + Opcode(4) + AA(1) + TC(1) + RD(1) + RA(1) + Z(1) + AD(1) + CD(1) + RCODE(4)
        // = 1000_0000_1000_0000 = 0x8080 (response, recursion available)
        val flags: Short = 0x8480.toShort() // QR=1, RD=1, RA=1
        header.putShort(flags)

        header.putShort(1) // QDCOUNT: 1 question
        header.putShort(1) // ANCOUNT: 1 answer
        header.putShort(0) // NSCOUNT: 0
        header.putShort(0) // ARCOUNT: 0

        output.write(header.array())

        // Question section
        output.write(encodeName(domain))
        output.writeShort(1)    // Type: A
        output.writeShort(1)    // Class: IN

        // Answer section
        output.write(encodeName(domain))
        output.writeShort(1)    // Type: A
        output.writeShort(1)    // Class: IN
        output.writeInt(300)    // TTL: 300 seconds

        // RDATA: IPv4 address
        val ipParts = ipAddress.split(".")
        if (ipParts.size != 4) throw IllegalArgumentException("Invalid IPv4: $ipAddress")
        output.writeShort(4)    // RDLENGTH: 4 bytes for IPv4
        for (part in ipParts) {
            output.write(part.toInt() and 0xFF)
        }

        return output.toByteArray()
    }

    fun buildResponseNXDOMAIN(transactionId: Short, domain: String): ByteArray {
        val output = ByteArrayOutputStream()

        // Header
        val header = ByteBuffer.allocate(12)
        header.putShort(transactionId)

        // Flags: QR=1, RCODE=3 (NXDOMAIN)
        // 1000_0000_0000_0011 = 0x8003
        val flags: Short = 0x8003.toShort()
        header.putShort(flags)

        header.putShort(1) // QDCOUNT
        header.putShort(0) // ANCOUNT
        header.putShort(0) // NSCOUNT
        header.putShort(0) // ARCOUNT

        output.write(header.array())

        // Question section
        output.write(encodeName(domain))
        output.writeShort(1)    // Type: A
        output.writeShort(1)    // Class: IN

        return output.toByteArray()
    }

    /**
     * Encode a domain name in DNS format.
     * Example: "example.com" -> [0x07, 'e','x','a','m','p','l','e', 0x03, 'c','o','m', 0x00]
     */
    private fun encodeName(domain: String): ByteArray {
        val output = ByteArrayOutputStream()
        val labels = domain.split(".")

        for (label in labels) {
            if (label.length > 63) throw IllegalArgumentException("DNS label too long: $label")
            output.write(label.length)
            output.write(label.toByteArray(Charsets.US_ASCII))
        }

        output.write(0) // Root label
        return output.toByteArray()
    }

    private fun ByteArrayOutputStream.writeShort(value: Int) {
        write((value shr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write((value shr 24) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 8) and 0xFF)
        write(value and 0xFF)
    }
}
