package compiler.binding.context

import compiler.InternalCompilerError
import compiler.ast.Statement
import compiler.ast.type.TypeReference
import compiler.binding.BoundExecutable
import compiler.binding.BoundFunction
import compiler.binding.BoundImportDeclaration
import compiler.binding.BoundOverloadSet
import compiler.binding.BoundVariable
import compiler.binding.BoundVisibility
import compiler.binding.basetype.BoundBaseType
import compiler.binding.basetype.BoundBaseTypeMemberVariable
import compiler.binding.basetype.BoundMixinStatement
import compiler.binding.type.BoundTypeArgument
import compiler.binding.type.BoundTypeParameter
import compiler.binding.type.BoundTypeReference
import compiler.binding.type.UnresolvedType
import compiler.reportings.Diagnosis
import compiler.reportings.Reporting

class SourceFileRootContext(
    packageContext: PackageContext,
) : MutableExecutionScopedCTContext(
    SourceFileParentContext(packageContext),
    true,
    false,
    ExecutionScopedCTContext.Repetition.EXACTLY_ONCE,
) {
    override lateinit var sourceFile: SourceFile

    val variables: Collection<BoundVariable> = _variables.values
    val functions: Collection<BoundFunction> = _functions
    val types: Collection<BoundBaseType> = _types

    override fun addDeferredCode(code: Statement) {
        throw InternalCompilerError("Deferred code on source-file level is currently not possible. Maybe implement as global destructors in the future?")
    }

    override fun getContextLocalDeferredCode(): Sequence<BoundExecutable<*>> {
        throw InternalCompilerError("Deferred code on source-file level is currently not possible. Maybe implement as global destructors in the future?")
    }

    override fun getScopeLocalDeferredCode(): Sequence<BoundExecutable<*>> {
        throw InternalCompilerError("Deferred code on source-file level is currently not possible. Maybe implement as global destructors in the future?")
    }

    override fun getFunctionDeferredCode(): Sequence<BoundExecutable<*>> {
        throw InternalCompilerError("Deferred code on source-file level is currently not possible. Maybe implement as global destructors in the future?")
    }

    private companion object {
        val EMPTY = object : ExecutionScopedCTContext {
            override val swCtx: SoftwareContext
                get() = throw InternalCompilerError("${this::swCtx.name} not initialized yet")

            override val moduleContext: ModuleContext
                get() = throw InternalCompilerError("${this::moduleContext.name} not initialized yet")

            override val sourceFile: SourceFile
                get() = throw InternalCompilerError("file not initialized yet")

            override val visibility: BoundVisibility
                get() = throw InternalCompilerError("Should be implemented on the level of ${SourceFileRootContext::class.qualifiedName}")

            override val isScopeBoundary = false
            override val isFunctionRoot = false
            override val isExceptionHandler = false
            override val hasExceptionHandler = false
            override val repetitionRelativeToParent = ExecutionScopedCTContext.Repetition.EXACTLY_ONCE
            override val imports = emptySet<BoundImportDeclaration>()
            override val allTypeParameters = emptySequence<BoundTypeParameter>()

            override fun resolveVariable(name: String, fromOwnFileOnly: Boolean): BoundVariable? = null
            override fun containsWithinBoundary(variable: BoundVariable, boundary: CTContext): Boolean = false
            override fun resolveTypeParameter(simpleName: String): BoundTypeParameter? = null
            override fun resolveBaseType(simpleName: String, fromOwnFileOnly: Boolean): BoundBaseType? = null
            override fun resolveType(ref: TypeReference, fromOwnFileOnly: Boolean): BoundTypeReference = UnresolvedType(
                this,
                ref,
                ref.arguments?.map { BoundTypeArgument(this, it, it.variance, this.resolveType(it.type)) },
            )
            override fun getToplevelFunctionOverloadSetsBySimpleName(name: String): Collection<BoundOverloadSet<*>> = emptySet()

            override fun getContextLocalDeferredCode(): Sequence<BoundExecutable<*>> {
                throw InternalCompilerError("Should be implemented on the level of ${SourceFileRootContext::class.qualifiedName}")
            }
            override fun getScopeLocalDeferredCode(): Sequence<BoundExecutable<*>> {
                throw InternalCompilerError("Should be implemented on the level of ${SourceFileRootContext::class.qualifiedName}")
            }
            override fun getExceptionHandlingLocalDeferredCode(): Sequence<BoundExecutable<*>> {
                throw InternalCompilerError("Should be implemented on the level of ${SourceFileRootContext::class.qualifiedName}")
            }
            override fun getDeferredCodeForThrow(): Sequence<BoundExecutable<*>> {
                throw InternalCompilerError("Should be implemented on the level of ${SourceFileRootContext::class.qualifiedName}")
            }
            override fun getFunctionDeferredCode(): Sequence<BoundExecutable<*>> {
                throw InternalCompilerError("Should be implemented on the level of ${SourceFileRootContext::class.qualifiedName}")
            }

            override fun getRepetitionBehaviorRelativeTo(indirectParent: CTContext) = ExecutionScopedCTContext.Repetition.EXACTLY_ONCE

            override fun getParentLoop() = null

            override fun registerMixin(
                mixinStatement: BoundMixinStatement,
                diagnosis: Diagnosis
            ): BoundBaseTypeMemberVariable? {
                diagnosis.add(Reporting.mixinNotAllowed(mixinStatement))
                return null
            }
        }
    }

    private class SourceFileParentContext(val packageContext: PackageContext) : ExecutionScopedCTContext by EMPTY {
        override val moduleContext = packageContext.moduleContext

        override val visibility = BoundVisibility.default(this)

        override val sourceFile: SourceFile
            get() = throw InternalCompilerError("shouldn't be accessed")

        override fun resolveVariable(name: String, fromOwnFileOnly: Boolean): BoundVariable? {
            if (fromOwnFileOnly) {
                return EMPTY.resolveVariable(name, fromOwnFileOnly)
            }

            return packageContext.resolveVariable(name)
        }

        override fun resolveBaseType(simpleName: String, fromOwnFileOnly: Boolean): BoundBaseType? {
            if (fromOwnFileOnly) {
                return EMPTY.resolveBaseType(simpleName, fromOwnFileOnly)
            }

            return packageContext.resolveBaseType(simpleName)
        }

        override fun getToplevelFunctionOverloadSetsBySimpleName(name: String): Collection<BoundOverloadSet<*>> {
            return packageContext.getTopLevelFunctionOverloadSetsBySimpleName(name)
        }
    }
}