package compiler.binding.type

import compiler.ast.type.TypeMutability
import compiler.ast.type.TypeReference
import compiler.ast.type.TypeVariance
import compiler.binding.BoundMemberFunction
import compiler.binding.BoundOverloadSet
import compiler.binding.SideEffectPrediction
import compiler.binding.basetype.BoundBaseTypeMemberVariable
import compiler.lexer.Span
import compiler.reportings.Reporting
import io.github.tmarsteel.emerge.backend.api.ir.IrType

class BoundTypeFromArgument(
    val argument: BoundTypeArgument,
    val effectiveType: BoundTypeReference,
) : BoundTypeReference {
    init {
        check(effectiveType !is NullableTypeReference) {
            "wrap the ${this::class.simpleName} in ${NullableTypeReference::class.simpleName} instead"
        }
    }

    override val isNullable = false // by definition
    override val simpleName get()= effectiveType.simpleName
    override val mutability get()= effectiveType.mutability
    override val span get() = argument.astNode.span

    override val inherentTypeBindings: TypeUnification
        get() = TypeUnification.EMPTY

    override fun defaultMutabilityTo(mutability: TypeMutability?): BoundTypeFromArgument = rewrap(
        effectiveType.defaultMutabilityTo(mutability),
    )

    override fun validate(forUsage: TypeUseSite): Collection<Reporting> {
        return setOfNotNull(forUsage.validateForTypeVariance(argument.variance)) + effectiveType.validate(forUsage.deriveIrrelevant())
    }

    override fun withTypeVariables(variables: List<BoundTypeParameter>): BoundTypeFromArgument {
        return rewrap(effectiveType.withTypeVariables(variables))
    }

    override fun unify(assigneeType: BoundTypeReference, assignmentLocation: Span, carry: TypeUnification): TypeUnification {
        if (this.effectiveType is TypeVariable) {
            return this.effectiveType.unify(assigneeType, assignmentLocation, carry)
        }

        when (assigneeType) {
            is RootResolvedTypeReference,
            is NullableTypeReference -> {
                return effectiveType.unify(assigneeType, assignmentLocation, carry)
            }
            is BoundTypeFromArgument -> {
                if (assigneeType.effectiveType is TypeVariable) {
                    return effectiveType.unify(assigneeType.effectiveType, assignmentLocation, carry)
                }

                if (this.argument.variance == TypeVariance.UNSPECIFIED) {
                    // target needs to use the type in both IN and OUT fashion -> source must match exactly
                    if (assigneeType.effectiveType !is GenericTypeReference && !assigneeType.effectiveType.hasSameBaseTypeAs(this.effectiveType)) {
                        return carry.plusReporting(Reporting.valueNotAssignable(this, assigneeType, "the exact type ${this.effectiveType} is required", assignmentLocation))
                    }

                    if (assigneeType.argument.variance != TypeVariance.UNSPECIFIED) {
                        return carry.plusReporting(Reporting.valueNotAssignable(this, assigneeType, "cannot assign an in-variant value to an exact-variant reference", assignmentLocation))
                    }

                    // checks for mutability and nullability
                    return this.effectiveType.unify(assigneeType.effectiveType, assignmentLocation, carry)
                }

                if (this.argument.variance == TypeVariance.OUT) {
                    if (assigneeType.argument.variance == TypeVariance.OUT || assigneeType.argument.variance == TypeVariance.UNSPECIFIED) {
                        return this.effectiveType.unify(assigneeType.effectiveType, assignmentLocation, carry)
                    }

                    check(assigneeType.argument.variance == TypeVariance.IN)
                    return carry.plusReporting(
                        Reporting.valueNotAssignable(this, assigneeType, "cannot assign in-variant value to out-variant reference", assignmentLocation)
                    )
                }

                check(this.argument.variance == TypeVariance.IN)
                if (assigneeType.argument.variance == TypeVariance.IN || assigneeType.argument.variance == TypeVariance.UNSPECIFIED) {
                    // IN variance reverses the hierarchy direction
                    return assigneeType.effectiveType.unify(this.effectiveType, assignmentLocation, carry)
                }

                return carry.plusReporting(
                    Reporting.valueNotAssignable(this, assigneeType, "cannot assign out-variant value to in-variant reference", assignmentLocation)
                )
            }
            is GenericTypeReference -> {
                return effectiveType.unify(assigneeType, assignmentLocation, carry)
            }
            is UnresolvedType -> {
                return unify(assigneeType.standInType, assignmentLocation, carry)
            }
            is TypeVariable -> return assigneeType.flippedUnify(this, assignmentLocation, carry)
        }
    }

    override fun instantiateFreeVariables(context: TypeUnification): BoundTypeReference {
        return rewrap(effectiveType.instantiateFreeVariables(context))
    }

    override fun instantiateAllParameters(context: TypeUnification): BoundTypeFromArgument {
        return rewrap(effectiveType.instantiateAllParameters(context))
    }

    override fun withMutability(modifier: TypeMutability?): BoundTypeReference {
        return rewrap(effectiveType.withMutability(modifier))
    }

    override fun withCombinedMutability(mutability: TypeMutability?): BoundTypeReference {
        return rewrap(effectiveType.withCombinedMutability(mutability))
    }

    override fun withCombinedNullability(nullability: TypeReference.Nullability): BoundTypeReference {
        return rewrap(effectiveType.withCombinedNullability(nullability))
    }

    override fun closestCommonSupertypeWith(other: BoundTypeReference): BoundTypeReference {
        return when (argument.variance) {
            TypeVariance.UNSPECIFIED,
            TypeVariance.OUT -> effectiveType.closestCommonSupertypeWith(other)
            TypeVariance.IN -> argument.context.swCtx.any.baseReference.closestCommonSupertypeWith(other)
        }
    }

    override fun findMemberVariable(name: String): BoundBaseTypeMemberVariable? {
        return effectiveType.findMemberVariable(name)
    }

    override fun findMemberFunction(name: String): Collection<BoundOverloadSet<BoundMemberFunction>> {
        return effectiveType.findMemberFunction(name)
    }

    override fun hasSameBaseTypeAs(other: BoundTypeReference): Boolean {
        return effectiveType.hasSameBaseTypeAs(other)
    }

    override val destructorThrowBehavior = SideEffectPrediction.POSSIBLY

    override fun toBackendIr(): IrType = effectiveType.toBackendIr()

    override fun toString() = effectiveType.toString()

    private fun rewrap(newEffectiveType: BoundTypeReference): BoundTypeFromArgument {
        if (newEffectiveType === effectiveType) {
            return this
        }

        return BoundTypeFromArgument(argument, newEffectiveType)
    }
}