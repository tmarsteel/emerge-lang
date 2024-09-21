package compiler.binding.basetype

import compiler.ast.ParameterList
import compiler.ast.VariableDeclaration
import compiler.ast.type.TypeReference
import compiler.binding.BoundMemberFunction
import compiler.binding.BoundParameterList
import compiler.binding.context.ExecutionScopedCTContext
import compiler.binding.context.MutableExecutionScopedCTContext
import compiler.binding.type.BoundTypeReference
import compiler.lexer.IdentifierToken
import compiler.reportings.Reporting
import compiler.util.checkNoDiagnostics
import io.github.tmarsteel.emerge.backend.api.ir.IrFullyInheritedMemberFunction
import io.github.tmarsteel.emerge.backend.api.ir.IrMemberFunction
import io.github.tmarsteel.emerge.common.CanonicalElementName

class InheritedBoundMemberFunction(
    val supertypeMemberFn: BoundMemberFunction,
    val subtype: BoundBaseType,
) : BoundMemberFunction by supertypeMemberFn {
    init {
        check(supertypeMemberFn.declaresReceiver) {
            "Inheriting a member function without receiver - that's nonsensical!"
        }
    }

    private val rawSuperFnContext = supertypeMemberFn.context as? ExecutionScopedCTContext
        ?: MutableExecutionScopedCTContext.functionRootIn(supertypeMemberFn.context)
    override val context = subtype.context
    private val functionContext = object : ExecutionScopedCTContext by rawSuperFnContext {
        override fun resolveType(ref: TypeReference, fromOwnFileOnly: Boolean): BoundTypeReference {
            if (ref.simpleName == subtype.simpleName) {
                return subtype.context.resolveType(ref, fromOwnFileOnly)
            }

            return rawSuperFnContext.resolveType(ref, fromOwnFileOnly)
        }
    }

    override val canonicalName = CanonicalElementName.Function(
        subtype.canonicalName,
        supertypeMemberFn.name,
    )

    // this is intentional - as long as not overridden, this info is truthful & accurate
    override val declaredOnType get()= supertypeMemberFn.declaredOnType

    override val receiverType: BoundTypeReference get() {
        check(subtype.typeParameters.isNullOrEmpty()) {
            "Not supported yet"
        }
        return subtype.baseReference
    }

    private val narrowedReceiverParameter: VariableDeclaration = run {
        val inheritedReceiverParameter = supertypeMemberFn.parameters.declaredReceiver!!
        // it is important that this location comes from the subtype
        // this is necessary so the access checks pass on module-private or less visible subtypes
        val sourceLocation = subtype.declaration.declaredAt.deriveGenerated()
        VariableDeclaration(
            sourceLocation,
            null,
            null,
            inheritedReceiverParameter.declaration.ownership,
            inheritedReceiverParameter.declaration.name,
            TypeReference(subtype.simpleName, declaringNameToken = IdentifierToken(subtype.simpleName, sourceLocation), mutability = inheritedReceiverParameter.typeAtDeclarationTime?.mutability),
            null,
        )
    }

    override val parameters: BoundParameterList = run {
        ParameterList(
            listOf(narrowedReceiverParameter) + (supertypeMemberFn.parameters.parameters.drop(1).map { it.declaration }),
        ).bindTo(functionContext)
    }

    override val parameterTypes get() = super.parameterTypes

    // semantic analysis not needed here
    override fun semanticAnalysisPhase1(): Collection<Reporting> {
        checkNoDiagnostics(parameters.semanticAnalysisPhase1())
        checkNoDiagnostics(parameters.parameters.flatMap { it.semanticAnalysisPhase1() })
        return emptySet()
    }

    override fun semanticAnalysisPhase2(): Collection<Reporting> {
        checkNoDiagnostics(parameters.parameters.flatMap { it.semanticAnalysisPhase2() })
        return emptySet()
    }

    override fun semanticAnalysisPhase3(): Collection<Reporting> {
        checkNoDiagnostics(parameters.parameters.flatMap { it.semanticAnalysisPhase3() })
        return emptySet()
    }

    private val backendIr by lazy {
        IrFullyInheritedMemberFunctionImpl(supertypeMemberFn.toBackendIr(), canonicalName)
    }
    override fun toBackendIr(): IrMemberFunction = backendIr

    override fun toString(): String {
        return "$canonicalName(${parameters.parameters.joinToString(separator = ", ", transform = { it.typeAtDeclarationTime.toString() })}) -> $returnType"
    }
}

private class IrFullyInheritedMemberFunctionImpl(
    override val superFunction: IrMemberFunction,
    override val canonicalName: CanonicalElementName.Function,
) : IrFullyInheritedMemberFunction, IrMemberFunction by superFunction