package com.ultrabytecoder.coinsafebox.security.hardware.jna

import com.sun.jna.Pointer
import java.nio.charset.StandardCharsets

/**
 * JNA representation of the libsecret `SecretSchema` C struct.
 *
 * The struct layout (from `secret-schema.h`):
 * ```c
 * typedef struct {
 *     const gchar *name;                       // offset  0   (8 bytes)
 *     SecretSchemaFlags flags;                 // offset  8   (4 bytes)
 *     /* pad */                                // offset 12   (4 bytes)
 *     SecretSchemaAttribute attributes[32];     // offset 16   (32 × 16 = 512 bytes)
 *     gint reserved;                           // offset 528  (4 bytes)
 *     /* pad */                                // offset 532  (4 bytes)
 *     gpointer reserved1..reserved7;           // offset 536  (7 × 8 = 56 bytes)
 * } SecretSchema;                             // total = 592 bytes
 * ```
 *
 * `SecretSchemaAttribute` = `{ const gchar *name; SecretSchemaAttributeType type; }`
 * = 16 bytes on 64-bit (8 + 4 + 4 padding).
 *
 * All native allocations use [GLib.g_malloc0] which returns a plain [Pointer]
 * **without** a JNA `Cleaner`.  The caller must free every returned pointer
 * with [GLib.g_free] — this is the sole deallocation path and there is no
 * risk of a double-free from JNA's finaliser.
 */
object SecretSchemaFactory {

    private const val MAX_ATTRIBUTES = 32

    /**
     * Creates a native `SecretSchema` with one string attribute.
     *
     * @param glib the loaded [GLib] instance (used for `g_malloc0`).
     * @return the schema pointer and all backing [Pointer] allocations to free
     *         with [GLib.g_free].
     */
    fun create(glib: GLib, name: String, attributeName: String): Pair<Pointer, List<Pointer>> {
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        val nameMem = glib.g_malloc0((nameBytes.size + 1).toLong())!!
        nameMem.write(0, nameBytes, 0, nameBytes.size)

        val attrNameBytes = attributeName.toByteArray(StandardCharsets.UTF_8)
        val attrNameMem = glib.g_malloc0((attrNameBytes.size + 1).toLong())!!
        attrNameMem.write(0, attrNameBytes, 0, attrNameBytes.size)

        // SecretSchemaAttribute = { const gchar* name; int type; } = 16 bytes on 64-bit
        val attrSize = 16L
        // SecretSchema = { ptr name; int flags; int pad; attributes[32]; int reserved; int pad; 7*ptr }
        val structSize = 8L + 4L + 4L + (MAX_ATTRIBUTES * attrSize) + 4L + 4L + (7 * 8L)

        val mem = glib.g_malloc0(structSize)!!
        var offset = 0L
        mem.setPointer(offset, nameMem); offset += 8
        mem.setInt(offset, 0); offset += 4
        mem.setInt(offset, 0); offset += 4
        // attributes[0]: { name, type=SECRET_SCHEMA_ATTRIBUTE_STRING(0) }
        mem.setPointer(offset, attrNameMem); offset += 8
        mem.setInt(offset, 0); offset += 4
        mem.setInt(offset, 0); offset += 4
        // attributes[1..31]: zeroed (g_malloc0 zero-initialises)
        offset += (MAX_ATTRIBUTES - 1) * attrSize
        // reserved + padding + reserved1..7: zeroed (g_malloc0 zero-initialises)

        return mem to listOf(mem, nameMem, attrNameMem)
    }
}
