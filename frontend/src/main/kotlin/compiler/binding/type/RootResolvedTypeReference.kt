package compiler.binding.type

import compiler.CoreIntrinsicsModule
import compiler.InternalCompilerError
import compiler.StandardLibraryModule
import compiler.ast.type.TypeMutability
import compiler.ast.type.TypeReference
import compiler.binding.BoundMemberFunction
import compiler.binding.BoundOverloadSet
import compiler.binding.SideEffectPrediction
import compiler.binding.basetype.BoundBaseType
import compiler.binding.basetype.BoundBaseTypeMemberVariable
import compiler.lexer.Span
import compiler.reportings.Reporting
import io.github.tmarsteel.emerge.backend.api.ir.IrBaseType
import io.github.tmarsteel.emerge.backend.api.ir.IrParameterizedType
import io.github.tmarsteel.emerge.backend.api.ir.IrSimpleType
import io.github.tmarsteel.emerge.backend.api.ir.IrType

/**
 * A [TypeReference] where the root type is resolved
 */
class RootResolvedTypeReference private constructor(
    val original: TypeReference?,
    private val explicitMutability: TypeMutability?,
    val baseType: BoundBaseType,
    val arguments: List<BoundTypeArgument>?,
) : BoundTypeReference {
    init {
        if (baseType.declaration.typeParameters == null && arguments != null) {
            throw InternalCompilerError("Type arguments for a type that doesn't declare parameters!")
        }
    }

    override val isNullable = false
    override val mutability = if (baseType.isCoreScalar) TypeMutability.IMMUTABLE else (explicitMutability ?: original?.mutability ?: TypeMutability.READONLY)
    override val simpleName = original?.simpleName ?: baseType.simpleName
    override val span = original?.declaringNameToken?.span

    override val inherentTypeBindings by lazy {
        TypeUnification.fromExplicit(
            baseType.typeParameters ?: emptyList(),
            arguments,
            span ?: Span.UNKNOWN
        )
    }

    override val destructorThrowBehavior get() = when (baseType.kind) {
        BoundBaseType.Kind.CLASS -> baseType.destructor?.throwBehavior
        else -> SideEffectPrediction.POSSIBLY
    }

    constructor(original: TypeReference, baseType: BoundBaseType, parameters: List<BoundTypeArgument>?) : this(
        original,
        original.mutability,
        baseType,
        parameters,
    )

    override fun withMutability(modifier: TypeMutability?): RootResolvedTypeReference {
        return RootResolvedTypeReference(
            original,
            if (baseType.isCoreScalar) TypeMutability.IMMUTABLE else modifier,
            baseType,
            arguments?.map { it.defaultMutabilityTo(modifier) },
        )
    }

    override fun withCombinedMutability(mutability: TypeMutability?): RootResolvedTypeReference {
        val combinedMutability = mutability?.let { this.mutability.combinedWith(it) } ?: this.mutability
        return RootResolvedTypeReference(
            original,
            combinedMutability,
            baseType,
            arguments?.map { it.defaultMutabilityTo(combinedMutability) },
        )
    }

    override fun withCombinedNullability(nullability: TypeReference.Nullability): BoundTypeReference {
        if (nullability == TypeReference.Nullability.NULLABLE) {
            return NullableTypeReference(this)
        }
        return this
    }

    override fun defaultMutabilityTo(mutability: TypeMutability?): RootResolvedTypeReference {
        if (mutability == null || original?.mutability != null || explicitMutability != null) {
            return this
        }

        return RootResolvedTypeReference(
            original,
            mutability,
            baseType,
            arguments?.map { it.defaultMutabilityTo(mutability) },
        )
    }

    override fun validate(forUsage: TypeUseSite): Collection<Reporting> {
        val reportings = mutableSetOf<Reporting>()

        arguments?.forEach { reportings.addAll(it.validate(forUsage.deriveIrrelevant())) }
        reportings.addAll(inherentTypeBindings.reportings)
        reportings.addAll(baseType.validateAccessFrom(forUsage.usageLocation))
        forUsage.exposedBy?.let { exposer ->
            if (exposer.visibility.isPossiblyBroaderThan(baseType.visibility)) {
                reportings.add(Reporting.hiddenTypeExposed(baseType, exposer, span ?: Span.UNKNOWN))
            }
        }

        return reportings
    }

    override fun hasSameBaseTypeAs(other: BoundTypeReference): Boolean {
        return when (other) {
            is RootResolvedTypeReference -> this.baseType == other.baseType
            is BoundTypeFromArgument -> hasSameBaseTypeAs(other.effectiveType)
            else -> false
        }
    }

    override fun closestCommonSupertypeWith(other: BoundTypeReference): BoundTypeReference {
        return when (other) {
            is UnresolvedType -> other.closestCommonSupertypeWith(this)
            is NullableTypeReference -> NullableTypeReference(closestCommonSupertypeWith(other.nested))
            is RootResolvedTypeReference -> {
                val commonSupertype = BoundBaseType.closestCommonSupertypeOf(this.baseType, other.baseType)
                check(commonSupertype.typeParameters.isNullOrEmpty()) { "Generic supertypes are not implemented, yet." }
                RootResolvedTypeReference(
                    null,
                    this.mutability.combinedWith(other.mutability),
                    commonSupertype,
                    null,
                )
            }
            is GenericTypeReference -> other.closestCommonSupertypeWith(this)
            is BoundTypeFromArgument -> other.closestCommonSupertypeWith(this)
            is TypeVariable -> throw InternalCompilerError("not implemented as it was assumed that this can never happen")
        }
    }

    override fun findMemberVariable(name: String): BoundBaseTypeMemberVariable? = baseType.resolveMemberVariable(name)

    val memberFunctions: Collection<BoundOverloadSet<BoundMemberFunction>> get() = baseType.memberFunctions

    override fun findMemberFunction(name: String): Collection<BoundOverloadSet<BoundMemberFunction>> {
        return baseType.resolveMemberFunction(name)
    }

    override fun withTypeVariables(variables: List<BoundTypeParameter>): RootResolvedTypeReference {
        return RootResolvedTypeReference(
            original,
            explicitMutability,
            baseType,
            arguments?.map { it.withTypeVariables(variables) },
        )
    }

    override fun unify(assigneeType: BoundTypeReference, assignmentLocation: Span, carry: TypeUnification): TypeUnification {
        when (assigneeType) {
            is RootResolvedTypeReference -> {
                // this must be a subtype of other
                if (!(assigneeType.baseType isSubtypeOf this.baseType)) {
                    return carry.plusReporting(
                        Reporting.valueNotAssignable(this, assigneeType, "${assigneeType.baseType.simpleName} is not a subtype of ${this.baseType.simpleName}", assignmentLocation)
                    )
                }

                // the modifiers must be compatible
                if (!(assigneeType.mutability isAssignableTo this.mutability)) {
                    return carry.plusReporting(
                        Reporting.valueNotAssignable(this, assigneeType, "cannot assign a ${assigneeType.mutability} value to a ${this.mutability} reference", assignmentLocation)
                    )
                }

                val selfArgs = this.arguments ?: emptyList()
                val assigneeArgs = assigneeType.arguments ?: emptyList()
                return selfArgs
                    .zip(assigneeArgs)
                    .fold(carry) { innerCarry, (targetArg, sourceArg) ->
                        targetArg.asBoundTypeReference().unify(sourceArg.asBoundTypeReference(), assignmentLocation, innerCarry)
                    }
            }
            is UnresolvedType -> return unify(assigneeType.standInType, assignmentLocation, carry)
            is GenericTypeReference -> return unify(assigneeType.effectiveBound, assignmentLocation, carry)
            is BoundTypeFromArgument -> {
                // this branch is PROBABLY only taken when verifying the bound of a type parameter against an argument
                // in this case this is the bound and assigneeType is the argument
                // variance is not important in that scenario because type arguments must be subtypes of the parameters bounds
                return unify(assigneeType.effectiveType, assignmentLocation, carry)
            }
            is TypeVariable -> return assigneeType.flippedUnify(this, assignmentLocation, carry)
            is NullableTypeReference -> return carry.plusReporting(
                Reporting.valueNotAssignable(this, assigneeType, "cannot assign a possibly null value to a non-null reference", assignmentLocation)
            )
        }
    }

    override fun instantiateFreeVariables(context: TypeUnification): RootResolvedTypeReference {
        return RootResolvedTypeReference(
            original,
            explicitMutability,
            baseType,
            arguments?.map { it.instantiateFreeVariables(context) },
        )
    }

    override fun instantiateAllParameters(context: TypeUnification): RootResolvedTypeReference {
        return RootResolvedTypeReference(
            original,
            explicitMutability,
            baseType,
            arguments?.map { it.instantiateAllParameters(context) },
        )
    }

    private val _string: String by lazy {
        var str = mutability.toString()
        str += " "

        str += when {
            baseType.canonicalName.packageName == CoreIntrinsicsModule.NAME ||
            baseType.canonicalName.packageName == StandardLibraryModule.NAME -> baseType.simpleName
            else -> baseType.canonicalName.toString()
        }

        if (arguments != null) {
            str += arguments.joinToString(
                prefix = "<",
                separator = ", ",
                postfix = ">",
            )
        }

        str
    }

    override fun toBackendIr(): IrType {
        val raw = IrSimpleTypeImpl(baseType.toBackendIr(), false)
        if (arguments.isNullOrEmpty() || baseType.typeParameters.isNullOrEmpty()) {
            return raw
        }

        return IrParameterizedTypeImpl(
            raw,
            baseType.typeParameters.zip(arguments).associate { (param, arg) ->
                param.name to arg.toBackendIr()
            }
        )
    }

    override fun toString(): String = _string

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RootResolvedTypeReference

        if (baseType != other.baseType) return false
        if (arguments != other.arguments) return false
        if (mutability != other.mutability) return false

        return true
    }

    override fun hashCode(): Int {
        var result = baseType.hashCode()
        result = 31 * result + arguments.hashCode()
        result = 31 * result + mutability.hashCode()
        return result
    }
}

internal class IrSimpleTypeImpl(
    override val baseType: IrBaseType,
    override val isNullable: Boolean,
) : IrSimpleType {
    override fun toString() = "IrSimpleType[${baseType.canonicalName}]"
    override fun asNullable(): IrSimpleType = IrSimpleTypeImpl(baseType, true)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IrSimpleTypeImpl) return false

        if (baseType != other.baseType) return false
        if (isNullable != other.isNullable) return false

        return true
    }

    override fun hashCode(): Int {
        var result = baseType.hashCode()
        result = 31 * result + isNullable.hashCode()
        return result
    }

}

internal class IrParameterizedTypeImpl(
    override val simpleType: IrSimpleType,
    override val arguments: Map<String, IrParameterizedType.Argument>,
) : IrParameterizedType {
    override fun toString() = "IrParameterizedType[$simpleType" + arguments.entries.joinToString(
        prefix = "<",
        transform = { (name, value) -> "$name = $value" },
        postfix = ">]"
    )

    override fun asNullable(): IrParameterizedType = IrParameterizedTypeImpl(simpleType.asNullable(), arguments)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IrParameterizedTypeImpl) return false

        if (simpleType != other.simpleType) return false
        if (arguments != other.arguments) return false

        return true
    }

    override fun hashCode(): Int {
        var result = simpleType.hashCode()
        result = 31 * result + arguments.hashCode()
        return result
    }
}