package compiler.binding.type

import compiler.ast.type.TypeMutability
import compiler.ast.type.TypeParameter
import compiler.ast.type.TypeReference
import compiler.ast.type.TypeVariance
import compiler.binding.SemanticallyAnalyzable
import compiler.binding.context.CTContext
import compiler.reportings.Reporting

data class BoundTypeParameter(
    val astNode: TypeParameter,
    val context: CTContext,
) : SemanticallyAnalyzable {
    val name: String = astNode.name.value
    val variance: TypeVariance = astNode.variance

    /**
     * Available after [semanticAnalysisPhase1].
     */
    lateinit var bound: BoundTypeReference
        private set

    override fun semanticAnalysisPhase1(): Collection<Reporting> {
        bound = astNode.bound?.let(context::resolveType) ?: TYPE_PARAMETER_DEFAULT_BOUND
        return emptySet()
    }

    override fun semanticAnalysisPhase2(): Collection<Reporting> {
        return bound.validate(TypeUseSite.Irrelevant)
    }

    override fun toString(): String {
        if (!this::bound.isInitialized) {
            return "[not validated; $astNode]"
        }
        var str = ""
        if (variance != TypeVariance.UNSPECIFIED) {
            str += variance.name.lowercase()
            str += " "
        }

        str += name

        str += " : "
        str += bound.toString()

        return str
    }

    companion object {
        val TYPE_PARAMETER_DEFAULT_BOUND: BoundTypeReference = BuiltinAny.baseReference
            .withMutability(TypeMutability.READONLY)
            .withCombinedNullability(TypeReference.Nullability.NULLABLE)
    }
}