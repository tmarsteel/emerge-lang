package compiler.binding.type

import compiler.InternalCompilerError
import compiler.ast.type.TypeMutability
import compiler.ast.type.TypeReference
import compiler.binding.SideEffectPrediction
import compiler.lexer.Span
import compiler.reportings.Reporting
import io.github.tmarsteel.emerge.backend.api.ir.IrType

/**
 * A generic type in the process of being inferred. To understand the difference between [TypeVariable]
 * and [GenericTypeReference], consider this function:
 *
 *     n <T> forEach(elements: List<out T>, action: (T) -> Unit) {
 *
 *     }
 *
 * When validating the `forEach` function definition (including the body), `T` is a [GenericTypeReference],
 * meaning that the concrete type of T can never be known and we can treat all mentions of `T` like mentions
 * of `T`s bound.
 *
 * When validating an invocation of `forEach`, the `T` of `forEach` becomes a type variable, because it can be
 * reasoned about from the types of the parameters (if you pass a `List<Int>` to `forEach`, the action has to accept
 * an `Int`, too).
 *
 * So, in the context of:
 *
 *     val x: List<Int>
 *     forEach(x, { someX -> ... })
 *
 * `T` becomes a [TypeVariable] and thus can be inferred to be `Int` for the `x` argument; and as a result the compiler
 * can infer that `someX` in the lambda is of type `Int`.
 */
class TypeVariable private constructor(
    val parameter: BoundTypeParameter,
    override val mutability: TypeMutability,
    override val span: Span?,
) : BoundTypeReference {
    constructor(ref: GenericTypeReference) : this(
        ref.parameter,
        ref.mutability,
        ref.span,
    )
    constructor(parameter: BoundTypeParameter) : this(
        parameter,
        parameter.bound.mutability,
        parameter.astNode.name.span,
    )

    override val simpleName get() = parameter.name
    override val isNullable get() = parameter.bound.isNullable
    override val inherentTypeBindings = TypeUnification.EMPTY
    override val destructorThrowBehavior = SideEffectPrediction.POSSIBLY

    val effectiveBound: BoundTypeReference get() {
        return parameter.bound
            .withCombinedMutability(mutability)
    }

    override fun defaultMutabilityTo(mutability: TypeMutability?): BoundTypeReference {
        throw InternalCompilerError("not implemented as it was assumed that this can never happen")
    }

    override fun withMutability(modifier: TypeMutability?): BoundTypeReference {
        throw InternalCompilerError("not implemented as it was assumed that this can never happen")
    }

    override fun withCombinedMutability(mutability: TypeMutability?): BoundTypeReference {
        throw InternalCompilerError("not implemented as it was assumed that this can never happen")
    }

    override fun withCombinedNullability(nullability: TypeReference.Nullability): BoundTypeReference {
        throw InternalCompilerError("not implemented as it was assumed that this can never happen")
    }

    override fun validate(forUsage: TypeUseSite): Collection<Reporting> {
        throw InternalCompilerError("not implemented as it was assumed that this can never happen")
    }

    override fun closestCommonSupertypeWith(other: BoundTypeReference): BoundTypeReference {
        throw InternalCompilerError("not implemented as it was assumed that this can never happen")
    }

    override fun withTypeVariables(variables: List<BoundTypeParameter>): BoundTypeReference {
        return this
    }

    override fun unify(
        assigneeType: BoundTypeReference,
        assignmentLocation: Span,
        carry: TypeUnification
    ): TypeUnification {
        return when (assigneeType) {
            is RootResolvedTypeReference,
            is GenericTypeReference,
            is BoundTypeFromArgument -> {
                val newCarry = carry.plus(this, assigneeType, assignmentLocation)
                val selfBinding = newCarry.bindings[this] ?: return newCarry
                selfBinding.unify(assigneeType, assignmentLocation, newCarry)
            }
            is UnresolvedType -> unify(assigneeType.standInType, assignmentLocation, carry)
            is TypeVariable -> throw InternalCompilerError("not implemented as it was assumed that this can never happen")
            is NullableTypeReference -> {
                if (effectiveBound.isNullable) {
                    return carry.plus(this, assigneeType, assignmentLocation)
                } else {
                    val carry2 = carry.plusReporting(Reporting.valueNotAssignable(
                        this,
                        assigneeType,
                        "Cannot assign a possibly null value to a non-nullable reference",
                        assignmentLocation,
                    ))
                    unify(assigneeType.nested, assignmentLocation, carry2)
                }
            }
        }
    }

    fun flippedUnify(targetType: BoundTypeReference, assignmentLocation: Span, carry: TypeUnification): TypeUnification {
        val newCarry = carry.plus(this, targetType, assignmentLocation)
        val selfBinding = carry.bindings[this] ?: return newCarry
        return targetType.unify(selfBinding, assignmentLocation, newCarry)
    }

    override fun instantiateFreeVariables(context: TypeUnification): BoundTypeReference {
        return context.bindings[this] ?: effectiveBound
    }

    override fun instantiateAllParameters(context: TypeUnification): BoundTypeReference {
        return instantiateFreeVariables(context)
    }

    override fun hasSameBaseTypeAs(other: BoundTypeReference): Boolean {
        return other == this
    }

    override fun toBackendIr(): IrType {
        throw InternalCompilerError("Attempting to create BackendIr from unresolved type variable $this at ${this.span}")
    }

    override fun toString(): String {
        var str = ""
        if (mutability != parameter.bound.mutability) {
            str += mutability.keyword.text + " "
        }
        str += simpleName
        return str
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TypeVariable) return false

        if (parameter != other.parameter) return false

        return true
    }

    override fun hashCode(): Int {
        return parameter.hashCode()
    }
}