package com.example

import com.example.crypto.MasterKeyProvider
import com.example.crypto.PinCrypto
import com.example.crypto.VaultCryptoEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.random.Random

class VaultCryptoTest {

    private class InMemoryMasterKeyProvider : MasterKeyProvider {
        private val masterKey: SecretKey = KeyGenerator.getInstance("AES").apply {
            init(256)
        }.generateKey()

        override fun getOrCreateMasterKey(): SecretKey = masterKey
        override fun isHardwareBacked(): Boolean = false
    }

    @Test
    fun `aes 256 gcm multi-chunk file encryption and decryption roundtrip`() {
        val engine = VaultCryptoEngine(InMemoryMasterKeyProvider())
        // 150 KiB spans 3 chunks (64 KiB + 64 KiB + 22 KiB)
        val plainData = Random(42).nextBytes(150 * 1024)
        val tempFile = File.createTempFile("vault_test", ".vlt")
        try {
            val writtenPlainLen = engine.encryptToFile(ByteArrayInputStream(plainData), tempFile)
            assertEquals(plainData.size.toLong(), writtenPlainLen)

            val decrypted = engine.decryptBytes(tempFile)
            assertArrayEquals(plainData, decrypted)
        } finally {
            tempFile.delete()
        }
    }

    @Test(expected = AEADBadTagException::class)
    fun `tampering with ciphertext fails gcm authentication`() {
        val engine = VaultCryptoEngine(InMemoryMasterKeyProvider())
        val plainData = "Rahasia Dokumen AES-256".encodeToByteArray()
        val encrypted = engine.encryptBytes(plainData)
        // Flip a byte inside the ciphertext payload
        encrypted[encrypted.size - 5] = (encrypted[encrypted.size - 5].toInt() xor 0xFF).toByte()
        engine.decryptBytes(encrypted)
    }

    @Test
    fun `encrypted media data source supports random access seeking`() {
        val engine = VaultCryptoEngine(InMemoryMasterKeyProvider())
        val plainData = Random(99).nextBytes(140_000)
        val tempFile = File.createTempFile("vault_media_test", ".vlt")
        try {
            engine.encryptToFile(ByteArrayInputStream(plainData), tempFile)
            val dataSource = engine.openEncryptedMediaDataSource(tempFile)
            assertEquals(plainData.size.toLong(), dataSource.size)

            // Read across chunk boundary (around 65536)
            val slice = ByteArray(1024)
            val read = dataSource.readAt(65000L, slice, 0, slice.size)
            assertEquals(1024, read)
            assertArrayEquals(plainData.copyOfRange(65000, 66024), slice)

            dataSource.close()
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `pbkdf2 pin hashing verifies matching pin and rejects wrong pin`() {
        val salt = PinCrypto.generateSalt()
        val hash = PinCrypto.hashPin("482910", salt)
        assertTrue(PinCrypto.verifyPin("482910", salt, hash))
        assertFalse(PinCrypto.verifyPin("000000", salt, hash))
    }
}
