package compiler.binding.classdef

import compiler.ast.ParameterList
import compiler.ast.VariableDeclaration
import compiler.ast.type.FunctionModifier
import compiler.ast.type.TypeArgument
import compiler.ast.type.TypeMutability
import compiler.ast.type.TypeReference
import compiler.ast.type.TypeVariance
import compiler.binding.BoundFunction
import compiler.binding.IrFunctionImpl
import compiler.binding.context.CTContext
import compiler.binding.context.ExecutionScopedCTContext
import compiler.binding.context.MutableCTContext
import compiler.binding.context.MutableExecutionScopedCTContext
import compiler.binding.type.BoundTypeParameter
import compiler.reportings.Reporting
import io.github.tmarsteel.emerge.backend.api.ir.IrFunction

class ClassConstructor(
    val classDef: BoundClassDefinition,
) : BoundFunction() {
    override val context: CTContext = MutableCTContext(classDef.context)
    val constructorCodeContext: ExecutionScopedCTContext = MutableExecutionScopedCTContext.functionRootIn(context)
    override val declaredAt = classDef.declaration.declaredAt
    override val receiverType = null
    override val declaresReceiver = false
    override val name = classDef.simpleName
    override val modifiers = setOf(FunctionModifier.Pure)
    override val isPure = true
    override val isDeclaredPure = true
    override val isReadonly = true
    override val isDeclaredReadonly = true
    override val returnsExclusiveValue = true
    override val parameters = run {
        val astParameterList = ParameterList(classDef.members.map { member ->
            VariableDeclaration(
                member.declaration.declaredAt,
                null,
                member.declaration.name,
                member.declaration.type,
                null,
            )
        })
        astParameterList.bindTo(constructorCodeContext).also {
            it.semanticAnalysisPhase1()
        }
    }

    override val typeParameters: List<BoundTypeParameter>
        get() = classDef.typeParameters

    override val returnType = context.resolveType(
        TypeReference(
            classDef.simpleName,
            TypeReference.Nullability.NOT_NULLABLE,
            TypeMutability.IMMUTABLE,
            classDef.declaration.name,
            classDef.typeParameters.map {
                TypeArgument(
                    TypeVariance.UNSPECIFIED,
                    TypeReference(it.astNode.name),
                )
            },
        ),
    )
    override val isGuaranteedToThrow = false
    override fun semanticAnalysisPhase1(): Collection<Reporting> = emptySet()
    override fun semanticAnalysisPhase2(): Collection<Reporting> = emptySet()
    override fun semanticAnalysisPhase3(): Collection<Reporting> = emptySet()

    private val backendIr by lazy { IrFunctionImpl(this) }
    override fun toBackendIr(): IrFunction = backendIr
}