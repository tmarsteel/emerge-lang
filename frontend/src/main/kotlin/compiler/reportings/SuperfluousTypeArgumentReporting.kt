package compiler.reportings

import compiler.binding.type.BoundTypeArgument
import compiler.lexer.Span

data class SuperfluousTypeArgumentsReporting(
    val nExpected: Int,
    val firstSuperfluousArgument: BoundTypeArgument,
) : Reporting(
    Level.ERROR,
    "Too many type arguments, expected only $nExpected",
    firstSuperfluousArgument.astNode.span ?: Span.UNKNOWN,
) {
    override fun toString() = super.toString()
}