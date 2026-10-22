package team.sopa.boar.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import team.sopa.boar.engine.chat.ChatStore
import team.sopa.boar.engine.security.AesTextCipher
import team.sopa.boar.engine.security.PinKeys
import team.sopa.boar.engine.security.PlainCipher
import team.sopa.boar.engine.settings.AppSettings
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The PIN that opens BOAR and the key its chats are sealed with. The chats' data key is stored sealed twice: with the
 * PIN (PBKDF2) and then with a non-exportable Android Keystore key, so the files copied off the phone (USB, a backup)
 * can't be read or brute-forced elsewhere. The PIN itself is never stored. Until it's entered, chats stay sealed.
 */
class PinLock(
    private val settings: AppSettings,
    private val chats: ChatStore,
    /** The Keystore key's name; tests use their own so they never touch the app's real key. */
    private val alias: String = "boar-pin-v1",
) {
    sealed interface Attempt {
        data object Ok : Attempt
        data class Wrong(val waitSeconds: Long) : Attempt
        data class Wait(val seconds: Long) : Attempt
    }

    val isSet: Boolean get() = settings.pinWrapped != null

    /** Called after a PIN is set, changed or removed: the screen's protection follows it at once (MainActivity). */
    @Volatile var onChange: (() -> Unit)? = null

    /** Seconds before another try is allowed (after too many wrong PINs). */
    fun waitSeconds(now: Long = System.currentTimeMillis()): Long = ((settings.pinLockedUntil - now) / 1000).coerceAtLeast(0)

    fun unlock(pin: String): Attempt {
        val wait = waitSeconds()
        if (wait > 0) return Attempt.Wait(wait)
        val dek = dataKey(pin)
        if (dek == null) {
            val failures = settings.pinFailures + 1
            settings.pinFailures = failures
            val backoff = PinKeys.backoffSeconds(failures)
            settings.pinLockedUntil = System.currentTimeMillis() + backoff * 1000
            return Attempt.Wrong(backoff)
        }
        settings.pinFailures = 0
        settings.pinLockedUntil = 0
        chats.cipher = AesTextCipher(dek)
        return Attempt.Ok
    }

    /** Turns the PIN on: a new data key, and every stored chat sealed with it. */
    fun enable(pin: String) {
        require(PinKeys.valid(pin))
        val dek = PinKeys.newDataKey()
        val cipher = AesTextCipher(dek)
        // The key is saved before any chat is sealed with it, so a failure can't leave chats sealed with a lost key.
        store(pin, dek)
        try {
            chats.reseal(PlainCipher, cipher)
        } catch (e: Exception) {
            clear() // the reseal is one transaction: the chats are still plain
            throw e
        }
        chats.cipher = cipher
    }

    /** A new PIN for the same data key (the chats aren't touched). False when [old] is wrong. */
    fun change(old: String, new: String): Boolean {
        require(PinKeys.valid(new))
        val dek = dataKey(old) ?: return false
        store(new, dek)
        return true
    }

    /** Turns the PIN off: the chats go back to plain text. False when [pin] is wrong. */
    fun disable(pin: String): Boolean {
        val dek = dataKey(pin) ?: return false
        chats.reseal(AesTextCipher(dek), PlainCipher)
        chats.cipher = PlainCipher
        clear()
        return true
    }

    /** Forgot the PIN: the chats can't be opened any more, so they're erased with the key. */
    fun forget() {
        chats.clearAll()
        chats.cipher = PlainCipher
        clear()
    }

    private fun dataKey(pin: String): ByteArray? {
        val wrapped = settings.pinWrapped ?: return null
        val salt = settings.pinSalt ?: return null
        val pinSealed = runCatching { keystoreOpen(Base64.decode(wrapped, Base64.NO_WRAP)) }.getOrNull() ?: return null
        return PinKeys.unwrap(pinSealed, PinKeys.derive(pin, Base64.decode(salt, Base64.NO_WRAP), settings.pinIterations))
    }

    private fun store(pin: String, dek: ByteArray) {
        val salt = PinKeys.newSalt()
        val pinSealed = PinKeys.wrap(dek, PinKeys.derive(pin, salt))
        settings.pinSalt = Base64.encodeToString(salt, Base64.NO_WRAP)
        settings.pinIterations = PinKeys.ITERATIONS
        settings.pinWrapped = Base64.encodeToString(keystoreSeal(pinSealed), Base64.NO_WRAP)
        settings.pinFailures = 0
        settings.pinLockedUntil = 0
        onChange?.invoke()
    }

    private fun clear() {
        settings.pinWrapped = null
        settings.pinSalt = null
        settings.pinFailures = 0
        settings.pinLockedUntil = 0
        runCatching { keyStore().deleteEntry(alias) }
        onChange?.invoke()
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun deviceKey(): SecretKey = (keyStore().getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
        init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        generateKey()
    }

    private fun keystoreSeal(b: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, deviceKey()) }
        return c.iv + c.doFinal(b)
    }

    private fun keystoreOpen(b: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, deviceKey(), GCMParameterSpec(128, b, 0, 12)) }
        return c.doFinal(b, 12, b.size - 12)
    }

}
