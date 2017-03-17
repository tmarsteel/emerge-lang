package compiler.parser.rule

import compiler.matching.ResultCertainty
import compiler.parser.Reporting
import compiler.parser.TokenSequence

/**
 * Matches the end of the given token sequence
 */
class EOIRule private constructor() : Rule<TokenSequence> {
    override val descriptionOfAMatchingThing = "end of input"
    override fun tryMatch(input: TokenSequence): MatchingResult<TokenSequence> {
        if (input.hasNext()) {
            return RuleMatchingResult(
                ResultCertainty.DEFINITIVE,
                null,
                setOf(Reporting.error("Unexpected ${input.peek()!!.toStringWithoutLocation()}, expecting $descriptionOfAMatchingThing", input.peek()!!))
            )
        }
        else {
            return RuleMatchingResult(
                ResultCertainty.DEFINITIVE,
                null,
                emptySet()
            )
        }
    }

    companion object {
        val INSTANCE = EOIRule()
    }
}
