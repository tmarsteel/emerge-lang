package compiler.reportings

import compiler.binding.type.BoundTypeArgument
import compiler.lexer.SourceLocation

class TypeArgumentVarianceSuperfluousReporting(
    val argument: BoundTypeArgument,
) : Reporting(
    Level.WARNING,
    "Superfluous variance on type argument. The parameter is already declared as ${argument.variance.name.lowercase()}",
    argument.sourceLocation ?: SourceLocation.UNKNOWN,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TypeArgumentVarianceSuperfluousReporting

        return argument == other.argument && argument.sourceLocation == other.argument.sourceLocation
    }

    override fun hashCode(): Int {
        var result = argument.hashCode()
        result = result * 31 + argument.sourceLocation.hashCode()
        return result
    }
}