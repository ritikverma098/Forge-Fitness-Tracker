package com.forge.workout.backup

import org.json.JSONArray
import org.json.JSONObject

/** Counts compact JSON as its containers are constructed; each nested container is reserved once. */
internal class BackupJsonBudget(private val limit: Long = BackupCodec.MAX_BYTES.toLong()) {
    var reservedBytes: Long = 0
        private set

    init { require(limit >= 0) }

    fun objectValue(vararg pairs: Pair<String, Any?>): JSONObject {
        var localBytes = 2L
        pairs.forEachIndexed { index, (key, value) ->
            localBytes += bytes(JSONObject.quote(key)) + 1 + if (index == 0) 0 else 1
            localBytes += scalarBytes(value)
        }
        reserve(localBytes)
        return JSONObject().apply { pairs.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) } }
    }

    fun arrayValue(values: List<Any?>): JSONArray {
        var localBytes = 2L + (values.size - 1).coerceAtLeast(0)
        values.forEach { localBytes += scalarBytes(it) }
        reserve(localBytes)
        return JSONArray().apply { values.forEach { put(it ?: JSONObject.NULL) } }
    }

    /** Converter output is individually bounded and budgeted before allocating its parsed object graph. */
    fun snapshotObject(text: String): JSONObject {
        val size = bytes(text)
        require(size <= MAX_SNAPSHOT_BYTES) { "Food snapshot is too large." }
        reserve(size)
        val parsed = JSONObject(text)
        // Parsing can normalize number spellings or escapes; account for the emitted compact form.
        val compactSize = bytes(parsed.toString())
        if (compactSize > size) reserve(compactSize - size) else reservedBytes -= size - compactSize
        return parsed
    }

    private fun scalarBytes(value: Any?): Long = when {
        value == null || value === JSONObject.NULL -> 4
        value is JSONObject || value is JSONArray -> 0
        value is String -> bytes(JSONObject.quote(value))
        value is Number -> bytes(JSONObject.numberToString(value))
        value is Boolean -> if (value) 4 else 5
        else -> throw IllegalArgumentException("Unsupported backup JSON value.")
    }

    private fun reserve(count: Long) {
        require(count >= 0 && count <= limit - reservedBytes) { "Backup exceeds the size limit." }
        reservedBytes += count
    }

    private fun bytes(text: String): Long = text.toByteArray(Charsets.UTF_8).size.toLong()
    private companion object { const val MAX_SNAPSHOT_BYTES = 512 * 1024 }
}
