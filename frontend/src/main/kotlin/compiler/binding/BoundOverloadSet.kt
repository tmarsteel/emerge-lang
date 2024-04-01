package compiler.binding

import compiler.binding.misc_ir.IrOverloadGroupImpl
import compiler.binding.type.nonDisjointPairs
import compiler.pivot
import compiler.reportings.OverloadSetHasNoDisjointParameterReporting
import compiler.reportings.Reporting
import io.github.tmarsteel.emerge.backend.api.DotName
import io.github.tmarsteel.emerge.backend.api.ir.IrFunction
import io.github.tmarsteel.emerge.backend.api.ir.IrOverloadGroup

class BoundOverloadSet(
    val fqn: DotName,
    val parameterCount: Int,
    val overloads: Collection<BoundFunction>
) : SemanticallyAnalyzable {
    init {
        require(overloads.isNotEmpty())
        assert(overloads.all { it.fullyQualifiedName == fqn && it.parameters.parameters.size == parameterCount })
    }

    override fun semanticAnalysisPhase1(): Collection<Reporting> {
        // the individual overload implementations are validated through the regular/obvious tree structure (SourceFile)
        return emptySet()
    }

    override fun semanticAnalysisPhase2(): Collection<Reporting> {
        // the individual overload implementations are validated through the regular/obvious tree structure (SourceFile)
        return emptySet()
    }

    override fun semanticAnalysisPhase3(): Collection<Reporting> {
        // // the individual overload implementations are validated through the regular/obvious tree structure (SourceFile)

        if (overloads.size == 1) {
            // not actually overloaded
            return emptySet()
        }

        val hasAtLeastOneDisjointParameter = this.overloads
            .map { it.parameters.parameters.asSequence() }
            .asSequence()
            .pivot()
            .filter { parametersAtIndex ->
                assert(parametersAtIndex.all { it != null })
                @Suppress("UNCHECKED_CAST")
                parametersAtIndex as List<BoundParameter>
                val parameterIsDisjoint = parametersAtIndex
                    .nonDisjointPairs()
                    .none()
                return@filter parameterIsDisjoint
            }
            .any()

        if (hasAtLeastOneDisjointParameter) {
            return emptySet()
        }

        return setOf(OverloadSetHasNoDisjointParameterReporting(this))
    }

    fun toBackendIr(): IrOverloadGroup<IrFunction> {
        return IrOverloadGroupImpl(fqn, parameterCount, overloads)
    }

    companion object {
        fun fromSingle(fn: BoundFunction): BoundOverloadSet {
            return BoundOverloadSet(
                fn.fullyQualifiedName,
                fn.parameters.parameters.size,
                setOf(fn),
            )
        }
    }
}