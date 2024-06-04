package compiler.binding

import compiler.ast.AssignmentStatement
import compiler.ast.type.TypeMutability
import compiler.ast.type.TypeReference
import compiler.ast.type.TypeVariance
import compiler.binding.SideEffectPrediction.Companion.combineSequentialExecution
import compiler.binding.context.CTContext
import compiler.binding.context.ExecutionScopedCTContext
import compiler.binding.context.MutableExecutionScopedCTContext
import compiler.binding.expression.BoundExpression
import compiler.binding.type.BoundTypeFromArgument
import compiler.binding.type.BoundTypeReference
import compiler.binding.type.IrGenericTypeReferenceImpl
import compiler.binding.type.IrParameterizedTypeImpl
import compiler.binding.type.IrSimpleTypeImpl
import compiler.reportings.NothrowViolationReporting
import compiler.reportings.Reporting
import io.github.tmarsteel.emerge.backend.api.ir.IrAssignmentStatement
import io.github.tmarsteel.emerge.backend.api.ir.IrExpression
import io.github.tmarsteel.emerge.backend.api.ir.IrGenericTypeReference
import io.github.tmarsteel.emerge.backend.api.ir.IrParameterizedType
import io.github.tmarsteel.emerge.backend.api.ir.IrSimpleType
import io.github.tmarsteel.emerge.backend.api.ir.IrTemporaryValueReference
import io.github.tmarsteel.emerge.backend.api.ir.IrType

abstract class BoundAssignmentStatement(
    override val context: ExecutionScopedCTContext,
    override val declaration: AssignmentStatement,
    val toAssignExpression: BoundExpression<*>
) : BoundStatement<AssignmentStatement> {
    protected abstract val targetThrowBehavior: SideEffectPrediction?
    protected abstract val targetReturnBehavior: SideEffectPrediction?

    final override val throwBehavior get() = targetThrowBehavior.combineSequentialExecution(toAssignExpression.throwBehavior)
    final override val returnBehavior get() = targetReturnBehavior.combineSequentialExecution(toAssignExpression.returnBehavior)

    protected val _modifiedContext = MutableExecutionScopedCTContext.deriveFrom(toAssignExpression.modifiedContext)
    override val modifiedContext: ExecutionScopedCTContext = _modifiedContext

    override val implicitEvaluationResultType: BoundTypeReference? = null

    private val seanHelper = SeanHelper()

    final override fun semanticAnalysisPhase1(): Collection<Reporting> {
        return seanHelper.phase1 {
            val reportings = mutableListOf<Reporting>()
            reportings.addAll(toAssignExpression.semanticAnalysisPhase1())
            reportings.addAll(additionalSemanticAnalysisPhase1())

            reportings
        }
    }

    protected abstract fun additionalSemanticAnalysisPhase1(): Collection<Reporting>

    private var implicitEvaluationRequired = false
    override fun requireImplicitEvaluationTo(type: BoundTypeReference) {
        implicitEvaluationRequired = true
    }

    /**
     * does [SemanticallyAnalyzable.semanticAnalysisPhase2] only on the target of the assignment
     * with the goal of making [assignmentTargetType] available.
     */
    protected abstract fun assignmentTargetSemanticAnalysisPhase2(): Collection<Reporting>

    /**
     * the type of the assignment target; if available, must be set after [assignmentTargetSemanticAnalysisPhase2]
     */
    protected abstract val assignmentTargetType: BoundTypeReference?

    abstract fun additionalSemanticAnalysisPhase2(): Collection<Reporting>

    final override fun semanticAnalysisPhase2(): Collection<Reporting> {
        return seanHelper.phase2 {
            toAssignExpression.markEvaluationResultUsed()

            val reportings = mutableListOf<Reporting>()
            reportings.addAll(assignmentTargetSemanticAnalysisPhase2())
            assignmentTargetType?.let(toAssignExpression::setExpectedEvaluationResultType)

            reportings.addAll(toAssignExpression.semanticAnalysisPhase2())
            reportings.addAll(additionalSemanticAnalysisPhase2())

            toAssignExpression.type?.also { assignedType ->
                assignmentTargetType?.also { targetType ->
                    assignedType.evaluateAssignabilityTo(targetType, toAssignExpression.declaration.span)
                        ?.let(reportings::add)

                    val targetTypeNotNullable = targetType.withCombinedNullability(TypeReference.Nullability.NOT_NULLABLE)
                    if (targetTypeNotNullable is BoundTypeFromArgument && targetTypeNotNullable.argument.variance == TypeVariance.OUT) {
                        reportings.add(Reporting.illegalAssignment("Cannot assign to a reference of an out-variant type", this))
                    }
                }
            }

            if (implicitEvaluationRequired) {
                reportings.add(Reporting.assignmentUsedAsExpression(this))
            }

            reportings
        }
    }

    protected abstract fun setTargetNothrow(boundary: NothrowViolationReporting.SideEffectBoundary)

    protected var nothrowBoundary: NothrowViolationReporting.SideEffectBoundary? = null
        private set
    final override fun setNothrow(boundary: NothrowViolationReporting.SideEffectBoundary) {
        seanHelper.requirePhase3NotDone()
        require(nothrowBoundary == null) { "setNothrow called more than once" }

        this.nothrowBoundary = boundary
        toAssignExpression.setNothrow(boundary)
        setTargetNothrow(boundary)
    }

    final override fun semanticAnalysisPhase3(): Collection<Reporting> {
        return seanHelper.phase3 {
            val reportings = mutableSetOf<Reporting>()
            toAssignExpression.markEvaluationResultCaptured(assignmentTargetType?.mutability ?: TypeMutability.READONLY)

            reportings.addAll(toAssignExpression.semanticAnalysisPhase3())
            reportings.addAll(additionalSemanticAnalysisPhase3())

            reportings
        }
    }

    protected abstract fun additionalSemanticAnalysisPhase3(): Collection<Reporting>

    override fun findReadsBeyond(boundary: CTContext): Collection<BoundExpression<*>> {
        return toAssignExpression.findReadsBeyond(boundary)
    }

    override fun findWritesBeyond(boundary: CTContext): Collection<BoundStatement<*>> {
        return toAssignExpression.findWritesBeyond(boundary)
    }

    protected fun IrType.nullable(): IrType = if (isNullable) this else when (this) {
        is IrSimpleType -> IrSimpleTypeImpl(this.baseType, true)
        is IrGenericTypeReference -> IrGenericTypeReferenceImpl(this.parameter, effectiveBound.nullable())
        is IrParameterizedType -> IrParameterizedTypeImpl(this.simpleType.nullable() as IrSimpleType, arguments)
    }
}

/**
 * [BoundAssignmentStatement] is a [BoundExpression] because the compiler wants to detect accidental use of `=` instead
 * of `==`. So [BoundAssignmentStatement.toBackendIrStatement] must return an [IrExpression], as per the [BoundExpression]
 * contract. Consequently, this class must also implement [IrExpression] even though it's not. Unavoidable.
 */
internal class IrAssignmentStatementImpl(
    override val target: IrAssignmentStatement.Target,
    override val value: IrTemporaryValueReference,
) : IrAssignmentStatement