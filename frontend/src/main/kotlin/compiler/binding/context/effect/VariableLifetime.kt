package compiler.binding.context.effect

import compiler.InternalCompilerError
import compiler.ast.VariableOwnership
import compiler.ast.type.TypeMutability
import compiler.binding.BoundVariable
import compiler.binding.expression.BoundIdentifierExpression
import compiler.lexer.Span
import compiler.reportings.Reporting

object VariableLifetime : EphemeralStateClass<BoundVariable, VariableLifetime.State, VariableLifetime.Effect> {
    override fun getInitialState(subject: BoundVariable): State {
        if (subject.ownershipAtDeclarationTime == VariableOwnership.CAPTURED && subject.typeAtDeclarationTime?.mutability == TypeMutability.EXCLUSIVE) {
            return State.AliveCapturedExclusive
        }

        return State.Untracked
    }

    override fun fold(state: State, effect: Effect): State {
        when (effect) {
            is Effect.ValueCaptured -> {
                if (state is State.AliveCapturedExclusive && effect.withMutability != TypeMutability.READONLY) {
                    return State.Dead(effect.subject, effect.capturedAt)
                }
                return state
            }
            is Effect.NewValueAssigned -> return getInitialState(effect.subject)
        }
    }

    override fun combineMaybe(state: State, advancedMaybe: State): State {
        return when (state) {
            is State.Untracked -> {
                check(advancedMaybe is State.Untracked) { "variables cannot suddenly become tracked" }
                state
            }
            is State.AliveCapturedExclusive -> when (advancedMaybe) {
                is State.Untracked -> throw InternalCompilerError("this should be impossible, variables cannot suddenly become tracked")
                is State.AliveCapturedExclusive -> state
                is State.Dead -> advancedMaybe.maybe()
            }
            is State.Dead -> state
        }
    }

    override fun intersect(stateOne: State, stateTwo: State): State {
        return when (stateOne) {
            is State.Untracked -> {
                check(stateTwo is State.Untracked) { "variables cannot suddenly become tracked" }
                stateOne
            }
            is State.AliveCapturedExclusive -> when (stateTwo) {
                is State.Untracked -> throw InternalCompilerError("this should be impossible, variables cannot suddenly become tracked")
                is State.AliveCapturedExclusive -> stateOne
                is State.Dead -> stateTwo.maybe()
            }
            is State.Dead -> when (stateTwo) {
                is State.Untracked -> throw InternalCompilerError("this should be impossible, variables cannot suddenly become tracked")
                is State.AliveCapturedExclusive -> stateOne.maybe()
                is State.Dead -> stateOne
            }
        }
    }

    sealed interface State {
        fun maybe(): State = this
        fun validateCapture(read: BoundIdentifierExpression): Collection<Reporting> = emptySet()
        fun validateRepeatedCapture(stateBeforeCapture: State, read: BoundIdentifierExpression): Collection<Reporting> = emptySet()

        /**
         * No lifetime tracking is done, it lives for the entirety of its scope
         */
        data object Untracked : State

        data object AliveCapturedExclusive : State

        data class Dead(
            val variable: BoundVariable,
            val lifetimeEndedAt: Span,
            val maybe: Boolean = false,
        ) : State {
            override fun maybe() = if (maybe) this else Dead(variable, lifetimeEndedAt, true)
            override fun validateCapture(read: BoundIdentifierExpression): Collection<Reporting> {
                return setOf(Reporting.variableUsedAfterLifetime(variable, read, this))
            }

            override fun validateRepeatedCapture(stateBeforeCapture: State, read: BoundIdentifierExpression): Collection<Reporting> {
                if (stateBeforeCapture is Dead) {
                    // the looping isn't causing the problem; don't report anything here. validateCapture should report it
                    return emptySet()
                }

                return setOf(Reporting.lifetimeEndingCaptureInLoop(variable, read))
            }
        }
    }

    sealed interface Effect : SideEffect<BoundVariable> {
        override val stateClass: EphemeralStateClass<*, *, *> get() = VariableLifetime

        data class ValueCaptured(
            override val subject: BoundVariable,
            val withMutability: TypeMutability,
            val capturedAt: Span,
        ) : Effect

        data class NewValueAssigned(
            override val subject: BoundVariable
        ) : Effect
    }
}