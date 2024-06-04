package compiler.reportings

import compiler.ast.VariableDeclaration
import compiler.lexer.Span

class LifetimeEndingCaptureInLoopReporting(
    val variable: VariableDeclaration,
    val captureAt: Span,
) : Reporting(
    Level.ERROR,
    "This usage captures variable ${variable.name.value}, ending its lifetime. This code can execute more than once, but the lifetime can only be ended once.",
    captureAt,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LifetimeEndingCaptureInLoopReporting) return false

        if (captureAt != other.captureAt) return false

        return true
    }

    override fun hashCode(): Int {
        var result = captureAt.hashCode()
        result = 31 * result + javaClass.hashCode()
        return result
    }
}