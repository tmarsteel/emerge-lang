package compiler.binding.type

import compiler.ast.type.TypeArgument
import compiler.ast.type.TypeMutability
import compiler.ast.type.TypeVariance
import compiler.binding.context.CTContext
import compiler.reportings.Reporting
import io.github.tmarsteel.emerge.backend.api.ir.IrParameterizedType
import io.github.tmarsteel.emerge.backend.api.ir.IrType
import io.github.tmarsteel.emerge.backend.api.ir.IrTypeVariance

class BoundTypeArgument(
    val context: CTContext,
    val astNode: TypeArgument,
    private val type: BoundTypeReference,
) {
    val variance: TypeVariance = astNode.variance

    private val boundType: BoundTypeReference by lazy {
        if (type is NullableTypeReference) {
            NullableTypeReference(BoundTypeFromArgument(this, type.nested))
        } else {
            BoundTypeFromArgument(this, type)
        }
    }
    fun asBoundTypeReference(): BoundTypeReference = boundType

    fun defaultMutabilityTo(mutability: TypeMutability?): BoundTypeArgument = rewrap(
        type.defaultMutabilityTo(mutability)
    )

    fun withTypeVariables(variables: List<BoundTypeParameter>): BoundTypeArgument = rewrap(
        type.withTypeVariables(variables),
    )

    fun instantiateFreeVariables(context: TypeUnification): BoundTypeArgument {
        return rewrap(type.instantiateFreeVariables(context))
    }

    fun instantiateAllParameters(context: TypeUnification): BoundTypeArgument {
        return rewrap(type.instantiateAllParameters(context))
    }

    fun validate(forUsage: TypeUseSite): Collection<Reporting> {
        return setOfNotNull(forUsage.validateForTypeVariance(variance)) + type.validate(forUsage.deriveIrrelevant())
    }

    fun toBackendIr(): IrParameterizedType.Argument = IrTypeArgumentImpl(variance.backendIr, type.toBackendIr())

    override fun toString(): String {
        if (variance == TypeVariance.UNSPECIFIED) {
            return type.toString()
        }

        return "$variance $type"
    }

    private fun rewrap(newType: BoundTypeReference): BoundTypeArgument {
        if (type === newType) {
            return this
        }

        return BoundTypeArgument(context, astNode, newType)
    }
}

private class IrTypeArgumentImpl(
    override val variance: IrTypeVariance,
    override val type: IrType
) : IrParameterizedType.Argument {
    override fun toString() = "${variance.name.lowercase()} $type"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IrTypeArgumentImpl) return false

        if (variance != other.variance) return false
        if (type != other.type) return false

        return true
    }

    override fun hashCode(): Int {
        var result = variance.hashCode()
        result = 31 * result + type.hashCode()
        return result
    }
}