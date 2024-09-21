/*
 * Copyright 2018 Tobias Marstaller
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */

package compiler.lexer

import compiler.util.sortedTopologically

enum class Keyword(val text: String)
{
    PACKAGE("package"),
    IMPORT("import"),

    FUNCTION("fn"),
    CONSTRUCTOR("constructor"),
    MIXIN("mixin"),
    DESTRUCTOR("destructor"),
    VAR("var"),
    SET("set"),
    BORROW("borrow"),
    CAPTURE("capture"),

    MUTABLE("mut"),
    READONLY("read"),
    IMMUTABLE("const"),
    EXCLUSIVE("exclusive"),

    NOTHROW("nothrow"),
    PURE("pure"),
    OPERATOR("operator"),
    INTRINSIC("intrinsic"),
    EXTERNAL("external"),
    OVERRIDE("override"),

    IF("if"),
    ELSE("else"),
    WHILE("while"),
    DO("DO"),
    BREAK("break"),
    CONTINUE("continue"),

    RETURN("return"),
    THROW("throw"),

    CLASS_DEFINITION("class"),
    INTERFACE_DEFINITION("interface"),

    VARIANCE_IN("in"),
    VARIANCE_OUT("out"),

    PRIVATE("private"),
    MODULE("module"),
    INTERNAL("package"),
    EXPORT("export"),

    NOT("not"),
    AND("and"),
    OR("or"),
    XOR("xor"),

    AS("as"),

    INSTANCEOF("is"),

    REFLECT("reflect"),

    TRY("try"),
    CATCH("catch"),
    ;
}

enum class Operator(val text: String, private val _humanReadableName: String? = null)
{
    PARANT_OPEN           ("(", "opening parenthesis"),
    PARANT_CLOSE          (")", "closing parenthesis"),
    CBRACE_OPEN           ("{", "opening curly brace"),
    CBRACE_CLOSE          ("}", "closing curly brace"),
    SBRACE_OPEN           ("[", "opening square brace"),
    SBRACE_CLOSE          ("]", "closing square brace"),
    DOT                   (".", "dot"),
    SAFEDOT               ("?."),
    TIMES                 ("*"),
    COMMA                 (",", "comma"),
    SEMICOLON             (";"),
    COLON                 (":", "colon"),
    NEWLINE               ("\n", "newline"),
    RETURNS               ("->"),
    PLUS                  ("+"),
    MINUS                 ("-"),
    DIVIDE                ("/"),
    EQUALS                ("=="),
    NOT_EQUALS            ("!="),
    ASSIGNMENT            ("="),
    GREATER_THAN_OR_EQUALS(">="),
    LESS_THAN_OR_EQUALS   ("<="),
    GREATER_THAN          (">"),
    LESS_THAN             ("<"),
    NULL_COALESCE         ("?:", "null-coalescing operator"),
    QUESTION_MARK         ("?"),
    NOTNULL               ("!!"), // find a better name for this...
    EXCLAMATION_MARK      ("!", "exclamation mark"),
    STRING_DELIMITER      (Char(compiler.lexer.STRING_DELIMITER.value).toString()),
    IDENTIFIER_DELIMITER  (Char(compiler.lexer.IDENTIFIER_DELIMITER.value).toString()),
    COMMENT               ("//", "comment marker")
    ;

    override fun toString() = this._humanReadableName ?: "operator $text"

    companion object {
        val valuesSortedForLexing: List<Operator> = values()
            .toList()
            .sortedTopologically { depender, dependency ->
                dependency.text.startsWith(depender.text)
            }
    }
}

val DECIMAL_SEPARATOR = CodePoint('.'.code)
val STRING_ESCAPE_CHAR = CodePoint('\\'.code)
val STRING_DELIMITER = CodePoint('"'.code)
val IDENTIFIER_DELIMITER = CodePoint('`'.code)

abstract class Token {
    abstract val span: Span

    override fun toString(): String {
        if (span === Span.UNKNOWN) {
            return toStringWithoutLocation()
        }

        return toStringWithoutLocation() + " in " + span.fileLineColumnText
    }

    abstract fun toStringWithoutLocation(): String
}

class KeywordToken(
    val keyword: Keyword,
    /** The actual CharSequence as it appears in the source code */
    val sourceText: String = keyword.text,
    override val span: Span = Span.UNKNOWN
): Token() {
    override fun toStringWithoutLocation() = "keyword " + keyword.text.lowercase()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other?.javaClass != javaClass) return false

        other as KeywordToken

        return keyword == other.keyword
    }

    override fun hashCode(): Int {
        return keyword.hashCode()
    }
}

class OperatorToken(
        val operator: Operator,
        override val span: Span = Span.UNKNOWN
) : Token() {
    override fun toStringWithoutLocation() = operator.toString()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other?.javaClass != javaClass) return false

        other as OperatorToken

        return operator == other.operator
    }

    override fun hashCode(): Int {
        return operator.hashCode()
    }
}

class IdentifierToken(
    val value: String,
    override val span: Span = Span.UNKNOWN
) : Token() {
    override fun toStringWithoutLocation() = "identifier $value"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other?.javaClass != javaClass) return false

        other as IdentifierToken

        return value == other.value
    }

    override fun hashCode(): Int {
        return value.hashCode()
    }
}

class NumericLiteralToken(
    override val span: Span,
    val stringContent: String
): Token() {
    override fun toStringWithoutLocation() = "number $stringContent"
}

/**
 * String contents without the delimiters. Having the delimiters as separate tokens hopefully
 * helps the parser ambiguity detection
 */
class StringLiteralContentToken(
    override val span: Span,
    val content: String,
) : Token() {
    override fun toStringWithoutLocation() = "string literal"
}

class DelimitedIdentifierContentToken(
    override val span: Span,
    val content: String,
) : Token() {
    override fun toStringWithoutLocation() = "delimited identifier"
}

class EndOfInputToken(lastLocationInFile: Span) : Token() {
    override val span = lastLocationInFile
    override fun toStringWithoutLocation() = "end of input"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other?.javaClass != javaClass) return false

        return true
    }

    override fun hashCode(): Int {
        return javaClass.hashCode()
    }
}