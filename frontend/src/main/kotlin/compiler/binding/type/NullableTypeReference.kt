package compiler.binding.type

import compiler.ast.type.TypeMutability
import compiler.ast.type.TypeReference
import compiler.binding.BoundMemberFunction
import compiler.binding.BoundOverloadSet
import compiler.binding.SideEffectPrediction
import compiler.binding.basetype.BoundBaseTypeMemberVariable
import compiler.lexer.Span
import compiler.reportings.Reporting

class NullableTypeReference private constructor(
    internal val nested: BoundTypeReference
): BoundTypeReference {
    override val simpleName get() = nested.simpleName
    override val mutability get() = nested.mutability
    override val span get() = nested.span
    override val isNullable = true

    override fun defaultMutabilityTo(mutability: TypeMutability?): BoundTypeReference {
        return rewrap(nested.defaultMutabilityTo(mutability))
    }

    override fun withMutability(modifier: TypeMutability?): BoundTypeReference {
        return rewrap(nested.withMutability(modifier))
    }

    override fun withCombinedMutability(mutability: TypeMutability?): BoundTypeReference {
        return rewrap(nested.withCombinedMutability(mutability))
    }

    override fun withCombinedNullability(nullability: TypeReference.Nullability): BoundTypeReference {
        return when (nullability) {
            TypeReference.Nullability.NULLABLE -> this
            TypeReference.Nullability.UNSPECIFIED -> this
            TypeReference.Nullability.NOT_NULLABLE -> nested
        }
    }

    override fun validate(forUsage: TypeUseSite): Collection<Reporting> {
        return nested.validate(forUsage)
    }

    override fun closestCommonSupertypeWith(other: BoundTypeReference): BoundTypeReference {
        return rewrap(nested.closestCommonSupertypeWith(other))
    }

    override fun findMemberVariable(name: String): BoundBaseTypeMemberVariable? {
        return nested.findMemberVariable(name)
    }

    override fun findMemberFunction(name: String): Collection<BoundOverloadSet<BoundMemberFunction>> {
        return nested.findMemberFunction(name)
    }

    override fun withTypeVariables(variables: List<BoundTypeParameter>): BoundTypeReference {
        return rewrap(nested.withTypeVariables(variables))
    }

    override fun unify(
        assigneeType: BoundTypeReference,
        assignmentLocation: Span,
        carry: TypeUnification,
    ): TypeUnification {
        return when(assigneeType) {
            is NullableTypeReference -> nested.unify(assigneeType.nested, assignmentLocation, carry)
            is BoundTypeFromArgument -> unify(assigneeType.effectiveType, assignmentLocation, carry)
            is GenericTypeReference -> when(nested) {
                is GenericTypeReference -> nested.unify(assigneeType, assignmentLocation, carry)
                is BoundTypeFromArgument -> nested.effectiveType.unify(assigneeType, assignmentLocation, carry)
                else -> unify(assigneeType.effectiveBound, assignmentLocation, carry)
            }
            else -> nested.unify(assigneeType, assignmentLocation, carry)
        }
    }

    override fun instantiateFreeVariables(context: TypeUnification): BoundTypeReference {
        return rewrap(nested.instantiateFreeVariables(context))
    }

    override fun instantiateAllParameters(context: TypeUnification): BoundTypeReference {
        return rewrap(nested.instantiateAllParameters(context))
    }

    override fun hasSameBaseTypeAs(other: BoundTypeReference): Boolean {
        return nested.hasSameBaseTypeAs(other)
    }

    override fun isDisjointWith(other: BoundTypeReference): Boolean {
        return nested.isDisjointWith(other)
    }

    override val inherentTypeBindings get() = nested.inherentTypeBindings

    override val destructorThrowBehavior get() = when (nested.destructorThrowBehavior) {
        null -> null
        SideEffectPrediction.NEVER -> SideEffectPrediction.NEVER
        else -> SideEffectPrediction.POSSIBLY
    }

    override fun toBackendIr() = nested.toBackendIr().asNullable()

    override fun toString(): String = "$nested?"

    private fun rewrap(newNested: BoundTypeReference): BoundTypeReference {
        if (newNested === nested) {
            return this
        }

        if (newNested is NullableTypeReference) {
            return newNested
        }

        return NullableTypeReference(newNested)
    }

    companion object {
        operator fun invoke(nested: BoundTypeReference): BoundTypeReference {
            return when (nested) {
                is NullableTypeReference -> nested
                else -> NullableTypeReference(nested.withCombinedNullability(TypeReference.Nullability.NOT_NULLABLE))
            }
        }
    }
}