package compiler.reportings

import compiler.InternalCompilerError
import compiler.ast.AstMixinStatement
import compiler.binding.context.ExecutionScopedCTContext

class IllegalMixinRepetitionReporting(
    val mixinStatement: AstMixinStatement,
    val repetition: ExecutionScopedCTContext.Repetition,
) : Reporting(
    Level.ERROR,
    run {
        val repetitionText = when (repetition) {
            ExecutionScopedCTContext.Repetition.ZERO_OR_MORE -> "might never be executed"
            ExecutionScopedCTContext.Repetition.ONCE_OR_MORE -> "may be executed multiple times"
            ExecutionScopedCTContext.Repetition.EXACTLY_ONCE -> throw InternalCompilerError("this is nonsensical; reporting on correct code")
        }

        "This mixin $repetitionText; mixins must always be executed exactly once per object construction"
    },
    mixinStatement.span,
)