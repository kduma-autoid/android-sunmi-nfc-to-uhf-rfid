package dev.duma.android.nfctorfid

import dev.duma.android.nfctorfid.epc.toHex
import dev.duma.android.nfctorfid.nfc.OriginalityStatus
import dev.duma.android.nfctorfid.nfc.OriginalityVerifier
import java.math.BigInteger
import java.security.SecureRandom
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECKeyGenerationParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginalityVerifierTest {

    private val uid = byteArrayOf(0x04, 0x7A, 0x1B, 0x2C, 0x3D, 0x4E, 0x5F)

    private fun generateKeyPair(curveName: String): Pair<AsymmetricCipherKeyPair, ECDomainParameters> {
        val params = SECNamedCurves.getByName(curveName)
        val domain = ECDomainParameters(params.curve, params.g, params.n, params.h)
        val generator = ECKeyPairGenerator()
        generator.init(ECKeyGenerationParameters(domain, SecureRandom()))
        return generator.generateKeyPair() to domain
    }

    /** Raw ECDSA over the UID, output in fixed-width P1363 (R || S). */
    private fun signP1363(message: ByteArray, keyPair: AsymmetricCipherKeyPair, componentBytes: Int): ByteArray {
        val signer = ECDSASigner()
        signer.init(true, keyPair.private as ECPrivateKeyParameters)
        val (r, s) = signer.generateSignature(message).let { it[0] to it[1] }
        return toFixed(r, componentBytes) + toFixed(s, componentBytes)
    }

    private fun toFixed(value: BigInteger, length: Int): ByteArray {
        val raw = value.toByteArray()
        val out = ByteArray(length)
        val src = if (raw.size > length) raw.copyOfRange(raw.size - length, raw.size) else raw
        src.copyInto(out, length - src.size)
        return out
    }

    private fun nxpKeyFor(keyPair: AsymmetricCipherKeyPair, curveName: String): OriginalityVerifier.NxpKey {
        val publicPoint = (keyPair.public as ECPublicKeyParameters).q
        return OriginalityVerifier.NxpKey("test", curveName, publicPoint.getEncoded(false).toHex())
    }

    @Test
    fun `valid raw ecdsa signature verifies on secp128r1`() {
        val (keyPair, _) = generateKeyPair("secp128r1")
        val signature = signP1363(uid, keyPair, 16)
        assertEquals(32, signature.size)
        assertTrue(OriginalityVerifier.verify(uid, signature, nxpKeyFor(keyPair, "secp128r1")))
    }

    @Test
    fun `valid raw ecdsa signature verifies on secp224r1`() {
        val (keyPair, _) = generateKeyPair("secp224r1")
        val signature = signP1363(uid, keyPair, 28)
        assertEquals(56, signature.size)
        assertTrue(OriginalityVerifier.verify(uid, signature, nxpKeyFor(keyPair, "secp224r1")))
    }

    @Test
    fun `tampered signature is rejected`() {
        val (keyPair, _) = generateKeyPair("secp224r1")
        val signature = signP1363(uid, keyPair, 28)
        signature[3] = (signature[3].toInt() xor 0x01).toByte()
        assertFalse(OriginalityVerifier.verify(uid, signature, nxpKeyFor(keyPair, "secp224r1")))
    }

    @Test
    fun `signature over different uid is rejected`() {
        val (keyPair, _) = generateKeyPair("secp128r1")
        val signature = signP1363(uid, keyPair, 16)
        val otherUid = uid.copyOf().also { it[6] = 0x60 }
        assertFalse(OriginalityVerifier.verify(otherUid, signature, nxpKeyFor(keyPair, "secp128r1")))
    }

    @Test
    fun `match routes by signature length`() {
        // Random signatures do not verify against the real NXP keys.
        assertEquals(OriginalityStatus.Invalid, OriginalityVerifier.match(uid, ByteArray(32) { 0x11 }))
        assertEquals(OriginalityStatus.Invalid, OriginalityVerifier.match(uid, ByteArray(56) { 0x22 }))
        // Unknown signature length — no key family fits.
        assertEquals(OriginalityStatus.NotSupported, OriginalityVerifier.match(uid, ByteArray(48) { 0x33 }))
        assertEquals(OriginalityStatus.NotSupported, OriginalityVerifier.match(uid, ByteArray(0)))
    }

    @Test
    fun `nxp public key constants decode as valid curve points`() {
        for (key in OriginalityVerifier.DESFIRE_KEYS + OriginalityVerifier.NTAG_UL_KEYS) {
            // decodePoint throws on malformed constants; verify() would swallow it.
            val params = SECNamedCurves.getByName(key.curveName)
            params.curve.decodePoint(key.publicKey)
        }
    }
}
