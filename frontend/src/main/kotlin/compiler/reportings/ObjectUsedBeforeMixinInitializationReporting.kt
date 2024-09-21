package compiler.reportings

import compiler.ast.AstMixinStatement
import compiler.lexer.Span

class ObjectUsedBeforeMixinInitializationReporting(
    val uninitializedMixin: AstMixinStatement,
    objectUsedAt: Span,
) : Reporting(
    Level.ERROR,
    "The object is not fully initialized yet. A mixin must still be initialized.",
    objectUsedAt,
) {
    override fun toString(): String {
        return "$levelAndMessage\n" + illustrateHints(listOf(
            SourceHint(span, "Object used before initialization is complete"),
            SourceHint(uninitializedMixin.span, "This mixin is not yet initialized")
        ))
    }
}