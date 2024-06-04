package compiler.binding.type

import compiler.InternalCompilerError
import compiler.ast.type.TypeMutability
import compiler.ast.type.TypeReference
import compiler.ast.type.TypeVariance
import compiler.binding.SideEffectPrediction
import compiler.binding.context.CTContext
import compiler.lexer.Span
import compiler.reportings.Reporting
import compiler.util.andThen
import io.github.tmarsteel.emerge.backend.api.ir.IrBaseType
import io.github.tmarsteel.emerge.backend.api.ir.IrGenericTypeReference
import io.github.tmarsteel.emerge.backend.api.ir.IrType

sealed class GenericTypeReference : BoundTypeReference {
    abstract val context: CTContext

    abstract val original: TypeReference
    abstract val parameter: BoundTypeParameter
    abstract val effectiveBound: BoundTypeReference

    override val simpleName get() = parameter.name
    override val isNullable get() = effectiveBound.isNullable
    override val mutability get() = effectiveBound.mutability
    override val span get() = original.declaringNameToken?.span
    override val inherentTypeBindings = TypeUnification.EMPTY

    override fun withMutability(modifier: TypeMutability?): GenericTypeReference {
        return mapEffectiveBound { it.withMutability(modifier) }
    }

    override fun withCombinedMutability(mutability: TypeMutability?): GenericTypeReference {
        return mapEffectiveBound { it.withCombinedMutability(mutability) }
    }

    override fun withCombinedNullability(nullability: TypeReference.Nullability): BoundTypeReference {
        return when(nullability) {
            TypeReference.Nullability.UNSPECIFIED -> this
            TypeReference.Nullability.NULLABLE -> NullableTypeReference(this)
            TypeReference.Nullability.NOT_NULLABLE -> mapEffectiveBound { it.withCombinedNullability(nullability) }
        }
    }

    override fun validate(forUsage: TypeUseSite): Collection<Reporting> {
        return effectiveBound.validate(forUsage) + setOfNotNull(forUsage.validateForTypeVariance(parameter.variance))
    }

    override fun hasSameBaseTypeAs(other: BoundTypeReference): Boolean {
        return other is GenericTypeReference && this.parameter === other.parameter
    }

    override fun unify(assigneeType: BoundTypeReference, assignmentLocation: Span, carry: TypeUnification): TypeUnification {
        return when (assigneeType) {
            is NullableTypeReference -> {
                // TODO: try the assignment ignoring the nullability problem. If it works, report the nullability problem
                // otherwise, report the bigger/harder to fix problem first. This requires code in TypeUnification
                // that can determin success/failure of a sub-unification
                carry.plusReporting(Reporting.valueNotAssignable(this, assigneeType, "Cannot assign a possibly null value to a non-nullable reference", assignmentLocation))
            }
            is UnresolvedType -> unify(assigneeType.standInType, assignmentLocation, carry)
            is RootResolvedTypeReference -> carry.plusReporting(Reporting.valueNotAssignable(
                this,
                assigneeType,
                "$assigneeType cannot be proven to be a subtype of $simpleName",
                assignmentLocation,
            ))
            is TypeVariable -> assigneeType.flippedUnify(this, assignmentLocation, carry)
            is BoundTypeFromArgument -> when (assigneeType.argument.variance) {
                TypeVariance.OUT,
                TypeVariance.UNSPECIFIED -> unify(assigneeType.effectiveType, assignmentLocation, carry)
                TypeVariance.IN -> unify(context.swCtx.typeParameterDefaultBound, assignmentLocation, carry)
            }
            is GenericTypeReference -> {
                // current assumption: confusing two distinct generics with the same name is not possible, given
                // that it is forbidden to shadow type variables (e.g. class A<T> { fun foo<T>() {} })
                if (assigneeType.isSubtypeOf(this)) {
                    return carry
                } else {
                    return carry.plusReporting(Reporting.valueNotAssignable(
                        this,
                        assigneeType,
                        "${assigneeType.simpleName} cannot be proven to be a subtype of $simpleName",
                        assignmentLocation,
                    ))
                }
            }
        }
    }

    override fun defaultMutabilityTo(mutability: TypeMutability?): GenericTypeReference {
        return mapEffectiveBound { it.defaultMutabilityTo(mutability) }
    }

    override fun closestCommonSupertypeWith(other: BoundTypeReference): BoundTypeReference {
        return when (other) {
            is GenericTypeReference -> {
                if (this.isSubtypeOf(other)) {
                    other
                } else if (other.isSubtypeOf(this)) {
                    this
                } else {
                    effectiveBound.closestCommonSupertypeWith(other.effectiveBound)
                }
            }
            is BoundTypeFromArgument -> other.closestCommonSupertypeWith(this)
            is TypeVariable -> throw InternalCompilerError("not implemented as it was assumed that this can never happen")
            else -> effectiveBound.closestCommonSupertypeWith(other)
        }
    }

    override fun instantiateFreeVariables(context: TypeUnification): BoundTypeReference {
        return this
    }

    override fun instantiateAllParameters(context: TypeUnification): BoundTypeReference {
        // TODO: this linear search is super inefficient, optimize
        val binding = context.bindings.entries.find { it.key.parameter == this.parameter }
        return binding?.value ?: this
    }

    override fun withTypeVariables(variables: List<BoundTypeParameter>): BoundTypeReference {
        if (this.parameter in variables) {
            return TypeVariable(this)
        }

        return this
    }

    private fun isSubtypeOf(other: GenericTypeReference): Boolean {
        if (this.parameter == other.parameter) {
            return true
        }

        val bound = this.effectiveBound
        if (bound !is GenericTypeReference){
            return false
        }

        return bound.isSubtypeOf(other)
    }

    override val destructorThrowBehavior = SideEffectPrediction.POSSIBLY

    fun mapEffectiveBound(mapper: (BoundTypeReference) -> BoundTypeReference): GenericTypeReference {
        return MappedEffectiveBoundGenericTypeReference(this, mapper)
    }

    override fun toBackendIr(): IrType = IrGenericTypeReferenceImpl(
        parameter.toBackendIr(),
        effectiveBound.toBackendIr(),
    )

    override fun toString() = parameter.name

    companion object {
        operator fun invoke(original: TypeReference, parameter: BoundTypeParameter): BoundTypeReference {
            return NakedGenericTypeReference(original, parameter)
                .withMutability(original.mutability)
                .withCombinedNullability(original.nullability)
        }
    }
}

private class NakedGenericTypeReference(
    override val original: TypeReference,
    override val parameter: BoundTypeParameter,
) : GenericTypeReference() {
    override val context = parameter.context
    override val effectiveBound: BoundTypeReference
        get() = parameter.bound
}

private class MappedEffectiveBoundGenericTypeReference private constructor(
    private val delegate: GenericTypeReference,
    private val mapper: (BoundTypeReference) -> BoundTypeReference,
) : GenericTypeReference() {
    override val context = delegate.context
    override val original = delegate.original
    override val parameter = delegate.parameter
    override val effectiveBound by lazy {
        mapper(delegate.effectiveBound)
    }

    companion object {
        operator fun invoke(delegate: GenericTypeReference, mapper: (BoundTypeReference) -> BoundTypeReference): MappedEffectiveBoundGenericTypeReference {
            if (delegate is MappedEffectiveBoundGenericTypeReference) {
                return MappedEffectiveBoundGenericTypeReference(
                    delegate.delegate,
                    delegate.mapper andThen mapper
                )
            } else {
                return MappedEffectiveBoundGenericTypeReference(
                    delegate,
                    mapper,
                )
            }
        }
    }
}

internal class IrGenericTypeReferenceImpl(
    override val parameter: IrBaseType.Parameter,
    override val effectiveBound: IrType,
) : IrGenericTypeReference {
    override fun toString() = "IrGenericReference[${parameter.name} : $effectiveBound]"
    override fun asNullable(): IrGenericTypeReference = IrGenericTypeReferenceImpl(parameter, effectiveBound.asNullable())
}