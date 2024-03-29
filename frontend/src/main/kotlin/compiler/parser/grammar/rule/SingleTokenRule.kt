package compiler.parser.grammar.rule

import compiler.lexer.Token
import compiler.parser.TokenSequence
import compiler.reportings.Reporting

sealed class SingleTokenRule(
    private val expectedToken: ExpectedToken,
) : Rule<Token> {
    override val explicitName = null

    private val marksEndOfAmbiguityInContexts = HashSet<MatchingContext>()

    final override val minimalMatchingSequence: Sequence<Sequence<ExpectedToken>> by lazy {
        sequenceOf(sequenceOf(MarkingExpectedToken(expectedToken)))
    }

    final override fun match(context: MatchingContext, input: TokenSequence): MatchingResult<Token> {
        if (!input.hasNext()) {
            return MatchingResult(
                isAmbiguous = true,
                marksEndOfAmbiguity = false,
                item = null,
                reportings = setOf(Reporting.unexpectedEOI(descriptionOfAMatchingThing, input.currentSourceLocation))
            )
        }

        input.mark()

        val token = input.next()!!
        val processed = matchAndPostprocess(token)
        if (processed != null) {
            input.commit()
            return MatchingResult(
                isAmbiguous = false,
                marksEndOfAmbiguity = context in marksEndOfAmbiguityInContexts,
                item = processed,
                reportings = emptySet(),
            )
        }

        input.rollback()
        return MatchingResult(
            isAmbiguous = true,
            marksEndOfAmbiguity = false,
            item = null,
            setOf(Reporting.mismatch(descriptionOfAMatchingThing, token)),
        )
    }

    override fun markAmbiguityResolved(inContext: MatchingContext) {
        // nothing to do, no nested rules to inform and no own bookkeeping to adjust
    }

    /**
     * @return if matched, the token, possibly modified (e.g. [IdentifierRule]). `null` on mismatch
     */
    abstract fun matchAndPostprocess(token: Token): Token?

    override fun toString() = "single token: $descriptionOfAMatchingThing"

    private inner class MarkingExpectedToken(
        private val delegate: ExpectedToken,
    ) : ExpectedToken {
        override fun markAsRemovingAmbiguity(inContext: MatchingContext) {
            marksEndOfAmbiguityInContexts.add(inContext)
            delegate.markAsRemovingAmbiguity(inContext)
        }
        override fun unwrap() = delegate
        override fun toString() = delegate.toString()
    }
}