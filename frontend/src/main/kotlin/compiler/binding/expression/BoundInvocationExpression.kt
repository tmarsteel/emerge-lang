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

import compiler.OnceAction
import compiler.ast.expression.InvocationExpression
import compiler.binding.BoundExecutable
import compiler.binding.BoundFunction
import compiler.binding.BoundOverloadSet
import compiler.binding.BoundStatement
import compiler.binding.IrCodeChunkImpl
import compiler.binding.context.CTContext
import compiler.binding.context.ExecutionScopedCTContext
import compiler.binding.misc_ir.IrCreateReferenceStatementImpl
import compiler.binding.misc_ir.IrCreateTemporaryValueImpl
import compiler.binding.misc_ir.IrDropReferenceStatementImpl
import compiler.binding.misc_ir.IrImplicitEvaluationExpressionImpl
import compiler.binding.misc_ir.IrTemporaryValueReferenceImpl
import compiler.binding.type.*
import compiler.handleCyclicInvocation
import compiler.lexer.IdentifierToken
import compiler.lexer.SourceLocation
import compiler.reportings.Reporting
import compiler.reportings.ValueNotAssignableReporting
import io.github.tmarsteel.emerge.backend.api.ir.IrCreateTemporaryValue
import io.github.tmarsteel.emerge.backend.api.ir.IrExecutable
import io.github.tmarsteel.emerge.backend.api.ir.IrExpression
import io.github.tmarsteel.emerge.backend.api.ir.IrFunction
import io.github.tmarsteel.emerge.backend.api.ir.IrImplicitEvaluationExpression
import io.github.tmarsteel.emerge.backend.api.ir.IrStaticDispatchFunctionInvocationExpression
import io.github.tmarsteel.emerge.backend.api.ir.IrTemporaryValueReference
import io.github.tmarsteel.emerge.backend.api.ir.IrType

class BoundInvocationExpression(
    override val context: ExecutionScopedCTContext,
    override val declaration: InvocationExpression,
    /** The receiver expression; is null if not specified in the source */
    val receiverExpression: BoundExpression<*>?,
    val functionNameToken: IdentifierToken,
    val valueArguments: List<BoundExpression<*>>
) : BoundExpression<InvocationExpression>, BoundExecutable<InvocationExpression> {

    private val onceAction = OnceAction()

    /**
     * The result of the function dispatching. Is set (non null) after semantic analysis phase 2
     */
    var dispatchedFunction: BoundFunction? = null
        private set

    override var type: BoundTypeReference? = null
        private set

    lateinit var typeArguments: List<BoundTypeArgument>
        private set

    override val isGuaranteedToThrow: Boolean?
        get() = dispatchedFunction?.isGuaranteedToThrow

    override fun semanticAnalysisPhase1(): Collection<Reporting> =
        onceAction.getResult(OnceAction.SemanticAnalysisPhase1) {
            val reportings = mutableSetOf<Reporting>()
            receiverExpression?.semanticAnalysisPhase1()?.let(reportings::addAll)
            valueArguments.map(BoundExpression<*>::semanticAnalysisPhase1).forEach(reportings::addAll)
            typeArguments = declaration.typeArguments.map(context::resolveType)
            reportings
        }

    override fun semanticAnalysisPhase2(): Collection<Reporting> {
        return onceAction.getResult(OnceAction.SemanticAnalysisPhase2) {
            receiverExpression?.markEvaluationResultUsed()
            valueArguments.forEach { it.markEvaluationResultUsed() }

            val reportings = mutableSetOf<Reporting>()

            receiverExpression?.semanticAnalysisPhase2()?.let(reportings::addAll)
            typeArguments.forEach { reportings.addAll(it.validate(TypeUseSite.Irrelevant)) }
            valueArguments.forEach { reportings.addAll(it.semanticAnalysisPhase2()) }

            val chosenOverload = selectOverload(reportings) ?: return@getResult reportings

            dispatchedFunction = chosenOverload.candidate
            if (chosenOverload.returnType == null) {
                handleCyclicInvocation(
                    context = this,
                    action = { chosenOverload.candidate.semanticAnalysisPhase2() },
                    onCycle = {
                        reportings.add(
                            Reporting.typeDeductionError(
                                "Cannot infer return type of the call to function ${functionNameToken.value} because the inference is cyclic here. Specify the return type explicitly.",
                                declaration.sourceLocation,
                            )
                        )
                    }
                )
            }

            type = chosenOverload.returnType
            if (chosenOverload.candidate.returnsExclusiveValue && expectedReturnType != null) {
                // this is solved by adjusting the return type of the constructor invocation according to the
                // type needed by the larger context
                type = type?.withMutability(expectedReturnType!!.mutability)
            }

            return@getResult reportings
        }
    }

    /**
     * Selects one of the given overloads, including ones that are not a legal match if there are no legal alternatives.
     * Also performs the following checks and reports accordingly:
     * * absolutely no candidate available to evaluate
     * * of the evaluated constructors or functions, none match
     * * if there is only one overload to pick from, forwards any reportings from evaluating that candidate
     */
    private fun selectOverload(reportings: MutableCollection<in Reporting>): OverloadCandidateEvaluation? {
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

        val candidateConstructors = if (receiverExpression != null) null else context.resolveBaseType(functionNameToken.value)?.constructors
        val candidateFunctions = context.getToplevelFunctionOverloadSetsBySimpleName(functionNameToken.value)

        if (candidateConstructors.isNullOrEmpty() && candidateFunctions.isEmpty()) {
            reportings.add(Reporting.noMatchingFunctionOverload(functionNameToken, receiverExpression?.type, valueArguments, false))
            return null
        }

        val allCandidates = (candidateConstructors ?: emptySet()) + candidateFunctions
        val evaluations = allCandidates.filterAndSortByMatchForInvocationTypes(receiverExpression, valueArguments, typeArguments, expectedReturnType)

        if (evaluations.isEmpty()) {
            // TODO: pass on the mismatch reason for all candidates?
            if (candidateConstructors != null) {
                reportings.add(
                    Reporting.unresolvableConstructor(
                        functionNameToken,
                        valueArguments,
                        candidateFunctions.isNotEmpty(),
                    )
                )
            } else {
                reportings.add(
                    Reporting.noMatchingFunctionOverload(
                        functionNameToken,
                        receiverExpression?.type,
                        valueArguments,
                        candidateFunctions.isNotEmpty(),
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

    override fun semanticAnalysisPhase3(): Collection<Reporting> {
        return onceAction.getResult(OnceAction.SemanticAnalysisPhase3) {
            val reportings = mutableSetOf<Reporting>()

            if (receiverExpression != null) {
                reportings += receiverExpression.semanticAnalysisPhase3()
            }

            reportings += valueArguments.flatMap { it.semanticAnalysisPhase3() }

            return@getResult reportings
        }
    }

    override fun findReadsBeyond(boundary: CTContext): Collection<BoundExpression<*>> {
        onceAction.requireActionDone(OnceAction.SemanticAnalysisPhase1)
        onceAction.requireActionDone(OnceAction.SemanticAnalysisPhase2)

        val byReceiver = receiverExpression?.findReadsBeyond(boundary) ?: emptySet()
        val byParameters = valueArguments.flatMap { it.findReadsBeyond(boundary) }

        if (dispatchedFunction != null) {
            if (dispatchedFunction!!.isPure == null) {
                dispatchedFunction!!.semanticAnalysisPhase3()
            }
        }

        val dispatchedFunctionIsPure = dispatchedFunction?.isPure ?: true
        val bySelf = if (dispatchedFunctionIsPure) emptySet() else setOf(this)

        return byReceiver + byParameters + bySelf
    }

    override fun findWritesBeyond(boundary: CTContext): Collection<BoundStatement<*>> {
        onceAction.requireActionDone(OnceAction.SemanticAnalysisPhase1)
        onceAction.requireActionDone(OnceAction.SemanticAnalysisPhase2)

        val byReceiver = receiverExpression?.findWritesBeyond(boundary) ?: emptySet()
        val byParameters = valueArguments.flatMap { it.findWritesBeyond(boundary) }

        if (dispatchedFunction != null) {
            if (dispatchedFunction!!.isReadonly == null) {
                dispatchedFunction!!.semanticAnalysisPhase3()
            }
        }

        val thisExpressionIsReadonly = dispatchedFunction?.isReadonly ?: true
        val bySelf: Collection<BoundStatement<*>> = if (thisExpressionIsReadonly) emptySet() else setOf(this)

        return byReceiver + byParameters + bySelf
    }

    private var expectedReturnType: BoundTypeReference? = null

    override fun setExpectedEvaluationResultType(type: BoundTypeReference) {
        onceAction.requireActionNotDone(OnceAction.SemanticAnalysisPhase2)
        expectedReturnType = type
    }

    override val isEvaluationResultReferenceCounted = true
    override val isCompileTimeConstant: Boolean
        get() {
            val localDispatchedFunction = dispatchedFunction ?: return false
            if (localDispatchedFunction.isPure != true) {
                return false
            }
            val receiverIsConstant = receiverExpression?.isCompileTimeConstant ?: true
            return receiverIsConstant && valueArguments.all { it.isCompileTimeConstant }
        }

    private fun buildBackendIrInvocation(arguments: List<IrTemporaryValueReference>): IrExpression {
        return IrStaticDispatchFunctionInvocationImpl(
            dispatchedFunction!!.toBackendIr(),
            arguments,
            type!!.toBackendIr(),
        )
    }

    override fun toBackendIrExpression(): IrExpression {
        return buildInvocationLikeIr(
            listOfNotNull(receiverExpression) + valueArguments,
            ::buildBackendIrInvocation,
        )
    }

    override fun toBackendIrStatement(): IrExecutable {
        return buildInvocationLikeIr(
            listOfNotNull(receiverExpression) + valueArguments,
            ::buildBackendIrInvocation,
            { listOf(IrDropReferenceStatementImpl(it)) },
        ).code
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
private fun Iterable<BoundOverloadSet>.filterAndSortByMatchForInvocationTypes(
    receiver: BoundExpression<*>?,
    valueArguments: List<BoundExpression<*>>,
    typeArguments: List<BoundTypeArgument>,
    expectedReturnType: BoundTypeReference?,
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
                // types not fully resolve, don't consider
                return@mapNotNull null
            }

            // TODO: source location
            val returnTypeWithVariables = candidateFn.returnType?.withTypeVariables(candidateFn.typeParameters)
            var unification = TypeUnification.fromExplicit(candidateFn.typeParameters, typeArguments, SourceLocation.UNKNOWN, allowZeroTypeArguments = true)
            if (returnTypeWithVariables != null) {
                if (expectedReturnType != null) {
                    unification = unification.doWithIgnoringReportings { obliviousUnification ->
                        expectedReturnType.unify(returnTypeWithVariables, SourceLocation.UNKNOWN, obliviousUnification)
                    }
                }
            }

            @Suppress("UNCHECKED_CAST") // the check is right above
            val rightSideTypes = (candidateFn.parameterTypes as List<BoundTypeReference>)
                .map { it.withTypeVariables(candidateFn.typeParameters) }
            check(rightSideTypes.size == argumentsIncludingReceiver.size)

            val indicesOfErroneousParameters = ArrayList<Int>(argumentsIncludingReceiver.size)
            unification = argumentsIncludingReceiver
                .zip(rightSideTypes)
                .foldIndexed(unification) { parameterIndex, carryUnification, (argument, parameterType) ->
                    val unificationAfterParameter = parameterType.unify(argument.type!!, argument.declaration.sourceLocation, carryUnification)
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

private data class OverloadCandidateEvaluation(
    val candidate: BoundFunction,
    val unification: TypeUnification,
    val returnType: BoundTypeReference?,
    val indicesOfErroneousParameters: Collection<Int>,
) {
    init {
        unification.reportings.asSequence()
            .filterIsInstance<ValueNotAssignableReporting>()
            .onEach {
                it.simplifyMessageWhenCausedSolelyByMutability = true
            }
    }
    val hasErrors = unification.reportings.any { it.level >= Reporting.Level.ERROR }
}

private fun Collection<OverloadCandidateEvaluation>.indicesOfDisjointlyTypedParameters(): Sequence<Int> {
    require(isNotEmpty())
    return (0 until first().candidate.parameters.parameters.size).asSequence()
        .filter { parameterIndex ->
            val parameterTypesAtIndex = this.map { it.candidate.parameters.parameters[parameterIndex] }
            parameterTypesAtIndex.nonDisjointPairs().none()
        }
}

private class IrStaticDispatchFunctionInvocationImpl(
    override val function: IrFunction,
    override val arguments: List<IrTemporaryValueReference>,
    override val evaluatesTo: IrType,
) : IrStaticDispatchFunctionInvocationExpression

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
            prepareArgumentsCode.add(IrCreateReferenceStatementImpl(temporary))
        }
        cleanUpArgumentsCode.add(IrDropReferenceStatementImpl(temporary))
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