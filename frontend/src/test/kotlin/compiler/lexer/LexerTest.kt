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

package compiler.compiler.lexer

import compiler.compiler.negative.lexCode
import compiler.lexer.EndOfInputToken
import compiler.lexer.IdentifierToken
import compiler.lexer.Keyword
import compiler.lexer.KeywordToken
import compiler.lexer.NumericLiteralToken
import compiler.lexer.Operator
import compiler.lexer.OperatorToken
import compiler.lexer.StringLiteralContentToken
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.comparables.beGreaterThan
import io.kotest.matchers.comparables.beGreaterThanOrEqualTo
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNot
import io.kotest.matchers.types.beInstanceOf
import io.kotest.matchers.types.shouldBeInstanceOf

class LexerTest : FreeSpec() {init {
    "keywords" - {
        "default" {
            val result = lexCode("   package   ", false)

            result.size shouldBe 2
            result[0] should beInstanceOf(KeywordToken::class)
            (result[0] as KeywordToken).keyword shouldBe Keyword.PACKAGE
            (result[0] as KeywordToken).sourceText shouldBe "package"
            (result[0] as KeywordToken).span.fromLineNumber shouldBe 1u
            (result[0] as KeywordToken).span.fromColumnNumber shouldBe 4u
            result[1] should beInstanceOf<EndOfInputToken>()
        }

        "should be case insensitive" {
            val result = lexCode(" PaCkAGe ", false)

            result.size should beGreaterThanOrEqualTo(1)
            result[0] should beInstanceOf(KeywordToken::class)
            (result[0] as KeywordToken).keyword shouldBe Keyword.PACKAGE
        }
    }

    "newlines are semantic" {
        val result = lexCode("  \n  \n  \n", false)

        result.size shouldBe 4
        result[0] should beInstanceOf(OperatorToken::class)
        (result[0] as OperatorToken).operator shouldBe Operator.NEWLINE

        result[1] shouldBe result[0]
        result[2] shouldBe result[0]
    }

    "integers" - {
        "single digit" {
            val result = lexCode("7", false)

            result.size shouldBe 2
            result[0] should beInstanceOf(NumericLiteralToken::class)
            (result[0] as NumericLiteralToken).stringContent shouldBe "7"
        }

        "multiple digit" {
            val result = lexCode("21498743", false)

            result.size shouldBe 2
            result[0] should beInstanceOf(NumericLiteralToken::class)
            (result[0] as NumericLiteralToken).stringContent shouldBe "21498743"
        }
    }

    "decimals" - {
        "simple decimal" {
            val result = lexCode("312.1232", false)

            result.size shouldBe 2
            result[0] should beInstanceOf(NumericLiteralToken::class)
            (result[0] as NumericLiteralToken).stringContent shouldBe "312.1232"
        }

        "without leading digit it's not a decimal" {
            val result = lexCode(".25", false)

            result.size should beGreaterThan(1)
            result[0] shouldNot beInstanceOf(NumericLiteralToken::class)
        }

        "invocation on integer literal" {
            val result = lexCode("312.toLong()", false)

            result.size should beGreaterThan(3)
            result[0] should beInstanceOf(NumericLiteralToken::class)
            (result[0] as NumericLiteralToken).stringContent shouldBe "312"

            result[1] should beInstanceOf(OperatorToken::class)
            (result[1] as OperatorToken).operator shouldBe Operator.DOT

            result[2] should beInstanceOf(IdentifierToken::class)
            (result[2] as IdentifierToken).value shouldBe "toLong"
        }
    }

    "identifiers" - {
        "identifier stops at space" {
            val result = lexCode("foo bar", false)

            result.size shouldBe 3

            result[0] should beInstanceOf(IdentifierToken::class)
            (result[0] as IdentifierToken).value shouldBe "foo"

            result[1] should beInstanceOf(IdentifierToken::class)
            (result[1] as IdentifierToken).value shouldBe "bar"
        }

        "identifier stops at operator" {
            val result = lexCode("baz*", false)

            result.size shouldBe 3

            result[0] should beInstanceOf(IdentifierToken::class)
            (result[0] as IdentifierToken).value shouldBe "baz"

            result[1] should beInstanceOf(OperatorToken::class)
            (result[1] as OperatorToken).operator shouldBe Operator.TIMES
        }

        "identifier stops at newline" {
            val result = lexCode("cat\n", false)

            result.size shouldBe 3

            result[0] should beInstanceOf(IdentifierToken::class)
            (result[0] as IdentifierToken).value shouldBe "cat"

            result[1] should beInstanceOf(OperatorToken::class)
            (result[1] as OperatorToken).operator shouldBe Operator.NEWLINE
        }

        "identifiers can include keywords" - {
            "beginning" {
                val result = lexCode("asddd", addPackageDeclaration = false)

                result.size shouldBe 2
                result[0].shouldBeInstanceOf<IdentifierToken>().value shouldBe "asddd"
            }

            "middle" {
                val result = lexCode("basd", addPackageDeclaration = false)

                result.size shouldBe 2
                result[0].shouldBeInstanceOf<IdentifierToken>().value shouldBe "basd"
            }

            "ending" {
                val result = lexCode("das", addPackageDeclaration = false)

                result.size shouldBe 2
                result[0].shouldBeInstanceOf<IdentifierToken>().value shouldBe "das"
            }
        }
    }

    "string literals" {
        val code = """
            fn "some {test} data" "{some} test data" "some test {data}" "\n \t"
        """.trimIndent()
        val tokens = lexCode(code, false)

        tokens.size shouldBe 14
        tokens[0].shouldBeInstanceOf<KeywordToken>().keyword shouldBe Keyword.FUNCTION

        tokens[1].shouldBeInstanceOf<OperatorToken>().let {
            it.operator shouldBe Operator.STRING_DELIMITER
            it.span.fromColumnNumber shouldBe 4u
            it.span.toColumnNumber shouldBe 4u
        }
        tokens[2].shouldBeInstanceOf<StringLiteralContentToken>().let {
            it.content shouldBe "some {test} data"
            it.span.fromColumnNumber shouldBe 5u
            it.span.toColumnNumber shouldBe 20u
        }
        tokens[3].shouldBeInstanceOf<OperatorToken>().let {
            it.operator shouldBe Operator.STRING_DELIMITER
            it.span.fromColumnNumber shouldBe 21u
            it.span.toColumnNumber shouldBe 21u
        }

        tokens[4].shouldBeInstanceOf<OperatorToken>().operator shouldBe Operator.STRING_DELIMITER
        tokens[5].shouldBeInstanceOf<StringLiteralContentToken>().content shouldBe "{some} test data"
        tokens[6].shouldBeInstanceOf<OperatorToken>().operator shouldBe Operator.STRING_DELIMITER

        tokens[7].shouldBeInstanceOf<OperatorToken>().operator shouldBe Operator.STRING_DELIMITER
        tokens[8].shouldBeInstanceOf<StringLiteralContentToken>().content shouldBe "some test {data}"
        tokens[9].shouldBeInstanceOf<OperatorToken>().operator shouldBe Operator.STRING_DELIMITER

        tokens[10].shouldBeInstanceOf<OperatorToken>().operator shouldBe Operator.STRING_DELIMITER
        tokens[11].shouldBeInstanceOf<StringLiteralContentToken>().content shouldBe "\n \t"
        tokens[12].shouldBeInstanceOf<OperatorToken>().operator shouldBe Operator.STRING_DELIMITER

        tokens[13] should beInstanceOf<EndOfInputToken>()
    }

    "combo test with code" {
        val result = lexCode("""package foo
            fn foobar(var x: S32 = 24) = return (142.12)?.toLong() == x
        """, false)

        result.size shouldBe 26

        result[0] should beInstanceOf(KeywordToken::class)
        (result[0] as KeywordToken).keyword shouldBe Keyword.PACKAGE

        result[1] should beInstanceOf(IdentifierToken::class)
        (result[1] as IdentifierToken).value shouldBe "foo"

        result[2] should beInstanceOf(OperatorToken::class)
        (result[2] as OperatorToken).operator shouldBe Operator.NEWLINE

        result[3] should beInstanceOf(KeywordToken::class)
        (result[3] as KeywordToken).keyword shouldBe Keyword.FUNCTION

        result[4] should beInstanceOf(IdentifierToken::class)
        (result[4] as IdentifierToken).value shouldBe "foobar"

        result[5] should beInstanceOf(OperatorToken::class)
        (result[5] as OperatorToken).operator shouldBe Operator.PARANT_OPEN

        result[6] should beInstanceOf(KeywordToken::class)
        (result[6] as KeywordToken).keyword shouldBe Keyword.VAR

        result[7] should beInstanceOf(IdentifierToken::class)
        (result[7] as IdentifierToken).value shouldBe "x"

        result[8] should beInstanceOf(OperatorToken::class)
        (result[8] as OperatorToken).operator shouldBe Operator.COLON

        result[9] should beInstanceOf(IdentifierToken::class)
        (result[9] as IdentifierToken).value shouldBe "S32"

        result[10] should beInstanceOf(OperatorToken::class)
        (result[10] as OperatorToken).operator shouldBe Operator.ASSIGNMENT

        result[11] should beInstanceOf(NumericLiteralToken::class)
        (result[11] as NumericLiteralToken).stringContent shouldBe "24"

        result[12] should beInstanceOf(OperatorToken::class)
        (result[12] as OperatorToken).operator shouldBe Operator.PARANT_CLOSE

        result[13] should beInstanceOf(OperatorToken::class)
        (result[13] as OperatorToken).operator shouldBe Operator.ASSIGNMENT

        result[14] should beInstanceOf(KeywordToken::class)
        (result[14] as KeywordToken).keyword shouldBe Keyword.RETURN

        result[15] should beInstanceOf(OperatorToken::class)
        (result[15] as OperatorToken).operator shouldBe Operator.PARANT_OPEN

        result[16] should beInstanceOf(NumericLiteralToken::class)
        (result[16] as NumericLiteralToken).stringContent shouldBe "142.12"

        result[17] should beInstanceOf(OperatorToken::class)
        (result[17] as OperatorToken).operator shouldBe Operator.PARANT_CLOSE

        result[18] should beInstanceOf(OperatorToken::class)
        (result[18] as OperatorToken).operator shouldBe Operator.SAFEDOT

        result[19] should beInstanceOf(IdentifierToken::class)
        (result[19] as IdentifierToken).value shouldBe "toLong"

        result[20] should beInstanceOf(OperatorToken::class)
        (result[20] as OperatorToken).operator shouldBe Operator.PARANT_OPEN

        result[21] should beInstanceOf(OperatorToken::class)
        (result[21] as OperatorToken).operator shouldBe Operator.PARANT_CLOSE

        result[22] should beInstanceOf(OperatorToken::class)
        (result[22] as OperatorToken).operator shouldBe Operator.EQUALS

        result[23] should beInstanceOf(IdentifierToken::class)
        (result[23] as IdentifierToken).value shouldBe "x"

        result[24] should beInstanceOf(OperatorToken::class)
        (result[24] as OperatorToken).operator shouldBe Operator.NEWLINE

        result[25] should beInstanceOf<EndOfInputToken>()
    }
}}