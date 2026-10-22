package com.forge.workout.nutrition.catalog

/** Rejects permissive JSONObject extensions and limits recursion before Android parses JSON. */
internal class CatalogJsonSyntax(private val source: String) {
    private var position = 0

    fun validate() {
        value(0)
        whitespace()
        require(position == source.length) { "Trailing JSON content." }
    }

    private fun value(depth: Int) {
        whitespace()
        require(position < source.length) { "Truncated JSON." }
        when (source[position]) {
            '{' -> objectValue(depth + 1)
            '[' -> arrayValue(depth + 1)
            '"' -> string()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            else -> number()
        }
    }

    private fun objectValue(depth: Int) {
        require(depth <= 16) { "JSON is too deeply nested." }
        position++
        whitespace()
        if (consume('}')) return
        val keys = HashSet<String>()
        do {
            whitespace()
            require(keys.add(string())) { "Duplicate JSON field." }
            whitespace()
            require(consume(':')) { "Missing JSON separator." }
            value(depth)
            whitespace()
            if (consume('}')) return
            require(consume(',')) { "Missing JSON separator." }
        } while (true)
    }

    private fun arrayValue(depth: Int) {
        require(depth <= 16) { "JSON is too deeply nested." }
        position++
        whitespace()
        if (consume(']')) return
        do {
            value(depth)
            whitespace()
            if (consume(']')) return
            require(consume(',')) { "Missing JSON separator." }
        } while (true)
    }

    private fun string(): String {
        require(consume('"')) { "Expected JSON string." }
        val result = StringBuilder()
        while (position < source.length) {
            val character = source[position++]
            when {
                character == '"' -> return result.toString()
                character == '\\' -> {
                    require(position < source.length) { "Truncated JSON escape." }
                    result.append(when (val escaped = source[position++]) {
                        '"', '\\', '/' -> escaped
                        'b' -> '\b'
                        'f' -> '\u000c'
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        'u' -> {
                            require(position + 4 <= source.length) { "Truncated Unicode escape." }
                            val hex = source.substring(position, position + 4)
                            require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "Invalid Unicode escape." }
                            position += 4
                            hex.toInt(16).toChar()
                        }
                        else -> throw IllegalArgumentException("Invalid JSON escape.")
                    })
                }
                character.code < 32 -> throw IllegalArgumentException("Unescaped control character.")
                else -> result.append(character)
            }
        }
        throw IllegalArgumentException("Unterminated JSON string.")
    }

    private fun number() {
        consume('-')
        require(position < source.length) { "Truncated JSON number." }
        if (!consume('0')) {
            require(source[position] in '1'..'9') { "Invalid JSON number." }
            digits()
        }
        if (consume('.')) require(digits()) { "Invalid JSON fraction." }
        if (consume('e') || consume('E')) {
            if (!consume('+')) consume('-')
            require(digits()) { "Invalid JSON exponent." }
        }
    }

    private fun digits(): Boolean {
        val start = position
        while (position < source.length && source[position] in '0'..'9') position++
        return position > start
    }

    private fun literal(value: String) {
        require(source.startsWith(value, position)) { "Invalid JSON literal." }
        position += value.length
    }

    private fun consume(character: Char): Boolean {
        if (position < source.length && source[position] == character) { position++; return true }
        return false
    }

    private fun whitespace() {
        while (position < source.length && source[position] in " \r\n\t") position++
    }
}
