package compiler.binding.basetype

import compiler.ast.BaseTypeMemberFunctionDeclaration
import compiler.ast.FunctionDeclaration
import compiler.binding.BoundDeclaredFunction
import compiler.binding.BoundFunctionAttributeList
import compiler.binding.BoundMemberFunction
import compiler.binding.BoundParameterList
import compiler.binding.SeanHelper
import compiler.binding.context.MutableExecutionScopedCTContext
import compiler.binding.type.BoundTypeParameter
import compiler.lexer.Span
import compiler.reportings.IncompatibleReturnTypeOnOverrideReporting
import compiler.reportings.Reporting
import io.github.tmarsteel.emerge.backend.api.ir.IrCodeChunk
import io.github.tmarsteel.emerge.backend.api.ir.IrMemberFunction
import io.github.tmarsteel.emerge.common.CanonicalElementName
import java.util.Collections
import java.util.IdentityHashMap

class BoundDeclaredBaseTypeMemberFunction(
    functionRootContext: MutableExecutionScopedCTContext,
    declaration: FunctionDeclaration,
    attributes: BoundFunctionAttributeList,
    declaredTypeParameters: List<BoundTypeParameter>,
    parameters: BoundParameterList,
    body: Body?,
    getTypeDef: () -> BoundBaseType,
) : BoundBaseTypeEntry<BaseTypeMemberFunctionDeclaration>, BoundMemberFunction, BoundDeclaredFunction(
    functionRootContext,
    declaration,
    attributes,
    declaredTypeParameters,
    parameters,
    body,
) {
    private val seanHelper = SeanHelper()

    override val declaredOnType by lazy(getTypeDef)
    override val canonicalName by lazy {
        CanonicalElementName.Function(
            declaredOnType.canonicalName,
            name,
        )
    }
    override val isVirtual get() = declaresReceiver
    override val isAbstract = !attributes.impliesNoBody && body == null

    override val visibility by lazy {
        attributes.visibility.coerceAtMost(declaredOnType.visibility)
    }

    override var overrides: Set<InheritedBoundMemberFunction>? = null
        get() {
            seanHelper.requirePhase1Done()
            return field
        }
        private set

    override fun semanticAnalysisPhase1(): Collection<Reporting> {
        return seanHelper.phase1 {
            val reportings = mutableListOf<Reporting>()
            reportings.addAll(super.semanticAnalysisPhase1())
            determineOverride(reportings)
            reportings
        }
    }

    private fun determineOverride(reportings: MutableCollection<Reporting>) {
        val isDeclaredOverride = attributes.firstOverrideAttribute != null
        if (isDeclaredOverride && !declaresReceiver) {
            reportings.add(Reporting.staticFunctionDeclaredOverride(this))
            return
        }

        val superFns = findOverriddenFunction()
        if (isDeclaredOverride) {
            if (superFns.isEmpty()) {
                reportings.add(Reporting.functionDoesNotOverride(this))
            }
        } else {
            if (superFns.isNotEmpty()) {
                val supertype = superFns.first().declaredOnType
                reportings.add(Reporting.undeclaredOverride(this, supertype))
            }
        }

        overrides = superFns

        returnType?.let { overriddenReturnType ->
            for (superFn in superFns) {
                superFn.returnType?.let { superReturnType ->
                    overriddenReturnType.evaluateAssignabilityTo(
                        superReturnType,
                        overriddenReturnType.span ?: Span.UNKNOWN
                    )
                        ?.let { typeError ->
                            reportings.add(IncompatibleReturnTypeOnOverrideReporting(declaration, superFn, typeError))
                        }
                }
            }
        }
    }

    private fun findOverriddenFunction(): Set<InheritedBoundMemberFunction> {
        val selfParameterTypes = parameterTypes
            .drop(1) // ignore receiver
            .asElementNotNullable()
            ?: return emptySet()

        declaredOnType.superTypes.semanticAnalysisPhase1()
        return declaredOnType.superTypes.inheritedMemberFunctions
            .asSequence()
            .filter { it.canonicalName.simpleName == this.name }
            .filter { it.parameters.parameters.size == this.parameters.parameters.size }
            .filter { potentialSuperFn ->
                val potentialSuperFnParamTypes = potentialSuperFn.parameterTypes
                    .drop(1) // ignore receiver
                    .asElementNotNullable()
                    ?: return@filter false

                selfParameterTypes.asSequence().zip(potentialSuperFnParamTypes.asSequence())
                    .all { (selfParamType, superParamType) -> superParamType == selfParamType }
            }
            .toCollection(Collections.newSetFromMap(IdentityHashMap()))
    }

    override fun semanticAnalysisPhase2(): Collection<Reporting> {
        return seanHelper.phase2 {
            super.semanticAnalysisPhase2()
        }
    }

    override fun semanticAnalysisPhase3(): Collection<Reporting> {
        return seanHelper.phase3 {
            val reportings = super.semanticAnalysisPhase3().toMutableList()

            if (attributes.externalAttribute != null) {
                reportings.add(Reporting.externalMemberFunction(this))
            }

            if (body != null) {
                if (attributes.impliesNoBody) {
                    reportings.add(Reporting.illegalFunctionBody(declaration))
                }
            } else {
                if (!attributes.impliesNoBody && !declaredOnType.kind.memberFunctionsAbstractByDefault) {
                    reportings.add(Reporting.missingFunctionBody(declaration))
                }
            }

            overrides?.forEach { superFn ->
                if (!superFn.purity.contains(this.purity)) {
                    reportings.add(Reporting.overrideAddsSideEffects(this, superFn))
                }
                if (superFn.attributes.isDeclaredNothrow && !this.attributes.isDeclaredNothrow) {
                    reportings.add(Reporting.overrideDropsNothrow(this, superFn))
                }
                if (superFn.visibility.isPossiblyBroaderThan(visibility) && declaredOnType.visibility.isPossiblyBroaderThan(visibility)) {
                    reportings.add(Reporting.overrideRestrictsVisibility(this, superFn))
                }

                superFn.parameters.parameters.zip(this.parameters.parameters)
                    .filterNot { (superFnParam, overrideFnParam) -> overrideFnParam.ownershipAtDeclarationTime.canOverride(superFnParam.ownershipAtDeclarationTime) }
                    .forEach { (superFnParam, overrideFnParam) ->
                        reportings.add(Reporting.overridingParameterExtendsOwnership(overrideFnParam, superFnParam))
                    }
            }

            return@phase3 reportings
        }
    }

    override fun validateAccessFrom(location: Span): Collection<Reporting> {
        return visibility.validateAccessFrom(location, this)
    }

    override fun toStringForErrorMessage() = "member function $name"

    override fun toString(): String {
        var str = canonicalName.toString()
        if (declaredTypeParameters.isNotEmpty()) {
            str += declaredTypeParameters.joinToString(
                prefix = "<",
                separator = ", ",
                transform = { "${it.name} : ${it.bound.toString()}" },
                postfix = ">"
            )
        }
        str += parameters.parameters.joinToString(
            prefix = "(",
            separator = ", ",
            transform= { it.typeAtDeclarationTime.toString() },
            postfix = ") -> ",
        )
        str += returnType

        return str
    }

    private val backendIr by lazy { IrMemberFunctionImpl(this) }
    override fun toBackendIr(): IrMemberFunction = backendIr
}

private fun <T : Any> List<T?>.asElementNotNullable(): List<T>? {
    if (any { it == null }) {
        return null
    }

    @Suppress("UNCHECKED_CAST")
    return this as List<T>
}

private class IrMemberFunctionImpl(
    private val boundFn: BoundDeclaredBaseTypeMemberFunction,
) : IrMemberFunction {
    override val canonicalName = boundFn.canonicalName
    override val parameters = boundFn.parameters.parameters.map { it.backendIrDeclaration }
    override val declaresReceiver = boundFn.declaresReceiver
    override val returnType by lazy { boundFn.returnType!!.toBackendIr() }
    override val isExternalC = boundFn.attributes.externalAttribute?.ffiName?.value == "C"
    override val isNothrow = boundFn.attributes.isDeclaredNothrow
    override val body: IrCodeChunk? by lazy { boundFn.getFullBodyBackendIr() }
    override val overrides: Set<IrMemberFunction> by lazy { (boundFn.overrides ?: emptyList()).map { it.toBackendIr() }.toSet() }
    override val supportsDynamicDispatch = boundFn.isVirtual
    override val ownerBaseType by lazy { boundFn.declaredOnType.toBackendIr() }
    override val declaredAt = boundFn.declaredAt
}