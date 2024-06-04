package compiler.binding.expression

import compiler.CoreIntrinsicsModule
import compiler.InternalCompilerError
import compiler.ast.expression.ArrayLiteralExpression
import compiler.ast.type.TypeArgument
import compiler.ast.type.TypeReference
import compiler.ast.type.TypeVariance
import compiler.binding.BoundStatement
import compiler.binding.IrCodeChunkImpl
import compiler.binding.SideEffectPrediction.Companion.reduceSequentialExecution
import compiler.binding.basetype.BoundBaseType
import compiler.binding.context.CTContext
import compiler.binding.context.ExecutionScopedCTContext
import compiler.binding.misc_ir.IrCreateTemporaryValueImpl
import compiler.binding.misc_ir.IrImplicitEvaluationExpressionImpl
import compiler.binding.misc_ir.IrTemporaryValueReferenceImpl
import compiler.binding.type.BoundTypeArgument
import compiler.binding.type.BoundTypeReference
import compiler.binding.type.IrSimpleTypeImpl
import compiler.binding.type.RootResolvedTypeReference
import compiler.binding.type.TypeUnification
import compiler.reportings.NothrowViolationReporting
import compiler.reportings.Reporting
import io.github.tmarsteel.emerge.backend.api.ir.IrExecutable
import io.github.tmarsteel.emerge.backend.api.ir.IrExpression
import io.github.tmarsteel.emerge.backend.api.ir.IrNullInitializedArrayExpression
import io.github.tmarsteel.emerge.backend.api.ir.IrType
import io.github.tmarsteel.emerge.backend.llvm.indexed
import java.math.BigInteger

class BoundArrayLiteralExpression(
    override val context: ExecutionScopedCTContext,
    override val declaration: ArrayLiteralExpression,
    val elements: List<BoundExpression<*>>,
) : BoundLiteralExpression<ArrayLiteralExpression> {
    override val modifiedContext: ExecutionScopedCTContext = elements.lastOrNull()?.modifiedContext ?: context

    override val throwBehavior get() = elements.map { it.throwBehavior }.reduceSequentialExecution()
    override val returnBehavior get() = elements.map { it.returnBehavior }.reduceSequentialExecution()

    private var expectedEvaluationResultType: BoundTypeReference? = null
    private var expectedElementType: BoundTypeReference? = null
    override var type: BoundTypeReference? = null
        private set

    override val implicitEvaluationResultType get() = type

    private val arrayType: BoundBaseType by lazy {
        val corePackage = context.swCtx.getPackage(CoreIntrinsicsModule.NAME)
            ?: throw InternalCompilerError("The software context doesn't define the default package ${CoreIntrinsicsModule.NAME}")
        corePackage.resolveBaseType("Array")
            ?: throw InternalCompilerError("The software context doesn't define ${CoreIntrinsicsModule.NAME}.Array")
    }

    override fun semanticAnalysisPhase1(): Collection<Reporting> {
        return elements.flatMap { it.semanticAnalysisPhase1() }
    }

    override fun semanticAnalysisPhase2(): Collection<Reporting> {
        elements.forEach { it.markEvaluationResultUsed() }

        val reportings = elements
            .flatMap { it.semanticAnalysisPhase2() }
            .toMutableSet()

        val elementType: BoundTypeReference
        if (expectedElementType != null) {
            elementType = expectedElementType!!
            elements.forEach { element ->
                element.type?.let {
                    val unification = elementType.unify(it, element.declaration.span, TypeUnification.EMPTY)
                    reportings.addAll(unification.reportings)
                }
            }
        } else {
            elementType = elements
                .mapNotNull { it.type }
                .reduceOrNull(BoundTypeReference::closestCommonSupertypeWith)
                ?: context.swCtx.any.baseReference
        }

        type = RootResolvedTypeReference(
            TypeReference(arrayType.simpleName),
            arrayType,
            listOf(BoundTypeArgument(
                context,
                TypeArgument(TypeVariance.UNSPECIFIED, TypeReference("_")),
                elementType,
            ))
        )
        expectedEvaluationResultType?.let {
            type = type?.withMutability(it.mutability)
        }

        return reportings
    }

    override fun setNothrow(boundary: NothrowViolationReporting.SideEffectBoundary) {
        elements.forEach { it.setNothrow(boundary) }
    }

    override fun semanticAnalysisPhase3(): Collection<Reporting> {
        return elements.flatMap { it.semanticAnalysisPhase3() }
    }

    override fun findReadsBeyond(boundary: CTContext): Collection<BoundExpression<*>> {
        return elements.flatMap { it.findReadsBeyond(boundary) }
    }

    override fun findWritesBeyond(boundary: CTContext): Collection<BoundStatement<*>> {
        return elements.flatMap { it.findWritesBeyond(boundary) }
    }

    override fun setExpectedReturnType(type: BoundTypeReference) {
        elements.forEach { it.setExpectedReturnType(type) }
    }

    override fun setExpectedEvaluationResultType(type: BoundTypeReference) {
        expectedEvaluationResultType = type

        if (type !is RootResolvedTypeReference || type.baseType != arrayType) {
            // ignore
            return
        }

        expectedElementType = type.arguments?.firstOrNull()?.asBoundTypeReference() ?: return
        elements.forEach { it.setExpectedEvaluationResultType(expectedElementType!!) }
    }

    override val isEvaluationResultReferenceCounted = true

    override val isCompileTimeConstant: Boolean
        get() = elements.all { it.isCompileTimeConstant }

    override fun toBackendIrExpression(): IrExpression {
        val arraySetFn = context.swCtx.array.resolveMemberFunction("set")
            .single()
            .overloads
            .single()
            .also {
                check(it.parameters.parameters.size == 3)
                check((it.returnType as RootResolvedTypeReference).baseType == context.swCtx.unit)
            }
            .toBackendIr()

        val irType = type!!.toBackendIr()
        val irElementType = (type as RootResolvedTypeReference).arguments!!.single().asBoundTypeReference().toBackendIr()

        return buildInvocationLikeIr(
            elements,
            { args ->
                val arrayTemporary = IrCreateTemporaryValueImpl(
                    IrNullInitializedArrayExpressionImpl(irType, irElementType, args.size.toULong())
                )
                val arrayTemporaryRef = IrTemporaryValueReferenceImpl(arrayTemporary)
                val instrs = mutableListOf<IrExecutable>()
                instrs.add(arrayTemporary)
                for ((index, element) in args.indexed()) {
                    val indexTemporary = IrCreateTemporaryValueImpl(
                        IrIntegerLiteralExpressionImpl(BigInteger.valueOf(index.toLong()), IrSimpleTypeImpl(context.swCtx.uword.toBackendIr(), false))
                    )
                    instrs.add(indexTemporary)
                    instrs.add(IrCreateTemporaryValueImpl(
                        IrStaticDispatchFunctionInvocationImpl(
                            arraySetFn,
                            listOf(
                                arrayTemporaryRef,
                                IrTemporaryValueReferenceImpl(indexTemporary),
                                element,
                            ),
                            mapOf(
                                (type as RootResolvedTypeReference).baseType.typeParameters!!.single().name to irElementType,
                            ),
                            IrSimpleTypeImpl(context.swCtx.unit.toBackendIr(), false),
                        ),
                    ))
                }
                IrImplicitEvaluationExpressionImpl(IrCodeChunkImpl(instrs), arrayTemporaryRef)
            },
        )
    }
}

private class IrNullInitializedArrayExpressionImpl(
    override val evaluatesTo: IrType,
    override val elementType: IrType,
    override val size: ULong
) : IrNullInitializedArrayExpression