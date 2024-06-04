package compiler.binding.type

import compiler.ast.type.TypeVariance
import compiler.lexer.Span
import compiler.reportings.Reporting
import compiler.reportings.ValueNotAssignableReporting

/* TODO: optimization potential
 * Have a custom collection class that optimized the get-a-copy-plus-one-element use-case
 * Set-properties are probably not needed because we can track changes there explicitly
 * and answer the question "Did this unification produce any errors?" without the set-contains
 * operation done now
 */

interface TypeUnification {
    val bindings: Map<TypeVariable, BoundTypeReference>
    val reportings: Set<Reporting>

    fun plus(variable: TypeVariable, binding: BoundTypeReference, assignmentLocation: Span): TypeUnification
    fun plusReporting(reporting: Reporting): TypeUnification

    fun doTreatingNonUnifiableAsOutOfBounds(parameter: BoundTypeParameter, argument: BoundTypeReference, action: (TypeUnification) -> TypeUnification): TypeUnification {
        return DecoratingTypeUnification.doWithDecorated(ValueNotAssignableAsArgumentOutOfBounds(this, parameter, argument), action)
    }

    fun doWithIgnoringReportings(action: (TypeUnification) -> TypeUnification): TypeUnification

    fun getErrorsNotIn(previous: TypeUnification): Sequence<Reporting> {
        return reportings.asSequence()
            .filter { it.level >= Reporting.Level.ERROR }
            .filter { it !in previous.reportings }
    }

    companion object {
        val EMPTY: TypeUnification = DefaultTypeUnification.EMPTY

        /**
         * For a type reference or function call, builds a [TypeUnification] that contains the explicit type arguments. E.g.:
         *
         *     class X<E, T> {}
         *
         *     val foo: X<Int, Boolean>
         *
         * Then you would call `fromExplicit(<params of base type X>, <int ant boolean type args>, ...)`
         * and the return value would be `[E = Int, T = Boolean] Errors: 0`
         *
         * @param argumentsLocation Location of where the type arguments are being supplied. Used as a fallback
         * for [Reporting]s if there are no type arguments and [allowZeroTypeArguments] is false
         * @param allowZeroTypeArguments Whether 0 type arguments is valid even if [typeParameters] is non-empty.
         * This is the case for function invocations, but type references always need to specify all type args.
         */
        fun fromExplicit(
            typeParameters: List<BoundTypeParameter>,
            arguments: List<BoundTypeArgument>?,
            argumentsLocation: Span,
            allowMissingTypeArguments: Boolean = false,
        ): TypeUnification {
            var unification = EMPTY

            if (arguments == null) {
                if (typeParameters.isNotEmpty() && !allowMissingTypeArguments) {
                    for (typeParam in typeParameters) {
                        unification = unification.plusReporting(Reporting.missingTypeArgument(typeParam, argumentsLocation))
                    }
                }

                return unification
            }

            for (i in 0..typeParameters.lastIndex.coerceAtMost(arguments.lastIndex)) {
                val parameter = typeParameters[i]
                val argument = arguments[i]
                if (argument.variance != TypeVariance.UNSPECIFIED && parameter.variance != TypeVariance.UNSPECIFIED) {
                    if (argument.variance != parameter.variance) {
                        unification = unification.plusReporting(Reporting.typeArgumentVarianceMismatch(parameter, argument))
                    } else {
                        unification = unification.plusReporting(Reporting.typeArgumentVarianceSuperfluous(argument))
                    }
                }

                val argumentType = argument.asBoundTypeReference()
                val nextUnification = unification.doTreatingNonUnifiableAsOutOfBounds(parameter, argumentType) { subUnification ->
                    parameter.bound.unify(argumentType, argumentType.span ?: Span.UNKNOWN, subUnification)
                }
                val hadErrors = nextUnification.getErrorsNotIn(unification).any()
                unification = nextUnification.plus(TypeVariable(parameter), if (!hadErrors) argumentType else parameter.bound, argumentType.span ?: Span.UNKNOWN)
            }

            for (i in arguments.size..typeParameters.lastIndex) {
                unification = unification.plusReporting(
                    Reporting.missingTypeArgument(typeParameters[i], arguments.lastOrNull()?.astNode?.span ?: argumentsLocation)
                )
            }
            if (arguments.size > typeParameters.size) {
                unification = unification.plusReporting(
                    Reporting.superfluousTypeArguments(
                        typeParameters.size,
                        arguments[typeParameters.size],
                    )
                )
            }

            return unification
        }
    }
}

private class DefaultTypeUnification private constructor(
    override val bindings: Map<TypeVariable, BoundTypeReference>,
    override val reportings: Set<Reporting>,
) : TypeUnification {
    override fun plusReporting(reporting: Reporting): TypeUnification {
        return DefaultTypeUnification(bindings, reportings + setOf(reporting))
    }

    override fun plus(variable: TypeVariable, binding: BoundTypeReference, assignmentLocation: Span): TypeUnification {
        val previousBinding = bindings[variable]
        if (previousBinding is BoundTypeFromArgument) {
            // type has been fixed explicitly -> no rebinding
            return this
        }

        val newBinding = when {
            binding is BoundTypeFromArgument -> binding
            else -> previousBinding?.closestCommonSupertypeWith(binding) ?: binding
        }

        // TODO: use effectiveBound
        return variable.parameter.bound.unify(
            newBinding,
            assignmentLocation,
            DefaultTypeUnification(bindings.plus(variable to newBinding), reportings),
        )
    }

    override fun doWithIgnoringReportings(action: (TypeUnification) -> TypeUnification): TypeUnification {
        val result = action(this)
        return DefaultTypeUnification(
            result.bindings,
            this.reportings,
        )
    }

    override fun toString(): String {
        val bindingsStr = bindings.entries.joinToString(
            prefix = "[",
            transform = { (key, value) -> "$key = $value" },
            separator = ", ",
            postfix = "]",
        )
        val nErrors = reportings.count { it.level >= Reporting.Level.ERROR }

        return "$bindingsStr Errors:$nErrors"
    }

    companion object {
        val EMPTY = DefaultTypeUnification(emptyMap(), emptySet())
    }
}

private abstract class DecoratingTypeUnification<Self : DecoratingTypeUnification<Self>> : TypeUnification {
    abstract val undecorated: TypeUnification

    abstract override fun plus(variable: TypeVariable, binding: BoundTypeReference, assignmentLocation: Span): Self

    companion object {
        inline fun <reified T : DecoratingTypeUnification<*>> doWithDecorated(modified: T, action: (TypeUnification) -> TypeUnification): TypeUnification {
            val result = action(modified)
            return (result as T).undecorated
        }
    }
}

private class ValueNotAssignableAsArgumentOutOfBounds(
    override val undecorated: TypeUnification,
    private val parameter: BoundTypeParameter,
    private val argument: BoundTypeReference,
) : DecoratingTypeUnification<ValueNotAssignableAsArgumentOutOfBounds>() {
    override val bindings get() = undecorated.bindings
    override val reportings get() = undecorated.reportings

    override fun plusReporting(reporting: Reporting): TypeUnification {
        val reportingToAdd = if (reporting !is ValueNotAssignableReporting) reporting else {
            Reporting.typeArgumentOutOfBounds(parameter, argument, reporting.reason)
        }

        return ValueNotAssignableAsArgumentOutOfBounds(undecorated.plusReporting(reportingToAdd), parameter, argument)
    }

    override fun plus(variable: TypeVariable, binding: BoundTypeReference, assignmentLocation: Span): ValueNotAssignableAsArgumentOutOfBounds {
        return ValueNotAssignableAsArgumentOutOfBounds(undecorated.plus(variable, binding, assignmentLocation), parameter, argument)
    }

    override fun doWithIgnoringReportings(action: (TypeUnification) -> TypeUnification): TypeUnification {
        return ValueNotAssignableAsArgumentOutOfBounds(
            doWithIgnoringReportings(action),
            parameter,
            argument,
        )
    }
}