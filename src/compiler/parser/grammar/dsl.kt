package compiler.parser.grammar

import compiler.lexer.*
import compiler.matching.ResultCertainty
import compiler.parser.TokenSequence
import compiler.parser.rule.MatchingResult
import compiler.parser.rule.*

fun rule(initFn: DSLFixedSequenceRule.() -> Any?): Rule<List<MatchingResult<*>>> {
    val rule = DSLFixedSequenceRule()
    // any rule can be preceeded by whitespace
    rule.optionalWhitespace()

    rule.initFn()

    return rule
}

fun <T> Rule<T>.describeAs(description: String): Rule<T> {
    val base = this
    return object : Rule<T> {
        override val descriptionOfAMatchingThing = description

        override fun tryMatch(input: TokenSequence): MatchingResult<T> {
            return base.tryMatch(input);
        }
    }
}

fun <ResultBefore,ResultAfter> Rule<ResultBefore>.postprocess(postProcessor: (Rule<ResultBefore>) -> Rule<ResultAfter>): Rule<ResultAfter>
    = postProcessor(this)

/**
 * A mutable subclass of [FixedSequenceRule] with DSL supporting methods
 */
interface DSLCollectionRule<ResultType> : Rule<ResultType>
{
    val subRules: MutableList<Rule<*>>

    /**
     * Matches exactly one [KeywordToken] with the given [lexer.Keyword]
     */
    fun keyword(kw: Keyword): Unit {
        subRules.add(Rule.singleton(KeywordToken(kw)))
    }

    /**
     * Matches exactly one [OperatorToken] with the given [Operator]
     */
    fun operator(op: Operator): Unit {
        subRules.add(Rule.singleton(OperatorToken(op)))
    }

    /**
     * Matches exactly one [IdentifierToken]
     */
    fun identifier(acceptedOperators: Collection<Operator> = emptyList(), acceptedKeywords: Collection<Keyword> = emptyList()): Unit
    {
        if (acceptedOperators.isEmpty() && acceptedKeywords.isEmpty()) {
            subRules.add(Rule.singletonOfType(TokenType.IDENTIFIER))
        } else {
            subRules.add(TolerantIdentifierMatchingRule(acceptedOperators, acceptedKeywords))
        }
    }

    fun optional(initFn: DSLFixedSequenceRule.() -> Any?): Unit
    {
        val subRule = DSLFixedSequenceRule()
        subRule.initFn()

        subRules.add(OptionalRule(subRule))
    }

    /**
     * Skips whitespace (newlines); Always matches successfully with [ResultCertainty.OPTIMISTIC]
     */
    fun optionalWhitespace(): Unit
    {
        subRules.add(WhitespaceEaterRule.instance)
    }

    /**
     * Matches at least `times` occurences of the given rule
     */
    fun atLeast(times: Int, initFn: DSLFixedSequenceRule.() -> Any?): Unit
    {
        val rule = DSLFixedSequenceRule()
        rule.initFn()

        subRules.add(VariableTimesRule(rule, IntRange(times, Int.MAX_VALUE)))
    }

    /**
     * Matches the first of any of the sub-rules
     */
    fun eitherOf(initFn: DSLEitherOfRule.() -> Any?): Unit
    {
        val rule = DSLEitherOfRule()
        rule.initFn()

        subRules.add(rule)
    }

    /**
     * Adds the given rule to the list of subrules
     */
    fun ref(otherRule: Rule<*>): Unit {
        subRules.add(otherRule)
    }
}

class DSLEitherOfRule(
        override val subRules: MutableList<Rule<*>> = mutableListOf()
) : DSLCollectionRule<Any?>, EitherOfRule(subRules)

class DSLFixedSequenceRule(
    override val subRules: MutableList<Rule<*>> = mutableListOf(),
    private val certaintySteps: MutableList<Pair<Int, ResultCertainty>> = mutableListOf(0 to ResultCertainty.OPTIMISTIC)
) : FixedSequenceRule(subRules, certaintySteps), DSLCollectionRule<List<MatchingResult<*>>>
{
    /**
     * Reading from this property: returns the level of certainty the rule has at the current point of configuration
     * Writing to this property: if the previous rule matches successfully, sets the certainty level of the result
     * to the given [ResultCertainty]
     */
    var __certainty: ResultCertainty
        get() = certaintySteps.last().second
        set(c)
        {
            val lastStep = certaintySteps.last()
            val currentIndex = subRules.lastIndex
            if (c.level <= lastStep.second.level)
            {
                throw MisconfigurationException("Certainty steps have to increase; last was " + lastStep.second + ", new one is " + c)
            }

            if (lastStep.first == currentIndex)
            {
                certaintySteps.removeAt(certaintySteps.lastIndex)
            }

            certaintySteps.add(currentIndex to c)
        }

    /**
     * Sets certainty at this matching stage to [ResultCertainty.DEFINITIVE]
     */
    fun __definitive(): Unit
    {
        __certainty = ResultCertainty.DEFINITIVE
    }
}