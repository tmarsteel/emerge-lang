package compiler.binding

import compiler.*
import compiler.ast.Executable
import compiler.ast.FunctionDeclaration
import compiler.ast.type.TypeMutability
import compiler.ast.type.TypeVariance
import compiler.binding.context.CTContext
import compiler.binding.expression.BoundExpression
import compiler.binding.type.BoundTypeParameter
import compiler.binding.type.BoundTypeReference
import compiler.binding.type.RootResolvedTypeReference
import compiler.binding.type.TypeUseSite
import compiler.lexer.SourceLocation
import compiler.reportings.Reporting
import compiler.reportings.ReturnTypeMismatchReporting
import io.github.tmarsteel.emerge.backend.api.ir.IrFunction

/**
 * Describes the presence/avaiability of a (class member) function in a context.
 * Refers to the original declaration and holds a reference to the appropriate context
 * so that [BaseType]s for receiver, parameters and return type can be resolved.
 */
abstract class BoundDeclaredFunction(
    final override val context: CTContext,
    val declaration: FunctionDeclaration,
    final override val attributes: BoundFunctionAttributeList,
    final override val declaredTypeParameters: List<BoundTypeParameter>,
    final override val parameters: BoundParameterList,
    /** TODO: rename to body */
    val code: Body?,
) : BoundFunction() {
    final override val declaredAt = declaration.declaredAt
    final override val name: String = declaration.name.value

    final override val allTypeParameters get()= context.allTypeParameters.toList()

    final override val receiverType: BoundTypeReference?
        get() = parameters.declaredReceiver?.typeAtDeclarationTime

    final override val declaresReceiver = parameters.declaredReceiver != null

    final override var returnType: BoundTypeReference? = null
        private set

    /**
     * Whether this functions code is pure. Is null if that has not yet been determined (see semantic
     * analysis) or if the function has no body.
     */
    var isEffectivelyPure: Boolean? = null
        private set

    /**
     * Whether this functions code behaves in a readonly way. Is null if that has not yet been determined (see semantic
     * analysis) or if the function has no body.
     */
    var isEffectivelyReadonly: Boolean? = null
        private set

    final override val isPure: Boolean?
        get() = when {
            attributes.isDeclaredPure -> true
            code == null -> false
            else -> isEffectivelyPure
        }

    final override val isReadonly: Boolean?
        get() = when {
            attributes.isDeclaredPure || attributes.isDeclaredReadonly -> true
            code == null -> false
            else -> isEffectivelyReadonly
        }

    final override val isGuaranteedToThrow: Boolean?
        get() = handleCyclicInvocation(
                    context = this,
                    action = { code?.isGuaranteedToThrow },
                    onCycle = { false },
                )

    private val onceAction = OnceAction()

    override fun semanticAnalysisPhase1(): Collection<Reporting> {
        // TODO: check FFI name of external modifier

        return onceAction.getResult(OnceAction.SemanticAnalysisPhase1) {
            val reportings = mutableSetOf<Reporting>()

            reportings.addAll(attributes.semanticAnalysisPhase1())

            declaredTypeParameters.map(BoundTypeParameter::semanticAnalysisPhase1).forEach(reportings::addAll)
            reportings.addAll(parameters.semanticAnalysisPhase1())

            if (declaration.parsedReturnType != null) {
                returnType = context.resolveType(declaration.parsedReturnType)
                if (code !is Body.SingleExpression) {
                    returnType = returnType?.defaultMutabilityTo(TypeMutability.IMMUTABLE)
                }

                returnType?.let {
                    code?.setExpectedReturnType(it)
                }
            }

            this.code?.semanticAnalysisPhase1()?.let(reportings::addAll)

            return@getResult reportings
        }
    }

    override fun semanticAnalysisPhase2(): Collection<Reporting> {
        onceAction.requireActionDone(OnceAction.SemanticAnalysisPhase1)
        return onceAction.getResult(OnceAction.SemanticAnalysisPhase2) {
            val reportings = mutableSetOf<Reporting>()

            handleCyclicInvocation(
                context = this,
                action = { this.code?.semanticAnalysisPhase2()?.let(reportings::addAll) },
                onCycle = {
                    Reporting.typeDeductionError(
                        "Cannot infer the return type of function $name because the type inference is cyclic here. Specify the type of one element explicitly.",
                        declaredAt
                    )
                }
            )

            if (returnType == null) {
                if (this.code is Body.SingleExpression) {
                    this.returnType = this.code.expression.type
                } else {
                    this.returnType = context.swCtx.unitBaseType.baseReference
                        .withMutability(TypeMutability.READONLY)
                }
            }

            receiverType?.let {
                it.validate(TypeUseSite.InUsage(it.sourceLocation, this)).let(reportings::addAll)
            }
            parameterTypes.forEach {
                it?.validate(TypeUseSite.InUsage(it.sourceLocation, this))?.let(reportings::addAll)
            }
            returnType?.let {
                val returnTypeUseSite = TypeUseSite.OutUsage(
                    declaration.parsedReturnType?.declaringNameToken?.sourceLocation
                        ?: this.declaredAt,
                    this,
                )
                reportings.addAll(it.validate(returnTypeUseSite))
            }
            declaredTypeParameters.forEach {
                reportings.addAll(it.semanticAnalysisPhase2())
                if (it.variance != TypeVariance.UNSPECIFIED) {
                    reportings.add(Reporting.varianceOnFunctionTypeParameter(it))
                }
            }
            code?.semanticAnalysisPhase2()?.let(reportings::addAll)

            return@getResult reportings
        }
    }

    override fun semanticAnalysisPhase3(): Collection<Reporting> {
        onceAction.requireActionDone(OnceAction.SemanticAnalysisPhase1)
        onceAction.requireActionDone(OnceAction.SemanticAnalysisPhase2)
        return onceAction.getResult(OnceAction.SemanticAnalysisPhase3) {
            val reportings = mutableSetOf<Reporting>()

            declaredTypeParameters.map(BoundTypeParameter::semanticAnalysisPhase3).forEach(reportings::addAll)

            if (code != null) {
                reportings += code.semanticAnalysisPhase3()

                // readonly and purity checks
                val statementsReadingBeyondFunctionContext = handleCyclicInvocation(
                    context = this,
                    action = { code.findReadsBeyond(context) },
                    onCycle = ::emptySet,
                )
                val statementsWritingBeyondFunctionContext = handleCyclicInvocation(
                    context = this,
                    action = { code.findWritesBeyond(context) },
                    onCycle = ::emptySet,
                )

                isEffectivelyReadonly = statementsWritingBeyondFunctionContext.isEmpty()
                isEffectivelyPure = isEffectivelyReadonly!! && statementsReadingBeyondFunctionContext.isEmpty()

                if (attributes.isDeclaredPure) {
                    if (!isEffectivelyPure!!) {
                        reportings.addAll(
                            Reporting.purityViolations(
                                statementsReadingBeyondFunctionContext,
                                statementsWritingBeyondFunctionContext,
                                this
                            )
                        )
                    }
                    // else: effectively pure means effectively readonly
                } else if (attributes.isDeclaredReadonly && !isEffectivelyReadonly!!) {
                    reportings.addAll(Reporting.readonlyViolations(statementsWritingBeyondFunctionContext, this))
                }

                // assure all paths return or throw
                val isGuaranteedToTerminate = code.isGuaranteedToReturn nullableOr code.isGuaranteedToThrow

                if (!isGuaranteedToTerminate) {
                    val localReturnType = returnType
                    // if the function is declared to return Unit a return of Unit is implied and should be inserted by backends
                    // if this is a single-expression function (fun a() = 3), return is implied
                    if (localReturnType == null || this.code !is Body.SingleExpression) {
                        val isImplicitUnitReturn = localReturnType is RootResolvedTypeReference && localReturnType.baseType == context.swCtx.unitBaseType
                        if (!isImplicitUnitReturn) {
                            reportings.add(Reporting.uncertainTermination(this))
                        }
                    }
                }
            }

            return@getResult reportings
        }
    }

    override fun validateAccessFrom(location: SourceLocation): Collection<Reporting> {
        return attributes.visibility.validateAccessFrom(location, this)
    }

    private val backendIr by lazy { IrFunctionImpl(this) }
    override fun toBackendIr(): IrFunction = backendIr
    sealed interface Body : BoundExecutable<Executable> {
        class SingleExpression(val expression: BoundExpression<*>) : Body, BoundExecutable<Executable> by expression {
            private var expectedReturnType: BoundTypeReference? = null

            override fun setExpectedReturnType(type: BoundTypeReference) {
                expectedReturnType = type
                expression.setExpectedEvaluationResultType(type)
            }

            override fun semanticAnalysisPhase3(): Collection<Reporting> {
                val reportings = mutableListOf<Reporting>()
                reportings.addAll(expression.semanticAnalysisPhase3())
                expectedReturnType?.let { declaredReturnType ->
                    expression.type?.let { actualReturnType ->
                        actualReturnType.evaluateAssignabilityTo(declaredReturnType, expression.declaration.sourceLocation)
                            ?.let(::ReturnTypeMismatchReporting)
                            ?.let(reportings::add)
                    }
                }

                return reportings
            }
        }
        class Full(val code: BoundCodeChunk) : Body, BoundExecutable<Executable> by code
    }
}