@file:JvmName("GrammarDsl")
package compiler.parser.grammar.dsl

import compiler.parser.grammar.rule.EitherOfRule
import compiler.parser.grammar.rule.LazyRule
import compiler.parser.grammar.rule.Rule
import compiler.parser.grammar.rule.SequenceRule

// TODO: replace with delegate, derive name from declaration?
// val ReturnStatement by sequence { ... }, infers name = "return statement"
fun sequence(explicitName: String? = null, grammar: Grammar): Rule<*> {
    return LazyRule {
        RuleCollectingGrammarReceiver.collect(
            grammar,
            { rules -> SequenceRule(rules.toTypedArray(), explicitName) },
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