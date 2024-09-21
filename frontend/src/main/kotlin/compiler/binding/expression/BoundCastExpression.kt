package compiler.binding.expression

import compiler.InternalCompilerError
import compiler.ast.Expression
import compiler.ast.expression.AstCastExpression
import compiler.ast.type.TypeMutability
import compiler.ast.type.TypeReference
import compiler.binding.IrCodeChunkImpl
import compiler.binding.SideEffectPrediction
import compiler.binding.basetype.BoundBaseType
import compiler.binding.context.ExecutionScopedCTContext
import compiler.binding.expression.BoundExpression.Companion.wrapIrAsStatement
import compiler.binding.misc_ir.IrCreateTemporaryValueImpl
import compiler.binding.misc_ir.IrImplicitEvaluationExpressionImpl
import compiler.binding.misc_ir.IrTemporaryValueReferenceImpl
import compiler.binding.type.BoundTypeReference
import compiler.binding.type.TypeUseSite
import compiler.reportings.NothrowViolationReporting
import compiler.reportings.Reporting
import io.github.tmarsteel.emerge.backend.api.ir.IrExecutable
import io.github.tmarsteel.emerge.backend.api.ir.IrExpression
import io.github.tmarsteel.emerge.backend.api.ir.IrTemporaryValueReference
import io.github.tmarsteel.emerge.backend.api.ir.IrType
import io.github.tmarsteel.emerge.common.CanonicalElementName

/**
 * Currently this is merely a way to specify the type of numeric literals
 * For that use case it is supposed to stay long term; i think this is much cleaner than suffixes
 */
class BoundCastExpression(
    override val context: ExecutionScopedCTContext,
    override val declaration: AstCastExpression,
    val value: BoundExpression<*>,
    val safeCast: Boolean,
) : BoundExpression<Expression> by value {
    override val throwBehavior: SideEffectPrediction? get() = when {
        safeCast -> value.throwBehavior
        else -> SideEffectPrediction.POSSIBLY
    }

    override lateinit var type: BoundTypeReference
        private set

    private var baseTypeToCheck: BoundBaseType? = null
    private val isTypedNumericLiteral get() = value is BoundNumericLiteral

    override fun semanticAnalysisPhase1(): Collection<Reporting> {
        val reportings = mutableListOf<Reporting>()

        reportings.addAll(value.semanticAnalysisPhase1())

        val type = context.resolveType(declaration.toType)
        this.type = type
        if (safeCast) {
            this.type = this.type.withCombinedNullability(TypeReference.Nullability.NULLABLE)
        }
        reportings.addAll(type.validate(TypeUseSite.Irrelevant(declaration.span, null)))
        baseTypeToCheck = validateTypeCheck(this, type, reportings)

        if (isTypedNumericLiteral) {
            // there is no suffix type notation for numeric literals; instead casting should do that
            // this enables the numeric literal to change its type based on what is required, e.g. 3 as U16
            value.setExpectedEvaluationResultType(type)
        }

        return reportings
    }

    private var expectedMutability: TypeMutability? = null
    override fun setExpectedEvaluationResultType(type: BoundTypeReference) {
        // don't forward / isolate this from the to-be-cast expression
        expectedMutability = type.mutability
    }

    override fun semanticAnalysisPhase2(): Collection<Reporting> {
        val reportings = value.semanticAnalysisPhase2().toMutableSet()
        val valueType = value.type
        val expectedMutability = this.expectedMutability
        if (valueType != null && declaration.toType.mutability == null && expectedMutability != null && valueType.mutability.isAssignableTo(expectedMutability)) {
            // user didn't specify mutability but the value fits what is expected in the context; allow this cross-talk across the cast boundary
            this.type = type.withMutability(expectedMutability)
        }

        return reportings
    }

    private var nothrowBoundary: NothrowViolationReporting.SideEffectBoundary? = null
    override fun setNothrow(boundary: NothrowViolationReporting.SideEffectBoundary) {
        nothrowBoundary = boundary
        value.setNothrow(boundary)
    }

    override fun semanticAnalysisPhase3(): Collection<Reporting> {
        val reportings = mutableListOf<Reporting>()

        reportings.addAll(value.semanticAnalysisPhase3())
        if (nothrowBoundary != null && !isTypedNumericLiteral) {
            reportings.add(Reporting.nothrowViolatingCast(this, nothrowBoundary!!))
        }

        return reportings
    }

    override fun toBackendIrExpression(): IrExpression {
        if (isTypedNumericLiteral) {
            return value.toBackendIrExpression()
        }

        val irTargetType = type.toBackendIr()
        val valueToCastTemporary = IrCreateTemporaryValueImpl(value.toBackendIrExpression())
        val instanceOfResultTemporary = IrCreateTemporaryValueImpl(buildInstanceOf(context.swCtx, IrTemporaryValueReferenceImpl(valueToCastTemporary), baseTypeToCheck!!))

        if (safeCast) {
            val nullResultTemporary = IrCreateTemporaryValueImpl(IrNullLiteralExpressionImpl(irTargetType.asNullable()))
            val castOperationResultTemporary = IrCreateTemporaryValueImpl(IrIfExpressionImpl(
                condition = IrTemporaryValueReferenceImpl(instanceOfResultTemporary),
                thenBranch = IrImplicitEvaluationExpressionImpl(IrCodeChunkImpl(emptyList()), IrTemporaryValueReferenceImpl(valueToCastTemporary)),
                elseBranch = IrImplicitEvaluationExpressionImpl(
                    IrCodeChunkImpl(listOf(
                        nullResultTemporary
                    )),
                    IrTemporaryValueReferenceImpl(nullResultTemporary),
                ),
                irTargetType.asNullable(),
            ))
            return IrImplicitEvaluationExpressionImpl(
                IrCodeChunkImpl(listOf(
                    valueToCastTemporary,
                    instanceOfResultTemporary,
                    castOperationResultTemporary,
                )),
                IrTemporaryValueReferenceImpl(castOperationResultTemporary),
            )
        }

        val classCastErrorBoundType = context.swCtx
            .getPackage(CanonicalElementName.Package(listOf("emerge", "core")))
            ?.resolveBaseType("CastError")
            ?: throw InternalCompilerError("Could not resolve base type emerge.core.CastError")

        val exceptionInstanceExpr = buildGenericInvocationLikeIr(
            context,
            declaration.span,
            emptyList(),
            { arguments, landingpad ->
                IrStaticDispatchFunctionInvocationImpl(
                    classCastErrorBoundType.constructor!!.toBackendIr(),
                    arguments,
                    emptyMap(),
                    classCastErrorBoundType.baseReference.withMutability(TypeMutability.EXCLUSIVE).toBackendIr(),
                    landingpad,
                )
            },
            assumeNothrow = false,
        )

        return IrImplicitEvaluationExpressionImpl(
            IrCodeChunkImpl(listOf(
                valueToCastTemporary,
                instanceOfResultTemporary,
                IrConditionalBranchImpl(
                    condition = IrTemporaryValueReferenceImpl(instanceOfResultTemporary),
                    thenBranch = IrCodeChunkImpl(emptyList()),
                    elseBranch = buildIrThrow(
                        context,
                        exceptionInstanceExpr,
                        throwableInstanceIsReferenceCounted = true, // ctor invocation, is always counted
                    ),
                ),
            )),
            object : IrTemporaryValueReference {
                override val declaration = valueToCastTemporary
                override val type: IrType get()= this@BoundCastExpression.type.toBackendIr()
            }
        )
    }

    override fun toBackendIrStatement(): IrExecutable {
        return this.wrapIrAsStatement()
    }
}