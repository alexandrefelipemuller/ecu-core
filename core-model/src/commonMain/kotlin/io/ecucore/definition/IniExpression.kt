package io.ecucore.definition

/**
 * Avaliador mínimo das expressões `{ ... }` dos .ini do TunerStudio usadas em escala/
 * translate de campos, p.ex. `{ ((algorithm == 0) || (algorithm == 2)) ? 2.000 : 0.500 }`
 * e `{ 0.1 / stoich }`.
 *
 * Suporta números, identificadores, `+ - * /`, comparações, `&& || !`, ternário e
 * parênteses. Qualquer coisa fora disso (ex.: `bitStringValue(...)`) falha e devolve null,
 * para o chamador cair no comportamento antigo (escala fixa).
 */
internal class IniExpression(private val source: String, private val resolve: (String) -> Double?) {
    private var pos = 0

    fun evaluate(): Double? = try {
        val value = parseTernary()
        skipWs()
        if (pos == source.length) value else null
    } catch (_: IllegalStateException) {
        null
    }

    private fun skipWs() {
        while (pos < source.length && source[pos].isWhitespace()) pos++
    }

    private fun peek(text: String): Boolean {
        skipWs()
        return source.startsWith(text, pos)
    }

    private fun eat(text: String): Boolean {
        if (!peek(text)) return false
        pos += text.length
        return true
    }

    private fun parseTernary(): Double {
        val cond = parseOr()
        if (eat("?")) {
            val a = parseTernary()
            check(eat(":")) { "expected :" }
            val b = parseTernary()
            return if (cond != 0.0) a else b
        }
        return cond
    }

    private fun parseOr(): Double {
        var left = parseAnd()
        while (eat("||")) {
            val right = parseAnd()
            left = if (left != 0.0 || right != 0.0) 1.0 else 0.0
        }
        return left
    }

    private fun parseAnd(): Double {
        var left = parseCompare()
        while (eat("&&")) {
            val right = parseCompare()
            left = if (left != 0.0 && right != 0.0) 1.0 else 0.0
        }
        return left
    }

    private fun parseCompare(): Double {
        val left = parseAdd()
        for (op in listOf("==", "!=", "<=", ">=", "<", ">")) {
            if (eat(op)) {
                val right = parseAdd()
                val result = when (op) {
                    "==" -> left == right
                    "!=" -> left != right
                    "<=" -> left <= right
                    ">=" -> left >= right
                    "<" -> left < right
                    else -> left > right
                }
                return if (result) 1.0 else 0.0
            }
        }
        return left
    }

    private fun parseAdd(): Double {
        var left = parseMul()
        while (true) {
            left = when {
                eat("+") -> left + parseMul()
                eat("-") -> left - parseMul()
                else -> return left
            }
        }
    }

    private fun parseMul(): Double {
        var left = parseUnary()
        while (true) {
            left = when {
                eat("*") -> left * parseUnary()
                eat("/") -> {
                    val right = parseUnary()
                    check(right != 0.0) { "div by zero" }
                    left / right
                }
                else -> return left
            }
        }
    }

    private fun parseUnary(): Double {
        if (eat("!")) return if (parseUnary() == 0.0) 1.0 else 0.0
        if (eat("-")) return -parseUnary()
        return parsePrimary()
    }

    private fun parsePrimary(): Double {
        skipWs()
        if (eat("(")) {
            val value = parseTernary()
            check(eat(")")) { "expected )" }
            return value
        }
        val start = pos
        while (pos < source.length && (source[pos].isLetterOrDigit() || source[pos] == '.' || source[pos] == '_')) pos++
        val token = source.substring(start, pos)
        check(token.isNotEmpty()) { "unexpected char at $pos" }
        token.toDoubleOrNull()?.let { return it }
        skipWs()
        check(!(pos < source.length && source[pos] == '(')) { "function calls unsupported: $token" }
        return resolve(token) ?: error("unresolved identifier $token")
    }
}
