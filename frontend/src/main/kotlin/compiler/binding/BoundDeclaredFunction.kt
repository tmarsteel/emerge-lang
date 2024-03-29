package compiler.binding

import compiler.*
import compiler.ast.FunctionDeclaration
import compiler.ast.type.FunctionModifier
import compiler.ast.type.TypeMutability
import compiler.ast.type.TypeVariance
import compiler.binding.context.CTContext
import compiler.binding.type.BoundTypeParameter
import compiler.binding.type.BoundTypeReference
import compiler.binding.type.RootResolvedTypeReference
import compiler.binding.type.TypeUseSite
import compiler.reportings.Reporting
import io.github.tmarsteel.emerge.backend.api.ir.IrFunction

/**
 * Describes the presence/avaiability of a (class member) function in a context.
 * Refers to the original declaration and holds a reference to the appropriate context
 * so that [BaseType]s for receiver, parameters and return type can be resolved.
 */
class BoundDeclaredFunction(
    override val context: CTContext,
    val declaration: FunctionDeclaration,
    override val typeParameters: List<BoundTypeParameter>,
    override val parameters: BoundParameterList,
    val code: Body?
) : BoundFunction() {
    override val declaredAt = declaration.declaredAt
    override val name: String = declaration.name.value

    override val receiverType: BoundTypeReference?
        get() = parameters.declaredReceiver?.type

    override val declaresReceiver = parameters.declaredReceiver != null

    /**
     * Implied modifiers. Operator functions often have an implied [FunctionModifier.Readonly]
     * TODO: yeet, these modifiers can go into the stdlib sources
     */
    val impliedModifiers: Set<FunctionModifier> = run {
        // only operator functions have implied modifiers
        if (FunctionModifier.Operator !in declaration.modifiers) {
            emptySet<FunctionModifier>()
        }

        when {
            name.startsWith("opUnary")                                -> setOf(FunctionModifier.Readonly)
            name.startsWith("op") && !name.endsWith("Assign") -> setOf(FunctionModifier.Readonly)
            name == "rangeTo" || name == "contains"                           -> setOf(FunctionModifier.Readonly)
            else                                                              -> emptySet()
        }
    }

    override val modifiers = (declaration.modifiers + impliedModifiers).toSet()

    override var returnType: BoundTypeReference? = null
        private set

    override val isDeclaredPure: Boolean = modifiers.none { it == FunctionModifier.Readonly || it == FunctionModifier.Modifying } ||
            modifiers.any { it == FunctionModifier.Pure }

    /**
     * Whether this functions code is behaves in a pure way. Is null if that has not yet been determined (see semantic
     * analysis) or if the function has no body.
     */
    var isEffectivelyPure: Boolean? = null
        private set

    override val isDeclaredReadonly: Boolean = modifiers.none { it == FunctionModifier.Modifying } ||
            modifiers.any { it == FunctionModifier.Readonly || it == FunctionModifier.Pure }

    /**
     * Whether this functions code behaves in a readonly way. Is null if that has not yet been determined (see semantic
     * analysis) or if the function has no body.
     */
    var isEffectivelyReadonly: Boolean? = null
        private set

    override val isPure: Boolean?
        get() = if (isDeclaredPure) true else isEffectivelyPure

    override val isReadonly: Boolean?
        get() = if (isDeclaredReadonly || isDeclaredPure) true else isEffectivelyReadonly

    override val isGuaranteedToThrow: Boolean?
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

            reportings.addAll(parameters.semanticAnalysisPhase1())

            // modifiers
            if (modifiers.any { it.impliesNoBody }) {
                if (code != null) {
                    reportings.add(Reporting.illegalFunctionBody(declaration))
                }
            } else if (code == null) {
                reportings.add(Reporting.missingFunctionBody(declaration))
            }

            if (FunctionModifier.Pure in modifiers) {
                reportings.add(Reporting.inefficientModifiers(
                    "The pure modifier is superfluous, functions are pure by default.",
                    declaredAt,
                ))

                if (FunctionModifier.Readonly in modifiers) {
                    reportings.add(
                        Reporting.inefficientModifiers(
                            "The modifier readonly is superfluous: the function is also pure and pure implies readonly.",
                            declaredAt,
                        )
                    )
                }
                if (FunctionModifier.Modifying in modifiers) {
                    reportings.add(
                        Reporting.conflictingModifiers(
                            "A function cannot be declared both mutable and pure",
                            declaredAt,
                        )
                    )
                }
            }

            if (FunctionModifier.Modifying in modifiers && FunctionModifier.Readonly in modifiers) {
                reportings.add(Reporting.conflictingModifiers(
                    "A function cannot be declared both mutable and readonly",
                    declaredAt,
                ))
            }

            typeParameters.map(BoundTypeParameter::semanticAnalysisPhase1).forEach(reportings::addAll)
            reportings.addAll(parameters.semanticAnalysisPhase1(false))

            if (declaration.returnType != null) {
                returnType = context.resolveType(declaration.returnType)
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
                it.validate(TypeUseSite.InUsage(it.sourceLocation)).let(reportings::addAll)
            }
            parameterTypes.forEach {
                it?.validate(TypeUseSite.InUsage(it.sourceLocation))?.let(reportings::addAll)
            }
            returnType?.let {
                val returnTypeUseSite = TypeUseSite.OutUsage(
                    declaration.returnType?.declaringNameToken?.sourceLocation
                        ?: this.declaredAt
                )
                reportings.addAll(it.validate(returnTypeUseSite))
            }
            typeParameters.forEach {
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

            typeParameters.map(BoundTypeParameter::semanticAnalysisPhase3).forEach(reportings::addAll)

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

                if (isDeclaredPure) {
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
                } else if (isDeclaredReadonly && !isEffectivelyReadonly!!) {
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

    private val backendIr by lazy { IrFunctionImpl(this) }
    override fun toBackendIr(): IrFunction = backendIr
}