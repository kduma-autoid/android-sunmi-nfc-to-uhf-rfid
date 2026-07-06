package dev.duma.android.nfctorfid

import dev.duma.android.nfctorfid.epc.EpcCodec
import dev.duma.android.nfctorfid.epc.hexToBytesOrNull
import dev.duma.android.nfctorfid.epc.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpcCodecTest {

    @Test
    fun `encode 7 byte uid produces documented example`() {
        val uid = "04A22E5B338841".hexToBytesOrNull()!!
        assertEquals("4E0704A22E5B338841000000", EpcCodec.encodeEpc(uid).toHex())
    }

    @Test
    fun `encode 4 byte uid pads with zeros`() {
        val uid = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        assertEquals("4E04DEADBEEF000000000000", EpcCodec.encodeEpc(uid).toHex())
    }

    @Test
    fun `encode 10 byte uid fills epc completely`() {
        val uid = "00112233445566778899".hexToBytesOrNull()!!
        assertEquals("4E0A00112233445566778899", EpcCodec.encodeEpc(uid).toHex())
    }

    @Test
    fun `pc word is 0x3100 non-gs1`() {
        val uid = "04A22E5B338841".hexToBytesOrNull()!!
        val pcAndEpc = EpcCodec.encodePcAndEpc(uid)
        assertEquals(14, pcAndEpc.size)
        assertEquals("3100", pcAndEpc.copyOfRange(0, 2).toHex())
        assertArrayEquals(EpcCodec.encodeEpc(uid), pcAndEpc.copyOfRange(2, 14))
    }

    @Test
    fun `roundtrip for all valid uid lengths`() {
        for (len in intArrayOf(4, 7, 10)) {
            val uid = ByteArray(len) { (it + 1).toByte() }
            val decoded = EpcCodec.decodeUid(EpcCodec.encodeEpc(uid))
            assertArrayEquals("length $len", uid, decoded)
        }
    }

    @Test
    fun `decode rejects wrong prefix, length byte, size and padding`() {
        val good = EpcCodec.encodeEpc(ByteArray(4) { 1 })
        assertNull(EpcCodec.decodeUid(good.copyOf().also { it[0] = 0x30 }))
        assertNull(EpcCodec.decodeUid(good.copyOf().also { it[1] = 0x05 }))
        assertNull(EpcCodec.decodeUid(good.copyOf(11)))
        assertNull(EpcCodec.decodeUid(good.copyOf().also { it[11] = 0x01 }))
    }

    @Test
    fun `decodeUidHex works with spaced lowercase input`() {
        assertEquals("04A22E5B338841", EpcCodec.decodeUidHex("4e07 04a2 2e5b 3388 4100 0000"))
        assertNull(EpcCodec.decodeUidHex("301400001234"))
        assertNull(EpcCodec.decodeUidHex("not hex"))
    }

    @Test
    fun `encode rejects invalid uid lengths`() {
        for (len in intArrayOf(0, 3, 5, 8, 11)) {
            try {
                EpcCodec.encodeEpc(ByteArray(len))
                throw AssertionError("expected IllegalArgumentException for length $len")
            } catch (expected: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `random iso14443-4 ids are detected`() {
        assertTrue(EpcCodec.isRandomUid(byteArrayOf(0x08, 0x12, 0x34, 0x56)))
        assertFalse(EpcCodec.isRandomUid(byteArrayOf(0x04, 0x12, 0x34, 0x56)))
        assertFalse(EpcCodec.isRandomUid(ByteArray(7) { 0x08 }))
    }
}
