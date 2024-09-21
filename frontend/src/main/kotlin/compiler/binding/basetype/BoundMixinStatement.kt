package compiler.binding.basetype

import compiler.ast.AstMixinStatement
import compiler.ast.type.TypeMutability
import compiler.ast.type.TypeReference
import compiler.binding.BoundExecutable
import compiler.binding.BoundStatement
import compiler.binding.SeanHelper
import compiler.binding.SideEffectPrediction
import compiler.binding.context.CTContext
import compiler.binding.context.MutableExecutionScopedCTContext
import compiler.binding.context.effect.PartialObjectInitialization
import compiler.binding.expression.BoundExpression
import compiler.binding.type.BoundTypeReference
import compiler.reportings.Diagnosis
import compiler.reportings.NothrowViolationReporting
import compiler.reportings.Reporting
import io.github.tmarsteel.emerge.backend.api.ir.IrExecutable

class BoundMixinStatement(
    val expression: BoundExpression<*>,
    override val declaration: AstMixinStatement,
) : BoundStatement<AstMixinStatement> {
    override val context = expression.context
    override val modifiedContext = MutableExecutionScopedCTContext.deriveFrom(expression.context)

    override val throwBehavior: SideEffectPrediction? get() = expression.throwBehavior
    override val returnBehavior: SideEffectPrediction? get()= expression.returnBehavior

    private val seanHelper = SeanHelper()

    override fun setNothrow(boundary: NothrowViolationReporting.SideEffectBoundary) {
        expression.setNothrow(boundary)
    }

    override fun semanticAnalysisPhase1(): Collection<Reporting> {
        return seanHelper.phase1 {
            val reportings = expression.semanticAnalysisPhase1()
            expression.markEvaluationResultUsed()
            expression.setExpectedEvaluationResultType(
                context.swCtx.any.baseReference
                    .withMutability(TypeMutability.EXCLUSIVE)
                    .withCombinedNullability(TypeReference.Nullability.NOT_NULLABLE)
            )
            return@phase1 reportings
        }
    }

    /**
     * Initialized during [semanticAnalysisPhase2]
     */
    var delegateMemberVariable: BoundBaseTypeMemberVariable? = null
        private set

    override fun semanticAnalysisPhase2(): Collection<Reporting> {
        return seanHelper.phase2 {
            val reportings = expression.semanticAnalysisPhase2().toMutableSet()
            delegateMemberVariable = context.registerMixin(this, Diagnosis.addingTo(reportings))
            delegateMemberVariable?.let { memberVar ->
                val selfVar = context.resolveVariable("self", true)!!
                modifiedContext.trackSideEffect(PartialObjectInitialization.Effect.WriteToMemberVariableEffect(selfVar, memberVar))
            }
            expression.markEvaluationResultCaptured(TypeMutability.EXCLUSIVE)
            return@phase2 reportings
        }
    }

    override fun setExpectedReturnType(type: BoundTypeReference) {
        expression.setExpectedReturnType(type)
    }

    override fun semanticAnalysisPhase3(): Collection<Reporting> {
        return seanHelper.phase3 {
            val reportings = expression.semanticAnalysisPhase3()
            return@phase3 reportings
        }
    }

    override fun findReadsBeyond(boundary: CTContext): Collection<BoundExpression<*>> {
        return expression.findReadsBeyond(boundary)
    }

    override fun findWritesBeyond(boundary: CTContext): Collection<BoundExecutable<*>> {
        return expression.findWritesBeyond(boundary)
    }

    override fun toBackendIrStatement(): IrExecutable {
        TODO("Not yet implemented")
    }
}