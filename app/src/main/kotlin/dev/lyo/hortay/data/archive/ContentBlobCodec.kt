package dev.lyo.hortay.data.archive

import java.security.MessageDigest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf

/**
 * Encodes and decodes [TdlibContentMeta] to/from a compact on-disk binary blob.
 *
 * ## Format
 * A 4-byte magic header (`TDLB`) + 4-byte version + ProtoBuf-encoded [TdlibContentMeta].
 * The magic + version prefix lets the archive DB detect format drift on read and
 * surface a graceful "snapshot format unsupported" rather than a deserialization crash.
 *
 * ## Why no raw TdApi.MessageContent
 * [org.drinkless.tdlib.TdApi.MessageContent] is a generated Java class with no
 * binary serialization API (`toByteArray` / `fromByteArray` don't exist). Using
 * the TLO wire format would require vendoring the TDLib TLO schema and writing a
 * custom parser — a maintenance burden that outweighs the benefit. The
 * [TdlibContentMeta] fields carry all the information needed for archive diffing
 * and search; TDLib's own local DB remains the source of truth for live content.
 */
@OptIn(ExperimentalSerializationApi::class)
object ContentBlobCodec {

    private const val MAGIC: Int = 0x54444C42  // "TDLB"
    private const val VERSION: Int = 1

    /**
     * Encodes [meta] into a versioned binary blob.
     * Deterministic: encoding the same [TdlibContentMeta] always produces identical bytes.
     */
    fun encode(meta: TdlibContentMeta): ByteArray {
        val protoBytes = ProtoBuf.encodeToByteArray(TdlibContentMeta.serializer(), meta)
        val out = java.io.ByteArrayOutputStream(8 + protoBytes.size)
        java.io.DataOutputStream(out).use { dos ->
            dos.writeInt(MAGIC)
            dos.writeInt(VERSION)
            dos.write(protoBytes)
        }
        return out.toByteArray()
    }

    /**
     * Decodes a blob produced by [encode].
     * @throws IllegalArgumentException on magic/version mismatch.
     */
    fun decode(blob: ByteArray): TdlibContentMeta {
        java.io.DataInputStream(java.io.ByteArrayInputStream(blob)).use { dis ->
            val magic = dis.readInt()
            require(magic == MAGIC) { "Not a content blob (magic 0x${magic.toString(16)})" }
            val version = dis.readInt()
            require(version == VERSION) { "Unsupported blob version $version" }
            val protoBytes = dis.readBytes()
            return ProtoBuf.decodeFromByteArray(TdlibContentMeta.serializer(), protoBytes)
        }
    }

    /**
     * Returns the SHA-256 hex digest of [blob].
     * Used by the archive repository to detect content changes (unchanged blobs → no new snapshot row).
     */
    fun hash(blob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
