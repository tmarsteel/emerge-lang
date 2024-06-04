/*
 * Copyright 2018 Tobias Marstaller
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */

package compiler.reportings

import compiler.ast.AstFunctionAttribute
import compiler.ast.AstPackageName
import compiler.ast.BaseTypeConstructorDeclaration
import compiler.ast.BaseTypeDestructorDeclaration
import compiler.ast.Executable
import compiler.ast.Expression
import compiler.ast.FunctionDeclaration
import compiler.ast.VariableDeclaration
import compiler.ast.type.TypeReference
import compiler.ast.type.TypeVariance
import compiler.binding.BoundAssignmentStatement
import compiler.binding.BoundBreakStatement
import compiler.binding.BoundContinueStatement
import compiler.binding.BoundDeclaredFunction
import compiler.binding.BoundExecutable
import compiler.binding.BoundFunction
import compiler.binding.BoundImportDeclaration
import compiler.binding.BoundMemberFunction
import compiler.binding.BoundOverloadSet
import compiler.binding.BoundReturnStatement
import compiler.binding.BoundStatement
import compiler.binding.BoundThrowStatement
import compiler.binding.BoundVariable
import compiler.binding.BoundVisibility
import compiler.binding.DefinitionWithVisibility
import compiler.binding.basetype.BoundBaseType
import compiler.binding.basetype.BoundBaseTypeEntry
import compiler.binding.basetype.BoundBaseTypeMemberVariable
import compiler.binding.basetype.BoundClassConstructor
import compiler.binding.basetype.BoundSupertypeDeclaration
import compiler.binding.basetype.InheritedBoundMemberFunction
import compiler.binding.context.effect.VariableLifetime
import compiler.binding.expression.*
import compiler.binding.type.BoundTypeArgument
import compiler.binding.type.BoundTypeParameter
import compiler.binding.type.BoundTypeReference
import compiler.binding.type.TypeUseSite
import compiler.lexer.IdentifierToken
import compiler.lexer.OperatorToken
import compiler.lexer.Span
import compiler.lexer.Token
import io.github.tmarsteel.emerge.backend.api.CanonicalElementName
import textutils.indentByFromSecondLine
import java.math.BigInteger

/**
 * TODO: rename to Diagnostic
 * TODO: replace the mutableSetOf(), addAll return shit with something actually efficient
 */
abstract class Reporting internal constructor(
    val level: Level,
    open val message: String,
    val span: Span
) : Comparable<Reporting>
{
    override fun compareTo(other: Reporting): Int {
        return level.compareTo(other.level)
    }

    fun toException(): ReportingException = ReportingException(this)

    protected val levelAndMessage: String get() = "($level) $message".indentByFromSecondLine(2)

    /**
     * TODO: currently, all subclasses must override this with super.toString(), because `data` is needed to detect double-reporting the same problem
     */
    override fun toString() = "$levelAndMessage\nin $span"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Reporting) return false

        if (javaClass != other.javaClass) return false
        if (span != other.span) return false

        return true
    }

    override fun hashCode(): Int {
        var result = span.hashCode()
        result = 31 * result + javaClass.hashCode()
        return result
    }


    enum class Level(val level: Int) {
        CONSECUTIVE(0),
        INFO(10),
        WARNING(20),
        ERROR(30);
    }

    // convenience methods so that the bulky constructors of the reporting class do not have to be used
    companion object {
        fun consecutive(message: String, span: Span = Span.UNKNOWN)
            = ConsecutiveFaultReporting(message, span)

        fun unexpectedEOI(expected: String, erroneousLocation: Span)
            = UnexpectedEndOfInputReporting(erroneousLocation, expected)

        fun unknownType(erroneousRef: TypeReference)
            = UnknownTypeReporting(erroneousRef)

        fun parsingMismatch(expected: String, actual: Token)
            = ParsingMismatchReporting(listOf(expected), actual)

        fun parsingError(message: String, location: Span)
            = ParsingErrorReporting(message, location)

        fun unsupported(message: String, location: Span)
            = UnsupportedFeatureReporting(message, location)

        fun valueNotAssignable(targetType: BoundTypeReference, sourceType: BoundTypeReference, reason: String, assignmentLocation: Span)
            = ValueNotAssignableReporting(targetType, sourceType, reason, assignmentLocation)

        fun undefinedIdentifier(expr: IdentifierToken, messageOverride: String? = null)
            = UndefinedIdentifierReporting(expr, messageOverride)

        /**
         * @param acceptedDeclaration The declaration that is accepted by the compiler as the first / actual one
         * @param additionalDeclaration The erroneous declaration; is rejected (instead of accepted)
         */
        fun variableDeclaredMoreThanOnce(acceptedDeclaration: VariableDeclaration, additionalDeclaration: VariableDeclaration)
            = MultipleVariableDeclarationsReporting(acceptedDeclaration, additionalDeclaration)

        fun globalVariableNotInitialized(variable: BoundVariable)
            = GlobalVariableNotInitializedReporting(variable.declaration)

        fun useOfUninitializedVariable(variable: BoundVariable, access: BoundIdentifierExpression, maybeInitialized: Boolean)
            = VariableAccessedBeforeInitializationReporting(variable.declaration, access.declaration, maybeInitialized)

        fun parameterDeclaredMoreThanOnce(firstDeclaration: VariableDeclaration, additionalDeclaration: VariableDeclaration)
            = MultipleParameterDeclarationsReporting(firstDeclaration, additionalDeclaration)

        fun variableTypeNotDeclared(variable: BoundVariable)
            = MissingVariableTypeReporting(variable.declaration, variable.kind)

        fun varianceOnFunctionTypeParameter(parameter: BoundTypeParameter)
            = VarianceOnFunctionTypeParameterReporting(parameter.astNode)

        fun varianceOnInvocationTypeArgument(argument: BoundTypeArgument)
            = VarianceOnInvocationTypeArgumentReporting(argument.astNode)

        fun missingTypeArgument(parameter: BoundTypeParameter, span: Span)
            = MissingTypeArgumentReporting(parameter.astNode, span)

        fun superfluousTypeArguments(nExpectedArguments: Int, firstSuperfluousArgument: BoundTypeArgument)
            = SuperfluousTypeArgumentsReporting(nExpectedArguments, firstSuperfluousArgument)

        fun typeArgumentVarianceMismatch(parameter: BoundTypeParameter, argument: BoundTypeArgument)
            = TypeArgumentVarianceMismatchReporting(parameter.astNode, argument)

        fun typeArgumentVarianceSuperfluous(argument: BoundTypeArgument)
            = TypeArgumentVarianceSuperfluousReporting(argument)

        fun typeArgumentOutOfBounds(parameter: BoundTypeParameter, argument: BoundTypeReference, reason: String)
            = TypeArgumentOutOfBoundsReporting(parameter.astNode, argument, reason)

        fun unsupportedTypeUsageVariance(useSite: TypeUseSite, erroneousVariance: TypeVariance)
            = UnsupportedTypeUsageVarianceReporting(useSite, erroneousVariance)

        fun erroneousLiteralExpression(message: String, location: Span)
            = ErroneousLiteralExpressionReporting(message, location)

        fun illegalAssignment(message: String, assignmentStatement: BoundAssignmentStatement)
            = IllegalAssignmentReporting(message, assignmentStatement)

        fun illegalFunctionBody(function: FunctionDeclaration)
            = IllegalFunctionBodyReporting(function)

        fun missingFunctionBody(function: FunctionDeclaration)
            = MissingFunctionBodyReporting(function)

        fun inefficientAttributes(message: String, attributes: Collection<AstFunctionAttribute>)
            = ModifierInefficiencyReporting(message, attributes)

        fun conflictingModifiers(attributes: Collection<AstFunctionAttribute>)
            = ConflictingFunctionModifiersReporting(attributes)

        fun functionIsMissingDeclaredAttribute(fn: BoundDeclaredFunction, missingAttribute: AstFunctionAttribute, reason: String)
            = FunctionMissingDeclaredModifierReporting(fn.declaration, missingAttribute, reason)

        fun toplevelFunctionWithOverrideAttribute(attr: AstFunctionAttribute.Override)
            = ToplevelFunctionWithOverrideAttributeReporting(attr.attributeName)

        fun unsupportedCallingConvention(attr: AstFunctionAttribute.External, supportedConventions: Set<String>)
            = UnsupportedCallingConventionReporting(attr, supportedConventions)

        fun unconventionalTypeName(name: IdentifierToken, convention: UnconventionalTypeNameReporting.ViolatedConvention)
            = UnconventionalTypeNameReporting(name, convention)

        fun illegalSupertype(ref: TypeReference, reason: String)
            = IllegalSupertypeReporting(ref, reason)

        fun duplicateSupertype(ref: TypeReference)
            = DuplicateSupertypeReporting(ref)

        fun cyclicInheritance(type: BoundBaseType, involvingSupertype: BoundSupertypeDeclaration)
            = CyclicInheritanceReporting(type.declaration, involvingSupertype)

        fun duplicateBaseTypes(packageName: CanonicalElementName.Package, duplicates: List<BoundBaseType>)
            = DuplicateBaseTypesReporting(packageName, duplicates.map { it.declaration })

        fun functionDoesNotOverride(function: BoundDeclaredFunction)
            = SuperFunctionForOverrideNotFoundReporting(function.declaration)

        fun undeclaredOverride(function: BoundDeclaredFunction, onSupertype: BoundBaseType)
            = UndeclaredOverrideReporting(function.declaration, onSupertype)

        fun staticFunctionDeclaredOverride(function: BoundDeclaredFunction)
            = StaticFunctionDeclaredOverrideReporting(function.attributes.firstOverrideAttribute!!)

        fun abstractInheritedFunctionNotImplemented(implementingType: BoundBaseType, functionToImplement: BoundMemberFunction)
            = AbstractInheritedFunctionNotImplementedReporting(implementingType, functionToImplement)

        fun noMatchingFunctionOverload(functionNameReference: IdentifierToken, receiverType: BoundTypeReference?, valueArguments: List<BoundExpression<*>>, functionDeclaredAtAll: Boolean)
            = UnresolvableFunctionOverloadReporting(functionNameReference, receiverType, valueArguments.map { it.type }, functionDeclaredAtAll)

        fun overloadSetHasNoDisjointParameter(overloadSet: BoundOverloadSet<*>): Reporting {
            val baseReporting = OverloadSetHasNoDisjointParameterReporting(overloadSet)

            // hypothesis / edge case to find: the problem is created by (multiple) inheritance _only_
            val allMemberFns = overloadSet.overloads.filterIsInstance<BoundMemberFunction>()
            if (allMemberFns.size < overloadSet.overloads.size) {
                // there are top-level functions at play
                return baseReporting
            }

            val sampleMemberFn = allMemberFns.first()
            val subtype = (sampleMemberFn as? InheritedBoundMemberFunction)?.subtype ?: sampleMemberFn.declaredOnType

            val onlyInheritedOverloads = allMemberFns.filterIsInstance<InheritedBoundMemberFunction>()
            if (onlyInheritedOverloads.isEmpty() || BoundOverloadSet.areOverloadsDisjoint(onlyInheritedOverloads)) {
                // the member functions declared in the subtype clearly have an effect on the ambiguity of the overload-set
                return baseReporting
            }

            val overloadsImportedBySupertype: MutableMap<BoundBaseType?, List<BoundMemberFunction>> = allMemberFns
                .flatMap { subtypeMemberFn ->
                    if (subtypeMemberFn is InheritedBoundMemberFunction) {
                        setOf(subtypeMemberFn)
                    } else {
                        subtypeMemberFn.overrides ?: emptySet()
                    }

                }
                .groupBy { it.declaredOnType }
                .toMutableMap()
            overloadsImportedBySupertype.remove(null)
            @Suppress("UNCHECKED_CAST") // the remove(null) right before ensures exactly that
            overloadsImportedBySupertype as MutableMap<BoundBaseType, List<BoundMemberFunction>>

            val someSupertypeHasAmbiguity = overloadsImportedBySupertype.values.any {
                !BoundOverloadSet.areOverloadsDisjoint(it)
            }
            if (someSupertypeHasAmbiguity) {
                // the problem was inherited from one of the supertypes -> ignore, gets reported by the supertype
                return consecutive("overload disjoint problem inherited into ${subtype.canonicalName} from a supertype", baseReporting.span)
            }

            // the problem is created by multiple inheritance; find the subset of supertypes that creates the problem
            val supertypeOverloadsIterator = overloadsImportedBySupertype.iterator()
            while (supertypeOverloadsIterator.hasNext()) {
                val (supertype, _) = supertypeOverloadsIterator.next()
                val problemExistsWithoutThisSupertype = !BoundOverloadSet.areOverloadsDisjoint(
                    overloadsImportedBySupertype.entries.asSequence()
                        .filter { (k, _) -> k === supertype }
                        .map { (_, overloads) -> overloads }
                        .flatMap { it }
                )
                if (problemExistsWithoutThisSupertype) {
                    supertypeOverloadsIterator.remove()
                }
            }

            check(overloadsImportedBySupertype.isNotEmpty()) {
                "This means that the problem is indeed created by member functions in the subtype. But that should have been caught earlier! ${subtype.canonicalName}"
            }
            return MultipleInheritanceIssueReporting(
                baseReporting,
                overloadsImportedBySupertype.keys,
                subtype,
            )
        }

        fun unresolvableConstructor(nameToken: IdentifierToken, valueArguments: List<BoundExpression<*>>, functionsWithNameAvailable: Boolean)
            = UnresolvableConstructorReporting(nameToken, valueArguments.map { it.type }, functionsWithNameAvailable)

        fun unresolvableMemberVariable(accessExpression: BoundMemberAccessExpression, hostType: BoundTypeReference)
            = UnresolvedMemberVariableReporting(accessExpression.declaration, hostType)

        fun unresolvablePackageName(name: CanonicalElementName.Package, location: Span)
            = UnresolvablePackageNameReporting(name, location)

        fun unresolvableImport(import: BoundImportDeclaration)
            = UnresolvableImportReporting(import)

        fun ambiguousImports(imports: Iterable<BoundImportDeclaration>)
            = AmbiguousImportsReporting(imports.map { it.declaration }, imports.first().simpleName!!)

        fun implicitlyEvaluatingAStatement(statement: BoundStatement<*>)
            = ImplicitlyEvaluatedStatementReporting(statement.declaration)

        fun ambiguousInvocation(invocation: BoundInvocationExpression, candidates: List<BoundFunction>)
            = AmbiguousInvocationReporting(invocation.declaration, candidates)

        fun typeDeductionError(message: String, location: Span)
            = TypeDeductionErrorReporting(message, location)

        fun explicitInferTypeWithArguments(type: TypeReference)
            = ExplicitInferTypeWithArgumentsReporting(type)

        fun explicitInferTypeNotAllowed(type: TypeReference)
            = ExplicitInferTypeNotAllowedReporting(type)

        fun modifierError(message: String, location: Span)
            = ModifierErrorReporting(message, location)

        fun operatorNotDeclared(message: String, expression: Expression)
            = OperatorNotDeclaredReporting(message, expression)

        fun functionIsMissingAttribute(function: BoundFunction, usageRequiringModifier: Executable, missingAttribute: String)
            = FunctionMissingModifierReporting(function, usageRequiringModifier, missingAttribute)

        fun externalMemberFunction(function: BoundDeclaredFunction)
            = ExternalMemberFunctionReporting(function.declaration, function.attributes.externalAttribute!!.attributeName)

        fun objectNotFullyInitialized(uninitializedMembers: Collection<BoundBaseTypeMemberVariable>, usedAt: Span): ObjectNotFullyInitializedReporting {
            return ObjectNotFullyInitializedReporting(
                uninitializedMembers.map { it.declaration },
                usedAt
            )
        }

        fun useOfUninitializedMember(access: BoundMemberAccessExpression) = UseOfUninitializedClassMemberVariableReporting(
            access.member!!.declaration,
            access.declaration.memberName.span,
        )

        fun constructorDeclaredAsModifying(constructor: BoundClassConstructor) = ConstructorDeclaredModifyingReporting(
            constructor,
            constructor.attributes.firstModifyingAttribute!!.attributeName,
        )

        fun explicitOwnershipNotAllowed(variable: BoundVariable)
            = ExplicitOwnershipNotAllowedReporting(variable.declaration.ownership!!.second)

        fun variableUsedAfterLifetime(variable: BoundVariable, read: BoundIdentifierExpression, deadState: VariableLifetime.State.Dead)
            = VariableUsedAfterLifetimeReporting(variable.declaration, read.declaration.span, deadState.lifetimeEndedAt, deadState.maybe)

        fun borrowedVariableCaptured(variable: BoundVariable, capture: BoundIdentifierExpression)
            = BorrowedVariableCapturedReporting(variable.declaration, capture.declaration.span)

        /**
         * An expression is used in a way that requires it to be non-null but the type of the expression is nullable.
         * @param nullableExpression The expression that could evaluate to null and thus case an NPE
         * @see BoundTypeReference.isExplicitlyNullable
         */
        fun unsafeObjectTraversal(nullableExpression: BoundExpression<*>, faultyAccessOperator: OperatorToken)
            = UnsafeObjectTraversalReporting(nullableExpression, faultyAccessOperator)

        fun superfluousSafeObjectTraversal(nonNullExpression: BoundExpression<*>, superfluousSafeOperator: OperatorToken)
            = SuperfluousSafeObjectTraversal(nonNullExpression, superfluousSafeOperator)

        fun overrideAddsSideEffects(override: BoundMemberFunction, superFunction: BoundMemberFunction)
            = OverrideAddsSideEffectsReporting(override, superFunction)

        fun nothrowViolatingInvocation(invocation: BoundInvocationExpression, boundary: NothrowViolationReporting.SideEffectBoundary)
            = NothrowViolationReporting.ThrowingInvocation(invocation, boundary)

        fun nothrowViolatingNotNullAssertion(assertion: BoundNotNullExpression, boundary: NothrowViolationReporting.SideEffectBoundary)
            = NothrowViolationReporting.NotNullAssertion(assertion.declaration, boundary)

        fun droppingReferenceToObjectWithThrowingConstructor(reference: BoundVariable, boundary: NothrowViolationReporting.SideEffectBoundary)
            = NothrowViolationReporting.PotentialThrowingDestruction(reference, "variable ${reference.name}", reference.declaration.span, boundary)

        fun droppingReferenceToObjectWithThrowingConstructor(reference: BoundBaseTypeMemberVariable, ownerType: BoundBaseType, boundary: NothrowViolationReporting.SideEffectBoundary)
            = NothrowViolationReporting.ObjectMemberWithThrowingDestructor(reference, ownerType, boundary)

        fun droppingReferenceToObjectWithThrowingConstructor(reference: BoundAssignmentStatement, boundary: NothrowViolationReporting.SideEffectBoundary)
            = NothrowViolationReporting.PotentialThrowingDestruction(reference, "the previous value", reference.declaration.span, boundary)

        fun droppingReferenceToObjectWithThrowingConstructor(returnValueOf: BoundInvocationExpression, boundary: NothrowViolationReporting.SideEffectBoundary)
            = NothrowViolationReporting.PotentialThrowingDestruction(
                returnValueOf,
                "return value of ${returnValueOf.functionToInvoke?.name ?: returnValueOf.functionNameToken.value}",
                returnValueOf.declaration.span,
                boundary,
            )

        fun throwStatementInNothrowContext(statement: BoundThrowStatement, boundary: NothrowViolationReporting.SideEffectBoundary)
            = NothrowViolationReporting.ThrowStatement(statement.declaration, boundary)

        fun constructorDeclaredNothrow(constructor: BoundClassConstructor)
            = ConstructorDeclaredNothrowReporting(constructor.attributes.firstNothrowAttribute!!.sourceLocation)

        fun breakOutsideOfLoop(breakStatement: BoundBreakStatement)
            = BreakOutsideOfLoopReporting(breakStatement.declaration)

        fun continueOutsideOfLoop(continueStatement: BoundContinueStatement)
            = ContinueOutsideOfLoopReporting(continueStatement.declaration)

        fun purityViolations(readingViolations: Collection<BoundExpression<*>>, writingViolations: Collection<BoundStatement<*>>, context: BoundFunction): Collection<Reporting> {
            val boundary = PurityViolationReporting.SideEffectBoundary.Function(context)
            val readingReportings = readingViolations.map { readingPurityViolationToReporting(it, boundary) }
            val writingReportings = writingViolations.asSequence()
                .filter { it !in readingViolations }
                .map { modifyingPurityViolationToReporting(it, boundary) }

            return readingReportings + writingReportings
        }

        fun purityViolations(readingViolations: Collection<BoundExpression<*>>, writingViolations: Collection<BoundStatement<*>>, context: BoundBaseTypeMemberVariable): Collection<Reporting> {
            val boundary = PurityViolationReporting.SideEffectBoundary.ClassMemberInitializer(context)
            val readingReportings = readingViolations.map { readingPurityViolationToReporting(it, boundary) }
            val writingReportings = writingViolations.asSequence()
                .filter { it !in readingViolations }
                .map { modifyingPurityViolationToReporting(it, boundary) }

            return readingReportings + writingReportings
        }

        fun readonlyViolations(writingViolations: Collection<BoundStatement<*>>, readonlyFunction: BoundFunction): Collection<Reporting> {
            return purityViolations(emptySet(), writingViolations, readonlyFunction)
        }

        fun missingReturnValue(returnStatement: BoundReturnStatement, expectedType: BoundTypeReference) = MissingReturnValueReporting(
            returnStatement.declaration,
            expectedType,
        )

        fun uncertainTermination(function: BoundDeclaredFunction) =
            UncertainTerminationReporting(function)

        fun conditionIsNotBoolean(condition: BoundExpression<*>): Reporting {
            val location = condition.declaration.span
            if (condition.type == null) {
                return consecutive("The condition must evaluate to Bool, cannot determine type", location)
            }

            return ConditionNotBooleanReporting(condition, location)
        }

        fun typeParameterNameConflict(originalType: BoundTypeReference, conflicting: BoundTypeParameter)
            = TypeParameterNameConflictReporting(originalType, conflicting)

        fun duplicateBaseTypeMembers(typeDef: BoundBaseType, duplicateMembers: Set<BoundBaseTypeMemberVariable>) =
            DuplicateBaseTypeMemberReporting(typeDef, duplicateMembers)

        fun assignmentUsedAsExpression(assignment: BoundAssignmentStatement)
            = AssignmenUsedAsExpressionReporting(assignment.declaration)

        fun mutationInCondition(mutation: BoundExecutable<*>)
            = MutationInConditionReporting(mutation.declaration)

        fun incorrectPackageDeclaration(name: AstPackageName, expected: CanonicalElementName.Package)
            = IncorrectPackageDeclarationReporting(name, expected)

        fun integerLiteralOutOfRange(literal: Expression, expectedType: BoundBaseType, expectedRange: ClosedRange<BigInteger>)
            = IntegerLiteralOutOfRangeReporting(literal, expectedType, expectedRange)

        fun multipleClassConstructors(additionalCtors: Collection<BaseTypeConstructorDeclaration>)
            = MultipleClassConstructorsReporting(additionalCtors)

        fun multipleClassDestructors(additionalDtors: Collection<BaseTypeDestructorDeclaration>)
            = MultipleClassDestructorsReporting(additionalDtors)

        fun entryNotAllowedOnBaseType(baseType: BoundBaseType, entry: BoundBaseTypeEntry<*>)
            = EntryNotAllowedInBaseTypeReporting(baseType.kind, entry)

        fun elementNotAccessible(element: DefinitionWithVisibility, visibility: BoundVisibility, accessAt: Span)
            = ElementNotAccessibleReporting(element, visibility, accessAt)

        fun visibilityTooBroad(owningModule: CanonicalElementName.Package, visibilityDeclaration: BoundVisibility.PackageScope)
            = PackageVisibilityTooBroadReporting(owningModule, visibilityDeclaration.packageName, visibilityDeclaration.astNode.sourceLocation)

        fun visibilityNotAllowedOnVariable(variable: BoundVariable)
            = VisibilityNotAllowedOnVariableReporting(variable)

        fun visibilityShadowed(element: DefinitionWithVisibility, contextVisibility: BoundVisibility)
            = ShadowedVisibilityReporting(element, contextVisibility)

        fun hiddenTypeExposed(type: BoundBaseType, exposedBy: DefinitionWithVisibility, exposedAt: Span)
            = HiddenTypeExposedReporting(type, exposedBy, exposedAt)

        fun forbiddenCast(castOp: BoundCastExpression, reason: String, span: Span = castOp.declaration.span)
            = ForbiddenCastReporting(castOp.declaration, reason, span)

        private fun readingPurityViolationToReporting(violation: BoundExpression<*>, boundary: PurityViolationReporting.SideEffectBoundary): Reporting {
            if (violation is BoundIdentifierExpression) {
                return ReadInPureContextReporting(violation, boundary)
            }
            check(violation is BoundInvocationExpression)
            return ImpureInvocationInPureContextReporting(violation, boundary)
        }

        private fun modifyingPurityViolationToReporting(violation: BoundStatement<*>, boundary: PurityViolationReporting.SideEffectBoundary): Reporting {
            if (violation is BoundAssignmentStatement) {
                return StateModificationOutsideOfPurityBoundaryReporting(violation, boundary)
            }

            check(violation is BoundInvocationExpression)
            if (violation.functionToInvoke?.purity?.contains(BoundFunction.Purity.MODIFYING) == true) {
                return ModifyingInvocationInReadonlyContextReporting(violation, boundary)
            } else {
                return ImpureInvocationInPureContextReporting(violation, boundary)
            }
        }
    }
}