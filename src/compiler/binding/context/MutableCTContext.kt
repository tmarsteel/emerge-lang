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

import compiler.InternalCompilerError
import compiler.ast.Bindable
import compiler.ast.FunctionDeclaration
import compiler.ast.ImportDeclaration
import compiler.ast.type.TypeReference
import compiler.binding.BoundFunction
import compiler.binding.BoundVariable
import compiler.binding.struct.Struct
import compiler.binding.type.BaseType
import compiler.binding.type.ResolvedTypeReference
import compiler.lexer.IdentifierToken
import java.util.*

/**
 * Mutable compile-time context; for explanation, see the doc of [CTContext].
 */
open class MutableCTContext(
    /**
     * The context this one is derived off of
     */
    private val parentContext: CTContext,
) : CTContext {
    private val imports: MutableSet<ImportDeclaration> = HashSet()

    private var _swCtx: SoftwareContext? = null
    override var swCtx: SoftwareContext
        get() = _swCtx ?: parentContext.swCtx
        set(value) {
            _swCtx = value
        }

    private var _module: Module? = null
    override var module
        get() = _module ?: parentContext.module
        set(value) {
            _module = value
        }

    private val hierarchy: Sequence<CTContext> = generateSequence(this as CTContext) { (it as? MutableCTContext)?.parentContext }

    /** Maps variable names to their metadata; holds only variables defined in this context */
    protected val _variables: MutableMap<String, BoundVariable> = HashMap()

    /** Holds all the toplevel functions defined in this context */
    protected val _functions: MutableSet<BoundFunction> = HashSet()

    /** Holds all the toplevel structs defined in this context */
    protected val _structs: MutableSet<Struct> = HashSet()

    /** Holds all the base types defined in this context */
    protected val _types: MutableSet<BaseType> = HashSet()

    fun addImport(decl: ImportDeclaration) {
        this.imports.add(decl)
    }

    /**
     * Adds the given [BaseType] to this context, possibly overriding
     */
    open fun addBaseType(type: BaseType) {
        _types.add(type)
        if (type is Struct) _structs.add(type)
    }

    open fun addStruct(definition: Struct) {
        _types.add(definition)
        _structs.add(definition)
    }

    override fun resolveType(ref: TypeReference, fromOwnModuleOnly: Boolean): BaseType? {
        _types.find { it.simpleName == ref.simpleName }?.let { return it }

        val fromImport = if (fromOwnModuleOnly) null else {
            val importedTypes = importsForSimpleName(ref.simpleName)
                .map { it.resolveType(ref, fromOwnModuleOnly = true) }
                .filterNotNull()

            // TODO: if importedTypes.size is > 1 the reference is ambiguous; how to handle that?
            importedTypes.firstOrNull()
        }

        return fromImport ?: parentContext.resolveType(ref, fromOwnModuleOnly)
    }

    override fun resolveVariable(name: String, fromOwnModuleOnly: Boolean): BoundVariable? {
        _variables[name]?.let { return it }

        val fromImport = if (fromOwnModuleOnly) null else {
            val importedVars = importsForSimpleName(name)
                .map { it.resolveVariable(name, fromOwnModuleOnly = true) }
                .filterNotNull()

            // TODO: if importedVars.size is > 1 the name is ambigous; how to handle that?
            importedVars.firstOrNull()
        }

        return fromImport ?: parentContext.resolveVariable(name, fromOwnModuleOnly)
    }

    override fun containsWithinBoundary(variable: BoundVariable, boundary: CTContext): Boolean {
        if (_variables.containsValue(variable)) return true

        if (this === boundary) {
            return false
        }

        return parentContext.containsWithinBoundary(variable, boundary)
    }

    open fun addFunction(declaration: FunctionDeclaration): BoundFunction {
        val bound = declaration.bindTo(this)
        _functions.add(bound)
        return bound
    }

    /**
     * Adds the given variable to this context; possibly overriding its type with the given type.
     */
    open fun addVariable(declaration: Bindable<BoundVariable>): BoundVariable {
        val bound = declaration.bindTo(this)

        return addVariable(bound)
    }

    open fun addVariable(boundVariable: BoundVariable): BoundVariable {
        if (boundVariable.context in hierarchy) {
            _variables[boundVariable.name] = boundVariable
            return boundVariable
        }

        throw InternalCompilerError("Cannot add a variable that has been bound to a different context")
    }

    override fun resolveFunction(name: String, fromOwnModuleOnly: Boolean): Collection<BoundFunction> {
        // try to resolve from this context
        val selfDefined = _functions.filter { it.name == name }

        // look through the imports
        val importedTypes = if (fromOwnModuleOnly) emptySet() else importsForSimpleName(name).map { it.resolveFunction(name, fromOwnModuleOnly = true) }

        // TODO: if importedTypes.size is > 1 the reference is ambiguous; how to handle that?
        return selfDefined + (importedTypes.firstOrNull() ?: emptySet()) + parentContext.resolveFunction(name, fromOwnModuleOnly)
    }

    /**
     * @return All the imported contexts that could contain the given simple name.
     */
    private fun importsForSimpleName(simpleName: String): Iterable<CTContext> {
        return imports.map { import ->
            val importRange = import.identifiers.map(IdentifierToken::value)
            val moduleName = importRange.dropLast(1)
            val importSimpleName = importRange.last()

            if (importSimpleName != "*" && importSimpleName != simpleName) {
                return@map null
            }

            return@map swCtx.module(moduleName)?.context
        }
            .filterNotNull()
    }
}