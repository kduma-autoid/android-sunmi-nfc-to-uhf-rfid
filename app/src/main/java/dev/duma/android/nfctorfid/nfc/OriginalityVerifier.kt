package dev.duma.android.nfctorfid.nfc

import dev.duma.android.nfctorfid.epc.hexToBytesOrNull
import java.math.BigInteger
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner

sealed class OriginalityStatus {
    /** Signature verified against a known NXP public key. */
    data class Verified(val chipName: String) : OriginalityStatus()

    /** Signature was read but matches no known NXP key — possible counterfeit. */
    data object Invalid : OriginalityStatus()

    /** Tag does not expose an originality signature (or rejected the command). */
    data object NotSupported : OriginalityStatus()

    /** I/O failure while reading (e.g. the tag left the field). */
    data object ReadError : OriginalityStatus()
}

/**
 * NXP Originality Check: raw (unhashed) ECDSA over the tag UID, signature in
 * IEEE P1363 form (fixed-width R || S). Public keys from Proxmark3
 * `client/src/crypto/originality.c`; the keys are public by design.
 */
object OriginalityVerifier {

    class NxpKey(val chipName: String, val curveName: String, publicKeyHex: String) {
        val publicKey: ByteArray = requireNotNull(publicKeyHex.hexToBytesOrNull())
    }

    /** secp224r1, 56-byte signatures — DESFire / NTAG4xx family (Read_Sig via IsoDep). */
    val DESFIRE_KEYS = listOf(
        NxpKey(
            "DESFire EV3", "secp224r1",
            "041DB46C145D0A36539C6544BD6D9B0AA62FF91EC48CBC6ABAE36E0089A46F0D" +
                "08C8A715EA40A63313B92E90DDC1730230E0458A33276FB743",
        ),
        NxpKey(
            "DESFire EV2 / NTAG424 DNA", "secp224r1",
            "04B304DC4C615F5326FE9383DDEC9AA892DF3A57FA7FFB3276192BC0EAA252ED" +
                "45A865E3B093A3D0DCE5BE29E92F1392CE7DE321E3E5C52B3A",
        ),
        NxpKey(
            "DESFire EV2 / Light", "secp224r1",
            "048A9B380AF2EE1B98DC417FECC263F8449C7625CECE82D9B916C992DA209D68" +
                "422B81EC20B65A66B5102A61596AF3379200599316A00A1410",
        ),
        NxpKey(
            "DESFire EV2 XL", "secp224r1",
            "04CD5D45E50B1502F0BA4656FF37669597E7E183251150F9574CC8DA56BF01C7" +
                "ABE019E29FEA48F9CE22C3EA4029A765E1BC95A89543BAD1BC",
        ),
        NxpKey(
            "DESFire EV1 / NTAG413 DNA", "secp224r1",
            "04BB5D514F7050025C7D0F397310360EEC91EAF792E96FC7E0F496CB4E669D41" +
                "4F877B7B27901FE67C2E3B33CD39D1C797715189AC951C2ADD",
        ),
        NxpKey(
            "DESFire Light", "secp224r1",
            "040E98E117AAA36457F43173DC920A8757267F44CE4EC5ADD3C54075571AEBBF" +
                "7B942A9774A1D94AD02572427E5AE0A2DD36591B1FB34FCF3D",
        ),
    )

    /** secp128r1, 32-byte signatures — NTAG21x / Ultralight (READ_SIG via NFC-A). */
    val NTAG_UL_KEYS = listOf(
        NxpKey(
            "NTAG21x", "secp128r1",
            "04494E1A386D3D3CFE3DC10E5DE68A499B1C202DB5B132393E89ED19FE5BE8BC61",
        ),
        NxpKey(
            "Ultralight EV1", "secp128r1",
            "0490933BDCD6E99B4E255E3DA55389A827564E11718E017292FAF23226A96614B8",
        ),
        NxpKey(
            "Ultralight", "secp128r1",
            "04A748B6A632FBEE2C0897702B33BEA1C074998E17B84ACA04FF267E5D2C91F6DC",
        ),
    )

    /** Matches [signature] over [uid] against all keys fitting the signature length. */
    fun match(uid: ByteArray, signature: ByteArray): OriginalityStatus {
        val keys = when (signature.size) {
            56 -> DESFIRE_KEYS
            32 -> NTAG_UL_KEYS
            else -> return OriginalityStatus.NotSupported
        }
        for (key in keys) {
            if (verify(uid, signature, key)) return OriginalityStatus.Verified(key.chipName)
        }
        return OriginalityStatus.Invalid
    }

    /** Raw ECDSA verification: message = UID bytes, no hashing, P1363 signature. */
    fun verify(uid: ByteArray, signature: ByteArray, key: NxpKey): Boolean {
        if (signature.isEmpty() || signature.size % 2 != 0) return false
        return try {
            val params = SECNamedCurves.getByName(key.curveName)
            val domain = ECDomainParameters(params.curve, params.g, params.n, params.h)
            val publicKey = ECPublicKeyParameters(params.curve.decodePoint(key.publicKey), domain)
            val half = signature.size / 2
            val r = BigInteger(1, signature.copyOfRange(0, half))
            val s = BigInteger(1, signature.copyOfRange(half, signature.size))
            val signer = ECDSASigner()
            signer.init(false, publicKey)
            signer.verifySignature(uid, r, s)
        } catch (e: Exception) {
            false
        }
    }
}
