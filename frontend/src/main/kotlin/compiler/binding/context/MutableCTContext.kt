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

package compiler.binding.context

import compiler.ast.FunctionDeclaration
import compiler.ast.ImportDeclaration
import compiler.ast.classdef.ClassDeclaration
import compiler.ast.type.TypeArgument
import compiler.ast.type.TypeParameter
import compiler.ast.type.TypeReference
import compiler.binding.BoundFunction
import compiler.binding.BoundImportDeclaration
import compiler.binding.BoundOverloadSet
import compiler.binding.BoundVariable
import compiler.binding.classdef.BoundClassDefinition
import compiler.binding.type.BaseType
import compiler.binding.type.BoundTypeArgument
import compiler.binding.type.BoundTypeParameter
import compiler.binding.type.BoundTypeReference
import compiler.binding.type.GenericTypeReference
import compiler.binding.type.RootResolvedTypeReference
import compiler.binding.type.UnresolvedType

/**
 * Mutable compile-time context; for explanation, see the doc of [CTContext].
 */
open class MutableCTContext(
    /**
     * The context this one is derived off of
     */
    private val parentContext: CTContext,
) : CTContext {
    private val _imports: MutableSet<BoundImportDeclaration> = HashSet()
    override val imports: Iterable<BoundImportDeclaration> = _imports

    private var _moduleContext: ModuleContext? = null
    override var moduleContext: ModuleContext
        get() = _moduleContext ?: parentContext.moduleContext
        set(value) {
            _moduleContext = value
        }

    private var _sourceFile: SourceFile? = null
    override var sourceFile
        get() = _sourceFile ?: parentContext.sourceFile
        set(value) {
            _sourceFile = value
        }

    /** Maps variable names to their metadata; holds only variables defined in this context */
    protected val _variables: MutableMap<String, BoundVariable> = HashMap()

    /** Holds all the toplevel functions defined in this context */
    protected val _functions: MutableSet<BoundFunction> = HashSet()

    /** Holds all the classes defined in this context */
    protected val _classes: MutableSet<BoundClassDefinition> = HashSet()

    /** Holds all the base types defined in this context */
    protected val _types: MutableSet<BaseType> = HashSet()

    fun addImport(decl: ImportDeclaration) {
        this._imports.add(decl.bindTo(this))
    }

    /**
     * Adds the given [BaseType] to this context, possibly overriding
     */
    open fun addBaseType(type: BaseType) {
        _types.add(type)
        if (type is BoundClassDefinition) _classes.add(type)
    }

    open fun addClass(definition: ClassDeclaration): BoundClassDefinition {
        val bound = definition.bindTo(this)
        _types.add(bound)
        _classes.add(bound)
        return bound
    }

    private val typeParameters = HashMap<String, BoundTypeParameter>()

    open fun addTypeParameter(parameter: TypeParameter): BoundTypeParameter {
        check(parameter.name.value !in typeParameters) {
            "Duplicate type parameter in context: $parameter"
        }
        val bound = parameter.bindTo(this)
        typeParameters[parameter.name.value] = bound
        return bound
    }

    override fun resolveTypeParameter(simpleName: String): BoundTypeParameter? {
        return typeParameters[simpleName] ?: parentContext.resolveTypeParameter(simpleName)
    }

    override fun resolveBaseType(simpleName: String, fromOwnFileOnly: Boolean): BaseType? {
        _types.find { it.simpleName == simpleName }?.let { return it }

        val fromImport = if (fromOwnFileOnly) null else {
            _imports
                .asSequence()
                .mapNotNull { it.getBaseTypeOfName(simpleName) }
                .firstOrNull()
        }

        return fromImport ?: parentContext.resolveBaseType(simpleName, fromOwnFileOnly)
    }

    override fun resolveType(ref: TypeArgument): BoundTypeArgument {
        return BoundTypeArgument(ref, ref.variance, resolveType(ref.type))
    }

    override fun resolveType(ref: TypeReference, fromOwnFileOnly: Boolean): BoundTypeReference {
        resolveTypeParameter(ref.simpleName)?.let { parameter ->
            return GenericTypeReference(ref, parameter)
        }

        val resolvedParameters = ref.arguments.map { resolveType(it).defaultMutabilityTo(ref.mutability) }
        return resolveBaseType(ref.simpleName)
            ?.let { RootResolvedTypeReference(ref, it, resolvedParameters) }
            ?: UnresolvedType(ref, resolvedParameters)
    }

    open fun addFunction(declaration: FunctionDeclaration): BoundFunction {
        val bound = declaration.bindTo(this)
        _functions.add(bound)
        return bound
    }

    override fun getToplevelFunctionOverloadSetsBySimpleName(name: String): Collection<BoundOverloadSet> {
        val imported = _imports.flatMap { it.getOverloadSetsBySimpleName(name) }
        return imported + parentContext.getToplevelFunctionOverloadSetsBySimpleName(name)
    }
}