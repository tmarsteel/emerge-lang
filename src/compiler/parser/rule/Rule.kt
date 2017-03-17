package compiler.parser.rule

import compiler.lexer.*
import compiler.matching.Matcher
import compiler.matching.ResultCertainty
import compiler.parser.MissingTokenReporting
import compiler.parser.Reporting
import compiler.parser.TokenMismatchReporting
import compiler.parser.TokenSequence

interface Rule<T> : Matcher<TokenSequence,T,Reporting> {
    companion object {
        fun singleton(equalTo: Token, mismatchCertainty: ResultCertainty = ResultCertainty.OPTIMISTIC): Rule<Token> = object : Rule<Token> {
            override val descriptionOfAMatchingThing: String
                get() = equalTo.toString()

            override fun tryMatch(input: TokenSequence): MatchingResult<Token> {
                if (!input.hasNext()) {
                    return RuleMatchingResult(
                        mismatchCertainty,
                        null,
                        setOf(
                            MissingTokenReporting(equalTo, input.currentSourceLocation)
                        )
                    )
                }

                input.mark()

                val token = input.next()!!
                if (token == equalTo) {
                    input.commit()
                    return RuleMatchingResult(
                        ResultCertainty.DEFINITIVE,
                        token,
                        emptySet()
                    )
                }
                else {
                    input.rollback()
                    return RuleMatchingResult(
                        mismatchCertainty,
                        null,
                        setOf(
                            TokenMismatchReporting(equalTo, token)
                        )
                    )
                }
            }
        }

        fun singletonOfType(type: TokenType): Rule<Token> = object : Rule<Token> {
            override val descriptionOfAMatchingThing: String
                get() = type.name

            override fun tryMatch(input: TokenSequence): MatchingResult<Token> {
                if (!input.hasNext()) {
                    return RuleMatchingResult(
                        ResultCertainty.DEFINITIVE,
                        null,
                        setOf(
                            Reporting.error("Expected token of type $type, found nothing", input.currentSourceLocation)
                        )
                    )
                }

                input.mark()

                val token = input.next()!!
                if (token.type == type) {
                    input.commit()
                    return RuleMatchingResult(
                        ResultCertainty.OPTIMISTIC,
                        token,
                        emptySet()
                    )
                }
                else {
                    input.rollback()
                    return RuleMatchingResult(
                        ResultCertainty.DEFINITIVE,
                        null,
                        setOf(
                                Reporting.error("Expected token of type $type, found $token", token)
                        )
                    )
                }
            }
        }
    }

    override fun tryMatch(input: TokenSequence): MatchingResult<T>
}