package com.devanandsb.ccmanager.security

import com.devanandsb.ccmanager.data.CardSecret
import javax.crypto.AEADBadTagException
import javax.crypto.SecretKey

/** Plaintext card secrets as typed by the user. Blank means "not provided / keep existing". */
data class SecretDraft(
    val number: String = "",
    val expiry: String = "",
    val cvv: String = "",
    val holder: String = "",
) {
    val isEmpty get() = number.isBlank() && expiry.isBlank() && cvv.isBlank() && holder.isBlank()
}

data class EncryptedDraft(
    val number: ByteArray?,
    val expiry: ByteArray?,
    val cvv: ByteArray?,
    val holder: ByteArray?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncryptedDraft) return false
        return number.contentEquals(other.number) &&
            expiry.contentEquals(other.expiry) &&
            cvv.contentEquals(other.cvv) &&
            holder.contentEquals(other.holder)
    }

    override fun hashCode(): Int {
        var result = number?.contentHashCode() ?: 0
        result = 31 * result + (expiry?.contentHashCode() ?: 0)
        result = 31 * result + (cvv?.contentHashCode() ?: 0)
        result = 31 * result + (holder?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Field-level encryption for card secrets. Each field has its own Keystore key, and every key
 * requires a biometric or device-credential auth within the last [Keys.AUTH_WINDOW_SECONDS].
 * Calls throw [android.security.keystore.UserNotAuthenticatedException] outside that window, and
 * [Vault.KeyLost] when stored blobs can't be read any more because the keys were invalidated.
 */
object Vault {
    /** The vault keys died (screen lock removed or changed): what was stored with them is unreadable for good. */
    class KeyLost : Exception("Stored card details can't be read after the screen lock was changed. Re-enter them or restore a backup.")

    private val V2_TAG = byteArrayOf('C'.code.toByte(), 'C'.code.toByte(), 'V'.code.toByte(), '2'.code.toByte(), 0, 0, 0, 1)

    private fun alias(field: String) = "duely_vault_${field}_v1"
    private fun key(field: String) = Keys.getOrCreate(alias(field), requireAuth = true)

    private fun openAlias(field: String) = "cc_vault_${field}_v2"
    private fun openKey(field: String) = Keys.getOrCreate(openAlias(field), requireAuth = false)

    /** Runs [op] with the field's key. A dead key is deleted (so the next use makes a fresh one) and reported as [KeyLost]. */
    private fun <T> withKey(field: String, op: (SecretKey) -> T): T =
        try {
            op(key(field))
        } catch (e: Exception) {
            if (!Keys.isInvalidated(e)) throw e
            Keys.delete(alias(field))
            throw KeyLost()
        }

    private fun isV2(blob: ByteArray): Boolean {
        if (blob.size <= V2_TAG.size) return false
        for (i in V2_TAG.indices) if (blob[i] != V2_TAG[i]) return false
        return true
    }

    private fun enc(field: String, value: String): ByteArray? =
        value.trim().takeIf { it.isNotEmpty() }?.toByteArray()?.let { plain ->
            // Non-auth hardware Keystore key: saving card details does not prompt for fingerprint;
            // reveal and checkout autofill still enforce biometric / device lock.
            V2_TAG + Keys.encrypt(openKey(field), plain)
        }

    private fun dec(field: String, blob: ByteArray?): String? =
        blob?.let { b ->
            if (isV2(b)) {
                return String(Keys.decrypt(openKey(field), b.copyOfRange(V2_TAG.size, b.size)))
            }
            try {
                String(withKey(field) { Keys.decrypt(it, b) })
            } catch (_: AEADBadTagException) {
                throw KeyLost() // sealed under an earlier key that was since replaced
            }
        }

    fun encrypt(d: SecretDraft) = EncryptedDraft(
        number = enc("number", d.number.filter(Char::isDigit)),
        expiry = enc("expiry", d.expiry),
        cvv = enc("cvv", d.cvv),
        holder = enc("holder", d.holder),
    )

    fun decrypt(s: CardSecret) = SecretDraft(
        number = dec("number", s.numberEnc).orEmpty(),
        expiry = dec("expiry", s.expiryEnc).orEmpty(),
        cvv = dec("cvv", s.cvvEnc).orEmpty(),
        holder = dec("holder", s.holderEnc).orEmpty(),
    )
}
