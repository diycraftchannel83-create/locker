package com.example.crypto

import android.media.MediaDataSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Chunked Authenticated AES-256-GCM Encryption Engine.
 *
 * File Envelope Format ("BVLT" v1):
 * - Magic: 4 bytes ("BVLT")
 * - Version: 1 byte (1)
 * - WrappedKeyIvLength: 1 byte (12)
 * - WrappedKeyIv: 12 bytes
 * - WrappedDekLength: 2 bytes (48 bytes = 32B AES-256 DEK + 16B GCM tag)
 * - WrappedDek: 48 bytes (encrypted with Android Keystore AES-256 Master Key)
 * - ChunkSize: 4 bytes (65536 bytes)
 * - TotalPlaintextSize: 8 bytes (Long)
 * Total Header Size = 80 bytes.
 *
 * Followed by sequential chunks:
 * - CiphertextLength: 4 bytes
 * - ChunkIv: 12 bytes (unique per chunk)
 * - ChunkCiphertextAndTag: (plaintextChunkLen + 16) bytes with AAD = 8-byte big-endian chunkIndex
 */
class VaultCryptoEngine(
    private val masterKeyProvider: MasterKeyProvider
) {
    companion object {
        private val MAGIC = byteArrayOf('B'.code.toByte(), 'V'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte())
        private const val VERSION: Byte = 1
        private const val AES_ALGORITHM = "AES"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = 16
        private const val GCM_IV_BYTES = 12
        private const val DEK_BYTES = 32 // AES-256
        const val DEFAULT_CHUNK_SIZE = 64 * 1024 // 64 KiB
        const val FIXED_HEADER_SIZE = 4 + 1 + 1 + 12 + 2 + 48 + 4 + 8 // 80 bytes
    }

    private val secureRandom = SecureRandom()

    data class EncryptedFileHeader(
        val dek: SecretKey,
        val chunkSize: Int,
        val totalPlaintextSize: Long,
        val headerByteLength: Int
    )

    private fun generateFileDek(): ByteArray {
        val dekBytes = ByteArray(DEK_BYTES)
        secureRandom.nextBytes(dekBytes)
        return dekBytes
    }

    private fun wrapDek(rawDek: ByteArray): Pair<ByteArray, ByteArray> {
        val masterKey = masterKeyProvider.getOrCreateMasterKey()
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey)
        val wrapped = cipher.doFinal(rawDek)
        val iv = cipher.iv
        return iv to wrapped
    }

    private fun unwrapDek(iv: ByteArray, wrappedDek: ByteArray): SecretKey {
        val masterKey = masterKeyProvider.getOrCreateMasterKey()
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, masterKey, spec)
        val rawDek = cipher.doFinal(wrappedDek)
        return try {
            SecretKeySpec(rawDek, AES_ALGORITHM)
        } finally {
            rawDek.fill(0)
        }
    }

    private fun chunkAad(chunkIndex: Long): ByteArray {
        return ByteBuffer.allocate(8).putLong(chunkIndex).array()
    }

    fun encryptStream(
        input: InputStream,
        output: OutputStream,
        totalPlaintextSize: Long
    ) {
        val rawDek = generateFileDek()
        val dekSpec = SecretKeySpec(rawDek, AES_ALGORITHM)
        val (wrapIv, wrappedDek) = wrapDek(rawDek)
        rawDek.fill(0)

        val dos = DataOutputStream(output)
        dos.write(MAGIC)
        dos.writeByte(VERSION.toInt())
        dos.writeByte(wrapIv.size)
        dos.write(wrapIv)
        dos.writeShort(wrappedDek.size)
        dos.write(wrappedDek)
        dos.writeInt(DEFAULT_CHUNK_SIZE)
        dos.writeLong(totalPlaintextSize)

        val buffer = ByteArray(DEFAULT_CHUNK_SIZE)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        var chunkIndex = 0L

        while (true) {
            val bytesRead = readFullyUpTo(input, buffer)
            if (bytesRead <= 0) break

            val chunkIv = ByteArray(GCM_IV_BYTES)
            secureRandom.nextBytes(chunkIv)
            cipher.init(Cipher.ENCRYPT_MODE, dekSpec, GCMParameterSpec(GCM_TAG_BITS, chunkIv))
            cipher.updateAAD(chunkAad(chunkIndex))
            val encryptedChunk = cipher.doFinal(buffer, 0, bytesRead)

            dos.writeInt(encryptedChunk.size)
            dos.write(chunkIv)
            dos.write(encryptedChunk)
            chunkIndex++
        }
        dos.flush()
    }

    /**
     * Streams plaintext from [input] into [destFile] with AES-256-GCM chunked encryption
     * and records the exact total plaintext byte count in the file header.
     */
    fun encryptToFile(input: InputStream, destFile: File): Long {
        var totalBytes = 0L
        val countingInput = object : InputStream() {
            override fun read(): Int {
                val b = input.read()
                if (b != -1) totalBytes++
                return b
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = input.read(b, off, len)
                if (n > 0) totalBytes += n
                return n
            }
        }
        destFile.outputStream().buffered().use { out ->
            encryptStream(countingInput, out, 0L)
        }
        // Patch totalPlaintextSize at offset 72 (4 magic + 1 ver + 1 ivLen + 12 iv + 2 dekLen + 48 dek + 4 chunkSize)
        RandomAccessFile(destFile, "rw").use { raf ->
            raf.seek(72L)
            raf.writeLong(totalBytes)
        }
        return totalBytes
    }

    fun decryptStream(input: InputStream, output: OutputStream) {
        val dis = DataInputStream(input)
        val header = readHeader(dis)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        var chunkIndex = 0L

        while (true) {
            val cipherLen = try {
                dis.readInt()
            } catch (_: EOFException) {
                break
            }
            if (cipherLen <= GCM_TAG_BYTES) {
                throw SecurityException("Corrupted encrypted chunk length")
            }
            val chunkIv = ByteArray(GCM_IV_BYTES)
            dis.readFully(chunkIv)
            val encryptedChunk = ByteArray(cipherLen)
            dis.readFully(encryptedChunk)

            cipher.init(Cipher.DECRYPT_MODE, header.dek, GCMParameterSpec(GCM_TAG_BITS, chunkIv))
            cipher.updateAAD(chunkAad(chunkIndex))
            val plainChunk = cipher.doFinal(encryptedChunk)
            output.write(plainChunk)
            chunkIndex++
        }
        output.flush()
    }

    fun encryptBytes(plainBytes: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream(plainBytes.size + 128)
        encryptStream(ByteArrayInputStream(plainBytes), baos, plainBytes.size.toLong())
        return baos.toByteArray()
    }

    fun decryptBytes(encryptedFile: File): ByteArray {
        val baos = ByteArrayOutputStream()
        encryptedFile.inputStream().buffered().use { input ->
            decryptStream(input, baos)
        }
        return baos.toByteArray()
    }

    fun decryptBytes(encryptedData: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        decryptStream(ByteArrayInputStream(encryptedData), baos)
        return baos.toByteArray()
    }

    fun readHeader(file: File): EncryptedFileHeader {
        return file.inputStream().buffered().use { readHeader(DataInputStream(it)) }
    }

    private fun readHeader(dis: DataInputStream): EncryptedFileHeader {
        val magic = ByteArray(4)
        dis.readFully(magic)
        if (!magic.contentEquals(MAGIC)) {
            throw SecurityException("Format file brankas tidak valid")
        }
        val version = dis.readByte()
        if (version != VERSION) {
            throw SecurityException("Versi enkripsi tidak didukung: $version")
        }
        val wrapIvLen = dis.readUnsignedByte()
        val wrapIv = ByteArray(wrapIvLen)
        dis.readFully(wrapIv)
        val wrappedDekLen = dis.readUnsignedShort()
        val wrappedDek = ByteArray(wrappedDekLen)
        dis.readFully(wrappedDek)
        val chunkSize = dis.readInt()
        val totalPlaintextSize = dis.readLong()
        val dek = unwrapDek(wrapIv, wrappedDek)
        val headerBytes = 4 + 1 + 1 + wrapIvLen + 2 + wrappedDekLen + 4 + 8
        return EncryptedFileHeader(dek, chunkSize, totalPlaintextSize, headerBytes)
    }

    /**
     * Creates a zero-disk-leakage [MediaDataSource] for playing AES-256-GCM encrypted videos
     * directly in memory with random-access seeking.
     */
    fun openEncryptedMediaDataSource(encryptedFile: File): MediaDataSource {
        val header = readHeader(encryptedFile)
        val raf = RandomAccessFile(encryptedFile, "r")
        val chunkStride = 4L + GCM_IV_BYTES + header.chunkSize + GCM_TAG_BYTES

        return object : MediaDataSource() {
            private var cachedChunkIdx: Long = -1L
            private var cachedChunkPlain: ByteArray = ByteArray(0)
            private val lock = Any()

            override fun getSize(): Long = header.totalPlaintextSize

            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (position >= header.totalPlaintextSize) return -1
                if (size == 0) return 0

                val bytesToRead = minOf(size.toLong(), header.totalPlaintextSize - position).toInt()
                var totalCopied = 0
                var currentPos = position

                synchronized(lock) {
                    while (totalCopied < bytesToRead) {
                        val chunkIdx = currentPos / header.chunkSize
                        val inChunkOffset = (currentPos % header.chunkSize).toInt()
                        val plainChunk = loadChunk(chunkIdx)
                        val available = plainChunk.size - inChunkOffset
                        if (available <= 0) break

                        val copyLen = minOf(available, bytesToRead - totalCopied)
                        System.arraycopy(plainChunk, inChunkOffset, buffer, offset + totalCopied, copyLen)
                        totalCopied += copyLen
                        currentPos += copyLen
                    }
                }
                return if (totalCopied == 0) -1 else totalCopied
            }

            private fun loadChunk(chunkIdx: Long): ByteArray {
                if (chunkIdx == cachedChunkIdx) return cachedChunkPlain

                val fileOffset = header.headerByteLength.toLong() + chunkIdx * chunkStride
                raf.seek(fileOffset)
                val cipherLen = raf.readInt()
                val iv = ByteArray(GCM_IV_BYTES)
                raf.readFully(iv)
                val encrypted = ByteArray(cipherLen)
                raf.readFully(encrypted)

                val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, header.dek, GCMParameterSpec(GCM_TAG_BITS, iv))
                cipher.updateAAD(chunkAad(chunkIdx))
                val plain = cipher.doFinal(encrypted)

                cachedChunkIdx = chunkIdx
                cachedChunkPlain = plain
                return plain
            }

            override fun close() {
                synchronized(lock) {
                    cachedChunkPlain.fill(0)
                    cachedChunkIdx = -1L
                    raf.close()
                }
            }
        }
    }

    private fun readFullyUpTo(input: InputStream, buffer: ByteArray): Int {
        var totalRead = 0
        while (totalRead < buffer.size) {
            val read = input.read(buffer, totalRead, buffer.size - totalRead)
            if (read == -1) break
            totalRead += read
        }
        return totalRead
    }
}

object PinCrypto {
    private const val ITERATIONS = 100_000
    private const val KEY_LENGTH_BITS = 256

    fun generateSalt(): ByteArray {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        return salt
    }

    fun hashPin(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return try {
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun verifyPin(pin: String, salt: ByteArray, expectedHash: ByteArray): Boolean {
        val actualHash = hashPin(pin, salt)
        return MessageDigest.isEqual(actualHash, expectedHash)
    }
}
