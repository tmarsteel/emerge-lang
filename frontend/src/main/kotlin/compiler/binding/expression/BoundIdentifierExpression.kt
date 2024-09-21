/*
 * Copyright 2018 Tobias Marstaller
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */

package compiler.binding.expression

import compiler.ast.VariableOwnership
import compiler.ast.expression.IdentifierExpression
import compiler.ast.type.TypeMutability
import compiler.ast.type.TypeReference
import compiler.binding.BoundVariable
import compiler.binding.SemanticallyAnalyzable
import compiler.binding.SideEffectPrediction
import compiler.binding.context.CTContext
import compiler.binding.context.ExecutionScopedCTContext
import compiler.binding.context.MutableExecutionScopedCTContext
import compiler.binding.context.effect.PartialObjectInitialization
import compiler.binding.context.effect.VariableInitialization
import compiler.binding.context.effect.VariableLifetime
import compiler.binding.type.BoundTypeReference
import compiler.binding.type.RootResolvedTypeReference
import compiler.binding.type.UnresolvedType
import compiler.lexer.Span
import compiler.reportings.NothrowViolationReporting
import compiler.reportings.Reporting
import io.github.tmarsteel.emerge.backend.api.ir.IrExpression
import io.github.tmarsteel.emerge.backend.api.ir.IrVariableAccessExpression
import io.github.tmarsteel.emerge.backend.api.ir.IrVariableDeclaration

class BoundIdentifierExpression(
    override val context: ExecutionScopedCTContext,
    override val declaration: IdentifierExpression
) : BoundExpression<IdentifierExpression> {
    val identifier: String = declaration.identifier.value

    override val type: BoundTypeReference?
        get() = when(val localReferral = referral) {
            is ReferringVariable -> localReferral.variable.getTypeInContext(context)
            is ReferringType -> localReferral.reference
            null -> null
        }

    var referral: Referral? = null
        private set

    override val throwBehavior = SideEffectPrediction.NEVER
    override val returnBehavior = SideEffectPrediction.NEVER

    private val _modifiedContext = MutableExecutionScopedCTContext.deriveFrom(context)
    override val modifiedContext: ExecutionScopedCTContext = _modifiedContext

    override fun semanticAnalysisPhase1(): Collection<Reporting> {
        val reportings = mutableSetOf<Reporting>()

        // attempt variable
        val variable = context.resolveVariable(identifier)

        if (variable != null) {
            referral = ReferringVariable(variable)
        } else {
            val type: BoundTypeReference? = context.resolveType(
                TypeReference(declaration.identifier)
            ).takeUnless { it is UnresolvedType }

            if (type == null) {
                reportings.add(Reporting.undefinedIdentifier(declaration.identifier))
            } else {
                referral = ReferringType(type)
            }
        }

        return reportings + (referral?.semanticAnalysisPhase1() ?: emptySet())
    }

    override fun markEvaluationResultUsed() {
        referral?.markEvaluationResultUsed()
    }

    override fun markEvaluationResultCaptured(withMutability: TypeMutability) {
        referral?.markEvaluationResultCaptured(withMutability)
    }

    fun allowPartiallyUninitializedValue() {
        (referral as? ReferringVariable)?.allowPartiallyUninitializedValue()
    }

    override val isEvaluationResultAnchored get() = referral?.isEvaluationResultAnchored ?: false
    override val isCompileTimeConstant get() = referral?.isCompileTimeConstant ?: false

    override fun semanticAnalysisPhase2(): Collection<Reporting> {
        val reportings = mutableListOf<Reporting>()
        referral?.semanticAnalysisPhase2()?.let(reportings::addAll)
        return reportings
    }

    override fun semanticAnalysisPhase3(): Collection<Reporting> {
        return referral?.semanticAnalysisPhase3() ?: emptySet()
    }

    override fun findReadsBeyond(boundary: CTContext): Collection<BoundExpression<*>> {
        return referral?.findReadsBeyond(boundary) ?: emptySet()
    }

    override fun findWritesBeyond(boundary: CTContext): Collection<BoundExpression<*>> {
        // this does not write by itself; writs are done by other statements
        return emptySet()
    }

    override fun setExpectedEvaluationResultType(type: BoundTypeReference) {
        // nothing to do. identifiers must always be unambiguous so there is no use for this information
    }

    override fun setNothrow(boundary: NothrowViolationReporting.SideEffectBoundary) {
        // nothing to do
    }

    sealed interface Referral : SemanticallyAnalyzable {
        val span: Span

        override fun semanticAnalysisPhase1(): Collection<Reporting> = emptySet()
        override fun semanticAnalysisPhase2(): Collection<Reporting> = emptySet()
        override fun semanticAnalysisPhase3(): Collection<Reporting> = emptySet()

        /** @see BoundExpression.findReadsBeyond */
        fun findReadsBeyond(boundary: CTContext): Collection<BoundExpression<*>>

        /** @see BoundExpression.markEvaluationResultUsed */
        fun markEvaluationResultUsed()

        /** @see BoundExpression.markEvaluationResultCaptured */
        fun markEvaluationResultCaptured(withMutability: TypeMutability)

        /** @see BoundExpression.isCompileTimeConstant */
        val isCompileTimeConstant: Boolean

        /** @see BoundExpression.isEvaluationResultAnchored */
        val isEvaluationResultAnchored: Boolean
    }
    inner class ReferringVariable(val variable: BoundVariable) : Referral {
        override val span = declaration.span
        private var usageContext = VariableUsageContext.WRITE
        private var allowPartiallyUninitialized: Boolean = false
        private var thisUsageCapturesWithMutability: TypeMutability? = null
        /* variables are always anchored, refcount on assignment + deferred refcount of the value at scope exit */
        override val isEvaluationResultAnchored get() = true

        fun allowPartiallyUninitializedValue() {
            allowPartiallyUninitialized = true
        }

        override fun markEvaluationResultUsed() {
            usageContext = VariableUsageContext.READ
        }

        override fun markEvaluationResultCaptured(withMutability: TypeMutability) {
            thisUsageCapturesWithMutability = withMutability
        }

        override fun semanticAnalysisPhase2(): Collection<Reporting> {
            return variable.semanticAnalysisPhase2()
        }

        override fun semanticAnalysisPhase3(): Collection<Reporting> {
            val reportings = mutableListOf<Reporting>()

            val initializationState = variable.getInitializationStateInContext(context)
            if (usageContext.requiresInitialization) {
                if (initializationState != VariableInitialization.State.INITIALIZED) {
                    reportings.add(
                        Reporting.useOfUninitializedVariable(
                            variable,
                            this@BoundIdentifierExpression,
                            initializationState == VariableInitialization.State.MAYBE_INITIALIZED,
                        )
                    )
                }

                if (!allowPartiallyUninitialized) {
                    variable.getTypeInContext(context)
                        ?.let { it as? RootResolvedTypeReference }
                        ?.baseType
                        ?.let { baseType ->
                            val partialState = context.getEphemeralState(PartialObjectInitialization, variable)
                            partialState.getUninitializedMembers(baseType)
                                .takeUnless { it.isEmpty() }
                                ?.let {
                                    reportings.add(Reporting.notAllMemberVariablesInitialized(it, declaration.span))
                                }

                            partialState.getUninitializedMixins(baseType)
                                .takeUnless { it.isEmpty() }
                                ?.let {
                                    reportings.add(Reporting.notAllMixinsInitialized(it, declaration.span))
                                }
                        }
                }
            }

            thisUsageCapturesWithMutability?.let { capturedWithMutability ->
                if (variable.ownershipAtDeclarationTime == VariableOwnership.BORROWED) {
                    reportings.add(Reporting.borrowedVariableCaptured(variable, this@BoundIdentifierExpression))
                } else {
                    _modifiedContext.trackSideEffect(
                        VariableLifetime.Effect.ValueCaptured(
                            variable,
                            capturedWithMutability,
                            declaration.span,
                        )
                    )
                }
            }

            if (usageContext.requiresVariableLifetimeActive) {
                val lifeStateBeforeUsage = context.getEphemeralState(VariableLifetime, variable)
                reportings.addAll(lifeStateBeforeUsage.validateCapture(this@BoundIdentifierExpression))

                if (thisUsageCapturesWithMutability != null) {
                    val repetitionRelativeToVariable = context.getRepetitionBehaviorRelativeTo(variable.modifiedContext)
                    if (repetitionRelativeToVariable.mayRepeat) {
                        val stateAfterUsage = _modifiedContext.getEphemeralState(VariableLifetime, variable)
                        reportings.addAll(stateAfterUsage.validateRepeatedCapture(lifeStateBeforeUsage, this@BoundIdentifierExpression))
                    }
                }
            }

            if (variable.kind.allowsVisibility) {
                reportings.addAll(variable.visibility.validateAccessFrom(declaration.span, variable))
            }

            return reportings
        }

        override fun findReadsBeyond(boundary: CTContext): Collection<BoundExpression<*>> {
            return when {
                context.containsWithinBoundary(variable, boundary) -> emptySet()
                isCompileTimeConstant -> emptySet()
                else -> setOf(this@BoundIdentifierExpression)
            }
        }

        override val isCompileTimeConstant: Boolean
            get() = !variable.isReAssignable && variable.initializerExpression?.isCompileTimeConstant == true
    }
    inner class ReferringType(val reference: BoundTypeReference) : Referral {
        override val span = declaration.span
        override fun markEvaluationResultUsed() {}
        override fun markEvaluationResultCaptured(withMutability: TypeMutability) {}

        override fun findReadsBeyond(boundary: CTContext): Collection<BoundExpression<*>> {
            // reading type information outside the boundary is pure because type information is compile-time constant
            return emptySet()
        }

        override val isCompileTimeConstant = true
        override val isEvaluationResultAnchored = true // typeinfos are static, no refcounting needed
    }

    override val isEvaluationResultReferenceCounted = false

    private val _backendIr by lazy {
        (referral as? ReferringVariable)?.let { referral ->
            IrVariableAccessExpressionImpl(
                referral.variable.backendIrDeclaration,
            )
        } ?: TODO("implement type references")
    }

    override fun toBackendIrExpression(): IrExpression = _backendIr

    private enum class VariableUsageContext(
        val requiresInitialization: Boolean,
        val requiresVariableLifetimeActive: Boolean,
    ) {
        READ(true, true),
        WRITE(false, false),
    }
}

internal class IrVariableAccessExpressionImpl(
    override val variable: IrVariableDeclaration,
) : IrVariableAccessExpression