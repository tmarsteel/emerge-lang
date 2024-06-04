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
import compiler.ast.expression.InvocationExpression
import compiler.ast.type.TypeMutability
import compiler.ast.type.TypeVariance
import compiler.binding.BoundExecutable
import compiler.binding.BoundFunction
import compiler.binding.BoundMemberFunction
import compiler.binding.BoundOverloadSet
import compiler.binding.BoundStatement
import compiler.binding.IrCodeChunkImpl
import compiler.binding.SeanHelper
import compiler.binding.SideEffectPrediction
import compiler.binding.SideEffectPrediction.Companion.reduceSequentialExecution
import compiler.binding.basetype.BoundBaseType
import compiler.binding.context.CTContext
import compiler.binding.context.ExecutionScopedCTContext
import compiler.binding.misc_ir.IrCreateStrongReferenceStatementImpl
import compiler.binding.misc_ir.IrCreateTemporaryValueImpl
import compiler.binding.misc_ir.IrDropStrongReferenceStatementImpl
import compiler.binding.misc_ir.IrImplicitEvaluationExpressionImpl
import compiler.binding.misc_ir.IrTemporaryValueReferenceImpl
import compiler.binding.type.*
import compiler.handleCyclicInvocation
import compiler.lexer.IdentifierToken
import compiler.lexer.Span
import compiler.reportings.NothrowViolationReporting
import compiler.reportings.Reporting
import io.github.tmarsteel.emerge.backend.api.ir.IrCreateTemporaryValue
import io.github.tmarsteel.emerge.backend.api.ir.IrDynamicDispatchFunctionInvocationExpression
import io.github.tmarsteel.emerge.backend.api.ir.IrExecutable
import io.github.tmarsteel.emerge.backend.api.ir.IrExpression
import io.github.tmarsteel.emerge.backend.api.ir.IrFunction
import io.github.tmarsteel.emerge.backend.api.ir.IrImplicitEvaluationExpression
import io.github.tmarsteel.emerge.backend.api.ir.IrMemberFunction
import io.github.tmarsteel.emerge.backend.api.ir.IrStaticDispatchFunctionInvocationExpression
import io.github.tmarsteel.emerge.backend.api.ir.IrTemporaryValueReference
import io.github.tmarsteel.emerge.backend.api.ir.IrType

class BoundInvocationExpression(
    override val context: ExecutionScopedCTContext,
    override val declaration: InvocationExpression,
    /** The receiver expression; is null if not specified in the source */
    val receiverExpression: BoundExpression<*>?,
    val functionNameToken: IdentifierToken,
    val valueArguments: List<BoundExpression<*>>,
) : BoundExpression<InvocationExpression>, BoundExecutable<InvocationExpression> {

    private val seanHelper = SeanHelper()

    /**
     * The result of the function dispatching. Is set (non null) after semantic analysis phase 2
     */
    val functionToInvoke: BoundFunction? get() = chosenOverload?.candidate
    private var chosenOverload: OverloadCandidateEvaluation? = null

    override val type: BoundTypeReference? get() = chosenOverload?.returnType ?: chosenOverload?.candidate?.returnType

    var typeArguments: List<BoundTypeArgument>? = null
        private set

    /**
     * [receiverExpression], but `null` iff it is a direct referral to a [BoundBaseTypeDefinition]
     */
    private val receiverExceptReferringType: BoundExpression<*>?
        get() = receiverExpression?.takeUnless { it is BoundIdentifierExpression && it.referral is BoundIdentifierExpression.ReferringType }

    override val throwBehavior: SideEffectPrediction? get() {
        val behaviors = valueArguments.map { it.throwBehavior } + listOf(functionToInvoke?.throwBehavior)
        return behaviors.reduceSequentialExecution()
    }

    override val returnBehavior: SideEffectPrediction? get() {
        val behaviors = valueArguments.map { it.returnBehavior } + listOf(SideEffectPrediction.NEVER)
        return behaviors.reduceSequentialExecution()
    }

    override fun semanticAnalysisPhase1(): Collection<Reporting> =
        seanHelper.phase1 {
            val reportings = mutableSetOf<Reporting>()
            receiverExpression?.semanticAnalysisPhase1()?.let(reportings::addAll)
            receiverExpression?.markEvaluationResultUsed()
            valueArguments.map(BoundExpression<*>::semanticAnalysisPhase1).forEach(reportings::addAll)
            typeArguments = declaration.typeArguments?.map(context::resolveType)
            typeArguments?.forEach {
                if (it.variance != TypeVariance.UNSPECIFIED) {
                    reportings.add(Reporting.varianceOnInvocationTypeArgument(it))
                }
            }
            reportings
        }

    private var evaluationResultUsed = false
    override fun markEvaluationResultUsed() {
        seanHelper.requirePhase1Done()
        seanHelper.requirePhase2NotDone()
        evaluationResultUsed = true
    }

    override fun semanticAnalysisPhase2(): Collection<Reporting> {
        return seanHelper.phase2 {
            valueArguments.forEach { it.markEvaluationResultUsed() }

            val reportings = mutableSetOf<Reporting>()

            receiverExpression?.semanticAnalysisPhase2()?.let(reportings::addAll)

            val availableOverloads: AvailableOverloads? = if (receiverExpression == null || (receiverExpression.type != null && receiverExpression.type !is UnresolvedType)) {
                collectOverloadCandidates()
            } else {
                // receiver is present but type is not known -> cannot determine overload
                null
            }

            availableOverloads?.candidates?.singleOrNull()?.overloads?.singleOrNull()?.let { singleOption ->
                singleOption.parameters.parameters
                    .dropWhile { it === singleOption.parameters.declaredReceiver }
                    .zip(valueArguments)
                    .forEach { (parameter, argument) ->
                        parameter.typeAtDeclarationTime?.also(argument::setExpectedEvaluationResultType)
                    }
            }

            typeArguments?.forEach { reportings.addAll(it.validate(TypeUseSite.Irrelevant(it.astNode.span, null))) }
            valueArguments.forEach { reportings.addAll(it.semanticAnalysisPhase2()) }

            if (availableOverloads == null) {
                return@phase2 reportings
            }

            chosenOverload = selectOverload(availableOverloads, reportings) ?: return@phase2 reportings

            if (chosenOverload!!.returnType == null) {
                handleCyclicInvocation(
                    context = this,
                    action = { chosenOverload!!.candidate.semanticAnalysisPhase2() },
                    onCycle = {
                        reportings.add(
                            Reporting.typeDeductionError(
                                "Cannot infer return type of the call to function ${functionNameToken.value} because the inference is cyclic here. Specify the return type explicitly.",
                                declaration.span,
                            )
                        )
                    }
                )
            }

            chosenOverload!!.candidate.parameters.parameters.zip(valueArguments)
                .filter { (parameter, _) -> parameter.ownershipAtDeclarationTime == VariableOwnership.CAPTURED }
                .forEach { (parameter, argument) -> argument.markEvaluationResultCaptured(parameter.typeAtDeclarationTime?.mutability ?: TypeMutability.READONLY) }

            return@phase2 reportings
        }
    }

    private fun collectOverloadCandidates(): AvailableOverloads {
        assert((receiverExpression == null) xor (receiverExpression?.type != null))

        val candidateConstructors = if (receiverExpression != null) null else {
            context.resolveBaseType(functionNameToken.value)
                ?.constructor
                ?.let { BoundOverloadSet.fromSingle(it) }
                ?.let(::setOf)
        }
        val candidateTopLevelFunctions = context.getToplevelFunctionOverloadSetsBySimpleName(functionNameToken.value)
        val candidateMemberFunctions = receiverExpression?.type?.findMemberFunction(functionNameToken.value) ?: emptySet()

        val allCandidates = (candidateConstructors ?: emptySet()) + candidateTopLevelFunctions + candidateMemberFunctions

        return AvailableOverloads(
            allCandidates,
            candidateConstructors != null,
            candidateTopLevelFunctions.isNotEmpty(),
        )
    }

    /**
     * Selects one of the given overloads, including ones that are not a legal match if there are no legal alternatives.
     * Also performs the following checks and reports accordingly:
     * * absolutely no candidate available to evaluate
     * * of the evaluated constructors or functions, none match
     * * if there is only one overload to pick from, forwards any reportings from evaluating that candidate
     */
    private fun selectOverload(overloadCandidates: AvailableOverloads, reportings: MutableCollection<in Reporting>): OverloadCandidateEvaluation? {
        if (valueArguments.any { it.type == null}) {
            // resolving the overload does not make sense if not all parameter types can be deducted
            // note that for erroneous type references, the parameter type will be a non-null UnresolvedType
            // so in that case we can still continue
            return null
        }

        if (receiverExpression != null && receiverExpression.type == null) {
            // same goes for the receiver
            return null
        }

        val (allCandidates, constructorsConsidered, anyTopLevelFunctions) = overloadCandidates

        if (allCandidates.isEmpty()) {
            reportings.add(Reporting.noMatchingFunctionOverload(functionNameToken, receiverExpression?.type, valueArguments, false))
            return null
        }

        val evaluations = allCandidates.filterAndSortByMatchForInvocationTypes(
            // for static member fns, receiverExpression helps discover them. But for the actual invocation,
            // the receiver stops to matter
            receiverExceptReferringType,
        )

        if (evaluations.isEmpty()) {
            // TODO: pass on the mismatch reason for all candidates?
            if (constructorsConsidered) {
                reportings.add(
                    Reporting.unresolvableConstructor(
                        functionNameToken,
                        valueArguments,
                        anyTopLevelFunctions,
                    )
                )
            } else {
                reportings.add(
                    Reporting.noMatchingFunctionOverload(
                        functionNameToken,
                        receiverExpression?.type,
                        valueArguments,
                        anyTopLevelFunctions,
                    )
                )
            }
        }

        val legalMatches = evaluations.filter { !it.hasErrors }
        when (legalMatches.size) {
            0 -> {
                if (evaluations.size == 1) {
                    val singleEval = evaluations.single()
                    // if there is only a single candidate, the errors found in validating are 100% applicable to be shown to the user
                    reportings.addAll(singleEval.unification.reportings.also {
                        check(it.any { it.level >= Reporting.Level.ERROR }) {
                            "Cannot choose overload to invoke, but evaluation of single overload candidate didn't yield any error -- what?"
                        }
                    })
                    return singleEval
                } else {
                    val disjointParameterIndices = evaluations.indicesOfDisjointlyTypedParameters().toSet()
                    val reducedEvaluations =
                        evaluations.filter { it.indicesOfErroneousParameters.none { it in disjointParameterIndices } }
                    if (reducedEvaluations.size == 1) {
                        val singleEval = reducedEvaluations.single()
                        reportings.addAll(singleEval.unification.reportings)
                        return singleEval
                    } else {
                        reportings.add(
                            Reporting.noMatchingFunctionOverload(
                                functionNameToken,
                                receiverExpression?.type,
                                valueArguments,
                                true
                            )
                        )
                        return evaluations.firstOrNull()
                    }
                }
            }
            1 -> return legalMatches.single()
            else -> {
                reportings.add(Reporting.ambiguousInvocation(this, evaluations.map { it.candidate }))
                return legalMatches.firstOrNull()
            }
        }
    }

    /**
     * Given the invocation types `receiverType` and `parameterTypes` of an invocation site
     * returns the functions matching the types sorted by matching quality to the given
     * types (see [BoundTypeReference.evaluateAssignabilityTo] and [BoundTypeReference.assignMatchQuality])
     *
     * In essence, this function is the overload resolution algorithm of Emerge.
     *
     * @return a list of matching functions, along with the resolved generics. Use the TypeUnification::right with the
     * returned function to determine the return type if that function were invoked.
     * The list is sorted by best-match first, worst-match last. However, if the return value has more than one element,
     * it has to be treated as an error because the invocation is ambiguous.
     */
    private fun Iterable<BoundOverloadSet<*>>.filterAndSortByMatchForInvocationTypes(
        receiver: BoundExpression<*>?,
    ): List<OverloadCandidateEvaluation> {
        check((receiver != null) xor (receiver?.type == null))
        val receiverType = receiver?.type
        val argumentsIncludingReceiver = listOfNotNull(receiver) + valueArguments
        return this
            .asSequence()
            .filter { it.parameterCount == argumentsIncludingReceiver.size }
            .flatMap { it.overloads }
            // filter by (declared receiver)
            .filter { candidateFn -> (receiverType != null) == candidateFn.declaresReceiver }
            // filter by incompatible number of parameters
            .filter { it.parameters.parameters.size == argumentsIncludingReceiver.size }
            .mapNotNull { candidateFn ->
                if (candidateFn.parameterTypes.any { it == null }) {
                    // types not fully resolved, don't consider
                    return@mapNotNull null
                }

                // TODO: source location
                val returnTypeArgsLocation = typeArguments
                    ?.mapNotNull { it.astNode.span }
                    ?.reduce(Span::rangeTo)
                    ?: declaration.span
                val returnTypeWithVariables = candidateFn.returnType?.withTypeVariables(candidateFn.allTypeParameters)
                var unification = TypeUnification.fromExplicit(candidateFn.declaredTypeParameters, typeArguments, returnTypeArgsLocation, allowMissingTypeArguments = true)
                if (returnTypeWithVariables != null) {
                    if (expectedEvaluationResultType != null) {
                        unification = unification.doWithIgnoringReportings { obliviousUnification ->
                            expectedEvaluationResultType!!.unify(returnTypeWithVariables, Span.UNKNOWN, obliviousUnification)
                        }
                    }
                }

                @Suppress("UNCHECKED_CAST") // the check is right above
                val rightSideTypes = (candidateFn.parameterTypes as List<BoundTypeReference>)
                    .map { it.withTypeVariables(candidateFn.allTypeParameters) }
                check(rightSideTypes.size == argumentsIncludingReceiver.size)

                val indicesOfErroneousParameters = ArrayList<Int>(argumentsIncludingReceiver.size)
                unification = argumentsIncludingReceiver
                    .zip(rightSideTypes)
                    .foldIndexed(unification) { parameterIndex, carryUnification, (argument, parameterType) ->
                        val unificationAfterParameter = parameterType.unify(argument.type!!, argument.declaration.span, carryUnification)
                        if (unificationAfterParameter.getErrorsNotIn(carryUnification).any()) {
                            indicesOfErroneousParameters.add(parameterIndex)
                        }
                        unificationAfterParameter
                    }

                OverloadCandidateEvaluation(
                    candidateFn,
                    unification,
                    returnTypeWithVariables?.instantiateFreeVariables(unification),
                    indicesOfErroneousParameters,
                )
            }
            .toList()
    }

    private var nothrowBoundary: NothrowViolationReporting.SideEffectBoundary? = null
    override fun setNothrow(boundary: NothrowViolationReporting.SideEffectBoundary) {
        this.nothrowBoundary = boundary
    }

    override fun semanticAnalysisPhase3(): Collection<Reporting> {
        return seanHelper.phase3 {
            val reportings = mutableSetOf<Reporting>()

            if (receiverExpression != null) {
                reportings += receiverExpression.semanticAnalysisPhase3()
            }

            reportings += valueArguments.flatMap { it.semanticAnalysisPhase3() }
            functionToInvoke?.let { targetFn ->
                reportings.addAll(
                    targetFn.validateAccessFrom(functionNameToken.span)
                )
                nothrowBoundary?.let { nothrowBoundary ->
                    if (targetFn.throwBehavior != SideEffectPrediction.NEVER) {
                        reportings.add(Reporting.nothrowViolatingInvocation(this, nothrowBoundary))
                    }
                    if (!evaluationResultUsed && targetFn.returnType?.destructorThrowBehavior != SideEffectPrediction.NEVER) {
                        reportings.add(Reporting.droppingReferenceToObjectWithThrowingConstructor(this, nothrowBoundary))
                    }
                }
            }

            return@phase3 reportings
        }
    }

    override fun findReadsBeyond(boundary: CTContext): Collection<BoundExpression<*>> {
        seanHelper.requirePhase2Done()

        val byReceiver = receiverExpression?.findReadsBeyond(boundary) ?: emptySet()
        val byParameters = valueArguments.flatMap { it.findReadsBeyond(boundary) }

        val invokedFunctionIsPure = functionToInvoke?.let {
            it.semanticAnalysisPhase3()
            BoundFunction.Purity.PURE.contains(it.purity)
        } ?: true

        val bySelf = if (invokedFunctionIsPure) emptySet() else setOf(this)

        return byReceiver + byParameters + bySelf
    }

    override fun findWritesBeyond(boundary: CTContext): Collection<BoundStatement<*>> {
        seanHelper.requirePhase2Done()

        val byReceiver = receiverExpression?.findWritesBeyond(boundary) ?: emptySet()
        val byParameters = valueArguments.flatMap { it.findWritesBeyond(boundary) }

        val invokedFunctionIsReadonly = functionToInvoke?.let {
            it.semanticAnalysisPhase3()
            BoundFunction.Purity.READONLY.contains(it.purity)
        } ?: true
        val bySelf: Collection<BoundStatement<*>> = if (invokedFunctionIsReadonly) emptySet() else setOf(this)

        return byReceiver + byParameters + bySelf
    }

    private var expectedEvaluationResultType: BoundTypeReference? = null

    override fun setExpectedEvaluationResultType(type: BoundTypeReference) {
        seanHelper.requirePhase2NotDone()
        expectedEvaluationResultType = type
    }

    override val isEvaluationResultReferenceCounted = true
    override val isCompileTimeConstant: Boolean
        get() {
            val localDispatchedFunction = functionToInvoke ?: return false
            if (!BoundFunction.Purity.PURE.contains(localDispatchedFunction.purity)) {
                return false
            }
            val receiverIsConstant = receiverExpression?.isCompileTimeConstant ?: true
            return receiverIsConstant && valueArguments.all { it.isCompileTimeConstant }
        }

    private fun buildBackendIrInvocation(arguments: List<IrTemporaryValueReference>): IrExpression {
        val isCallOnInterfaceType = (receiverExpression?.type as? RootResolvedTypeReference)?.baseType?.kind == BoundBaseType.Kind.INTERFACE
        val fn = functionToInvoke!!
        val returnType = type!!.toBackendIr()
        val irResolvedTypeArgs = chosenOverload!!.unification.bindings.entries
            .associate { (typeVar, binding) -> typeVar.parameter.name to binding.toBackendIr() }

        if (fn is BoundMemberFunction && fn.isVirtual && isCallOnInterfaceType) {
            check(receiverExceptReferringType != null)
            return IrDynamicDispatchFunctionInvocationImpl(
                arguments.first(),
                fn.toBackendIr(),
                arguments,
                irResolvedTypeArgs,
                returnType
            )
        }

        return IrStaticDispatchFunctionInvocationImpl(
            functionToInvoke!!.toBackendIr(),
            arguments,
            irResolvedTypeArgs,
            type!!.toBackendIr(),
        )
    }

    override fun toBackendIrExpression(): IrExpression {
        return buildInvocationLikeIr(
            listOfNotNull(receiverExceptReferringType) + valueArguments,
            ::buildBackendIrInvocation,
        )
    }

    override fun toBackendIrStatement(): IrExecutable {
        return buildInvocationLikeIr(
            listOfNotNull(receiverExceptReferringType) + valueArguments,
            ::buildBackendIrInvocation,
            { listOf(IrDropStrongReferenceStatementImpl(it)) },
        ).code
    }
}

private data class AvailableOverloads(
    val candidates: Collection<BoundOverloadSet<*>>,
    val constructorsConsidered: Boolean,
    val anyTopLevelFunctions: Boolean,
)

private data class OverloadCandidateEvaluation(
    val candidate: BoundFunction,
    val unification: TypeUnification,
    val returnType: BoundTypeReference?,
    val indicesOfErroneousParameters: Collection<Int>,
) {
    val hasErrors = unification.reportings.any { it.level >= Reporting.Level.ERROR }
}

private fun Collection<OverloadCandidateEvaluation>.indicesOfDisjointlyTypedParameters(): Sequence<Int> {
    if (isEmpty()) {
        return emptySequence()
    }

    return (0 until first().candidate.parameters.parameters.size).asSequence()
        .filter { parameterIndex ->
            val parameterTypesAtIndex = this.map { it.candidate.parameters.parameters[parameterIndex] }
            parameterTypesAtIndex.nonDisjointPairs().none()
        }
}

internal class IrStaticDispatchFunctionInvocationImpl(
    override val function: IrFunction,
    override val arguments: List<IrTemporaryValueReference>,
    override val typeArgumentsAtCallSite: Map<String, IrType>,
    override val evaluatesTo: IrType,
) : IrStaticDispatchFunctionInvocationExpression

private class IrDynamicDispatchFunctionInvocationImpl(
    override val dispatchOn: IrTemporaryValueReference,
    override val function: IrMemberFunction,
    override val arguments: List<IrTemporaryValueReference>,
    override val typeArgumentsAtCallSite: Map<String, IrType>,
    override val evaluatesTo: IrType
) : IrDynamicDispatchFunctionInvocationExpression

/**
 * Contains logic for invocation-like IR. Used for actual invocations, but also e.g. for [BoundArrayLiteralExpression].
 * Doesn't assume any value for [BoundExpression.isEvaluationResultReferenceCounted]; refcounting logic can be cleanly
 * customized with [buildResultCleanup].
 */
internal fun buildInvocationLikeIr(
    boundArgumentExprs: List<BoundExpression<*>>,
    buildActualCall: (arguments: List<IrTemporaryValueReference>) -> IrExpression,
    buildResultCleanup: (IrTemporaryValueReference) -> List<IrExecutable> = { emptyList() },
): IrImplicitEvaluationExpression {
    val prepareArgumentsCode = ArrayList<IrExecutable>(boundArgumentExprs.size * 2)
    val argumentTemporaries = ArrayList<IrCreateTemporaryValue>(boundArgumentExprs.size)
    val cleanUpArgumentsCode = ArrayList<IrExecutable>(boundArgumentExprs.size)

    for (boundArgumentExpr in boundArgumentExprs) {
        val irExpr = boundArgumentExpr.toBackendIrExpression()
        val temporary = IrCreateTemporaryValueImpl(irExpr)
        argumentTemporaries.add(temporary)
        prepareArgumentsCode.add(temporary)
        if (!boundArgumentExpr.isEvaluationResultReferenceCounted) {
            prepareArgumentsCode.add(IrCreateStrongReferenceStatementImpl(temporary))
        }
        cleanUpArgumentsCode.add(IrDropStrongReferenceStatementImpl(temporary))
    }

    val returnValueTemporary = IrCreateTemporaryValueImpl(
        buildActualCall(argumentTemporaries.map { IrTemporaryValueReferenceImpl(it) })
    )
    val returnValueTemporaryRef = IrTemporaryValueReferenceImpl(returnValueTemporary)
    val cleanupCode = buildResultCleanup(returnValueTemporaryRef)
    return IrImplicitEvaluationExpressionImpl(
        IrCodeChunkImpl(prepareArgumentsCode + returnValueTemporary + cleanUpArgumentsCode + cleanupCode),
        returnValueTemporaryRef,
    )
}