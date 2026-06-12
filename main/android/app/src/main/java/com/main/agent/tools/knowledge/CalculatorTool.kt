package com.main.agent.tools.knowledge

import android.content.Context
import com.main.agent.tools.base.Tool
import com.main.agent.tools.base.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class CalculatorTool : Tool {
    override val name        = "calculator"
    override val description = "Evaluate a mathematical expression and return the result."
    override val schema = """
        {"type":"function","function":{"name":"calculator","description":"$description",
        "parameters":{"type":"object","properties":
        {"expression":{"type":"string","description":"Math expression, e.g. '2 * (3 + 4) / sqrt(9)'"}}
        ,"required":["expression"]}}}""".trimIndent()

    override suspend fun execute(context: Context, args: JsonObject): ToolResult {
        val expr = args["expression"]?.jsonPrimitive?.content?.trim()
            ?: return ToolResult.Error("Missing 'expression' argument")
        return try {
            val result = evaluate(expr)
            ToolResult.Success(
                if (result == result.toLong().toDouble()) result.toLong().toString()
                else "%.6g".format(result)
            )
        } catch (e: Exception) {
            ToolResult.Error("Could not evaluate expression '$expr': ${e.message}",
                ToolResult.ErrorCode.PARSE_ERROR)
        }
    }

    private fun evaluate(expr: String): Double {
        val tokens = tokenize(expr)
        val rpn = shuntingYard(tokens)
        return evalRpn(rpn)
    }

    private data class Token(val type: TokenType, val value: String = "") {
        enum class TokenType { NUM, OP, LPAREN, RPAREN, FUNC }
    }

    private fun tokenize(s: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        val chars = s.replace(" ", "")
        while (i < chars.length) {
            when {
                chars[i].isDigit() || chars[i] == '.' -> {
                    val start = i
                    while (i < chars.length && (chars[i].isDigit() || chars[i] == '.')) i++
                    tokens.add(Token(Token.TokenType.NUM, chars.substring(start, i)))
                }
                chars[i] == '(' -> { tokens.add(Token(Token.TokenType.LPAREN)); i++ }
                chars[i] == ')' -> { tokens.add(Token(Token.TokenType.RPAREN)); i++ }
                chars[i] == '+' || chars[i] == '-' || chars[i] == '*' || chars[i] == '/' || chars[i] == '^' -> {
                    tokens.add(Token(Token.TokenType.OP, chars[i].toString())); i++
                }
                chars[i].isLetter() -> {
                    val start = i
                    while (i < chars.length && chars[i].isLetter()) i++
                    tokens.add(Token(Token.TokenType.FUNC, chars.substring(start, i)))
                }
                else -> throw IllegalArgumentException("Unexpected character: ${chars[i]}")
            }
        }
        return tokens
    }

    private fun shuntingYard(tokens: List<Token>): List<Token> {
        val output = mutableListOf<Token>()
        val ops = ArrayDeque<Token>()
        val prec = mapOf("+" to 2, "-" to 2, "*" to 3, "/" to 3, "^" to 4)

        for (t in tokens) {
            when (t.type) {
                Token.TokenType.NUM -> output.add(t)
                Token.TokenType.FUNC -> ops.addFirst(t)
                Token.TokenType.LPAREN -> ops.addFirst(t)
                Token.TokenType.RPAREN -> {
                    while (ops.isNotEmpty() && ops.first().type != Token.TokenType.LPAREN) {
                        output.add(ops.removeFirst())
                    }
                    ops.removeFirstOrNull()
                    if (ops.isNotEmpty() && ops.first().type == Token.TokenType.FUNC) {
                        output.add(ops.removeFirst())
                    }
                }
                Token.TokenType.OP -> {
                    while (ops.isNotEmpty() && ops.first().type == Token.TokenType.OP &&
                        (prec[ops.first().value] ?: 0) >= (prec[t.value] ?: 0) &&
                        t.value != "^") {
                        output.add(ops.removeFirst())
                    }
                    ops.addFirst(t)
                }
            }
        }
        while (ops.isNotEmpty()) output.add(ops.removeFirst())
        return output
    }

    private fun evalRpn(rpn: List<Token>): Double {
        val stack = ArrayDeque<Double>()
        for (t in rpn) {
            when (t.type) {
                Token.TokenType.NUM -> stack.addFirst(t.value.toDouble())
                Token.TokenType.OP -> {
                    val b = stack.removeFirst()
                    val a = stack.removeFirst()
                    stack.addFirst(when (t.value) {
                        "+" -> a + b; "-" -> a - b; "*" -> a * b
                        "/" -> a / b; "^" -> Math.pow(a, b)
                        else -> throw IllegalArgumentException("Unknown op: ${t.value}")
                    })
                }
                Token.TokenType.FUNC -> {
                    val a = stack.removeFirst()
                    stack.addFirst(when (t.value.lowercase()) {
                        "sqrt" -> Math.sqrt(a); "abs" -> Math.abs(a)
                        "sin"  -> Math.sin(a);  "cos"  -> Math.cos(a)
                        "tan"  -> Math.tan(a);  "log"  -> Math.log10(a)
                        "ln"   -> Math.log(a);  "ceil" -> Math.ceil(a)
                        "floor"-> Math.floor(a)
                        else -> throw IllegalArgumentException("Unknown function: ${t.value}")
                    })
                }
                Token.TokenType.LPAREN, Token.TokenType.RPAREN -> {}
            }
        }
        return stack.removeFirst()
    }
}
