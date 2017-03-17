package compiler.parser.rule

import compiler.matching.ResultCertainty
import compiler.parser.Reporting
import compiler.parser.TokenSequence
import java.util.*

/**
 * Matches a given rule a variable number of times (at least, at most)
 */
class VariableTimesRule<T>(
        val rule: Rule<T>,
        val times: IntRange
): Rule<List<MatchingResult<T>>>
{
    override val descriptionOfAMatchingThing: String

    init {
        if (times.start == 0) {
            if (times.endInclusive >= Int.MAX_VALUE) {
                descriptionOfAMatchingThing = "any number of ${rule.descriptionOfAMatchingThing}"
            }
            else
            {
                descriptionOfAMatchingThing = "up to ${times.endInclusive.wordifyExpecationEN} ${rule.descriptionOfAMatchingThing}"
            }
        }
        else
        {
            if (times.endInclusive >= Int.MAX_VALUE) {
                descriptionOfAMatchingThing = "at least ${times.start.wordifyExpecationEN} ${rule.descriptionOfAMatchingThing}"
            }
            else
            {
                descriptionOfAMatchingThing = "between ${times.start.wordifyExpecationEN} and ${times.endInclusive.wordifyExpecationEN} ${rule.descriptionOfAMatchingThing}"
            }
        }
    }

    override fun tryMatch(input: TokenSequence): MatchingResult<List<MatchingResult<T>>> {
        // TODO: refactor: collect all reportings; double-check fail conditions
        var matchResults: MutableList<MatchingResult<T>> = ArrayList(times.start)

        input.mark()
        var lastResult: MatchingResult<T>? = null

        while (matchResults.size < times.endInclusive) {
            input.mark()

            lastResult = rule.tryMatch(input)
            if (lastResult.item == null) {
                if (lastResult.certainty == ResultCertainty.DEFINITIVE) {
                    @Suppress("UNCHECKED_CAST")
                    return RuleMatchingResult(
                        ResultCertainty.DEFINITIVE,
                        lastResult as? List<MatchingResult<T>>,
                        lastResult.reportings
                    )
                }

                input.rollback()
                break
            }

            input.commit()
            matchResults.add(lastResult)
        }

        if (matchResults.size >= times.start) {
            input.commit()
            return RuleMatchingResult(
                matchResults.map { it.certainty }.min() ?: ResultCertainty.OPTIMISTIC,
                matchResults,
                setOf()
            )
        }
        else
        {
            input.rollback()

            var errors = if (lastResult?.reportings != null && lastResult.reportings.isNotEmpty()) {
                lastResult.reportings
            }
            else {
                setOf(Reporting.error(
                    "Exopected $descriptionOfAMatchingThing but found ${matchResults.size.wordifyActualEN}",
                    input.currentSourceLocation
                ))
            }

            return RuleMatchingResult(
                ResultCertainty.OPTIMISTIC,
                null,
                errors
            )
        }
    }
}

private val Int.wordifyExpecationEN: String
    get() {
        if (this in 0..12) {
            return listOf(
                "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve"
            )[this]
        }
        else
        {
            return this.toString()
        }
    }

private val Int.wordifyActualEN: String
    get() {
        if (this == 0) {
            return "none"
        }
        else return this.wordifyExpecationEN
    }