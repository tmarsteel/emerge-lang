@file:JvmName("GrammarDsl")
package compiler.parser.grammar.dsl

import compiler.parser.TokenSequence
import compiler.parser.grammar.rule.EitherOfRule
import compiler.parser.grammar.rule.ExpectedToken
import compiler.parser.grammar.rule.LazyRule
import compiler.parser.grammar.rule.MatchingContext
import compiler.parser.grammar.rule.MatchingResult
import compiler.parser.grammar.rule.Rule
import compiler.parser.grammar.rule.SequenceRule

// TODO: replace with delegate, derive name from declaration?
// val ReturnStatement by sequence { ... }, infers name = "return statement"
fun sequence(explicitName: String? = null, grammar: Grammar): Rule<*> {
    return LazyRule {
        RuleCollectingGrammarReceiver.collect(
            grammar,
            { rules -> SequenceRule(rules, explicitName) },
            explicitName == null,
        )
    }
}

fun eitherOf(explicitName: String? = null, grammar: Grammar): Rule<*> {
    return LazyRule {
        RuleCollectingGrammarReceiver.collect(
            grammar,
            { rules -> EitherOfRule(rules, explicitName) },
            explicitName == null,
        )
    }
}

val <T> Rule<T>.isolateCyclicGrammar: Rule<T> get() = object : Rule<T> {
    override val explicitName: String? get() = this@isolateCyclicGrammar.explicitName
    override val descriptionOfAMatchingThing get() = this@isolateCyclicGrammar.descriptionOfAMatchingThing

    override fun match(context: MatchingContext, input: TokenSequence): MatchingResult<T> {
        return this@isolateCyclicGrammar.match(MatchingContext.None, input)
    }

    override fun markAmbiguityResolved(inContext: MatchingContext) {
        if (inContext == MatchingContext.None) {
            this@isolateCyclicGrammar.markAmbiguityResolved(MatchingContext.None)
        }
    }

    override val minimalMatchingSequence = sequenceOf(emptySequence<ExpectedToken>())
}