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

import compiler.sortedTopologically

enum class Keyword(val text: String)
{
    PACKAGE("package"),
    IMPORT("import"),

    FUNCTION("fun"),
    VAR("var"),
    SET("set"),
    MUTABLE("mutable"),
    READONLY("readonly"),
    IMMUTABLE("immutable"),
    EXCLUSIVE("exclusive"),
    NOTHROW("nothrow"),
    PURE("pure"),
    OPERATOR("operator"),
    INTRINSIC("intrinsic"),
    EXTERNAL("external"),
    IF("if"),
    ELSE("else"),

    RETURN("return"),

    CLASS_DEFINITION("class"),
    INTERFACE_DEFINITION("interface"),

    VARIANCE_IN("in"),
    VARIANCE_OUT("out"),

    PRIVATE("private"),
    MODULE("module"),
    INTERNAL("package"),
    EXPORT("export"),
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
    IDENTITY_EQ           ("==="),
    IDENTITY_NEQ          ("!=="),
    EQUALS                ("=="),
    NOT_EQUALS            ("!="),
    ASSIGNMENT            ("="),
    GREATER_THAN_OR_EQUALS(">="),
    LESS_THAN_OR_EQUALS   ("<="),
    GREATER_THAN          (">"),
    LESS_THAN             ("<"),
    ELVIS                 ("?:", "elvis operator"),
    QUESTION_MARK         ("?"),
    NOTNULL               ("!!"), // find a better name for this...
    EXCLAMATION_MARK      ("!", "exclamation mark"),
    STRING_DELIMITER      (Char(compiler.lexer.STRING_DELIMITER.value).toString()),
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

abstract class Token {
    abstract val sourceLocation: SourceLocation

    override fun toString(): String {
        if (sourceLocation === SourceLocation.UNKNOWN) {
            return toStringWithoutLocation()
        }

        return toStringWithoutLocation() + " in " + sourceLocation.fileLineColumnText
    }

    abstract fun toStringWithoutLocation(): String
}

class KeywordToken(
        val keyword: Keyword,
        /** The actual CharSequence as it appears in the source code */
        val sourceText: String = keyword.text,
        override val sourceLocation: SourceLocation = SourceLocation.UNKNOWN
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
        override val sourceLocation: SourceLocation = SourceLocation.UNKNOWN
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
    override val sourceLocation: SourceLocation = SourceLocation.UNKNOWN
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
        override val sourceLocation: SourceLocation,
        val stringContent: String
): Token() {
    override fun toStringWithoutLocation() = "number $stringContent"
}

/**
 * String contents without the delimiters. Having the delimiters as separate tokens hopefully
 * helps the parser ambiguity detection
 */
class StringLiteralContentToken(
    override val sourceLocation: SourceLocation,
    val content: String,
) : Token() {
    override fun toStringWithoutLocation() = "string literal"
}

class EndOfInputToken(lastLocationInFile: SourceLocation) : Token() {
    override val sourceLocation = lastLocationInFile
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