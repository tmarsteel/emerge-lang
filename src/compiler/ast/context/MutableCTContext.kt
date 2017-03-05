package compiler.ast.context

import compiler.InternalCompilerError
import compiler.ast.FunctionDeclaration
import compiler.ast.ImportDeclaration
import compiler.ast.VariableDeclaration
import compiler.ast.type.Any
import compiler.ast.type.BaseType
import compiler.ast.type.TypeReference
import compiler.lexer.IdentifierToken
import java.util.*

/**
 * Mutable compile-time context; for explanation, see the doc of [CTContext].
 */
open class MutableCTContext : CTContext
{
    private val imports: MutableSet<ImportDeclaration> = HashSet()

    /** The [SoftwareContext] the [imports] are resolved from */
    var swCtx: SoftwareContext? = null

    /** Maps variable names to their metadata; holds only variables defined in this context */
    private val variables: MutableMap<String,Variable> = HashMap()

    /** Holds all the toplevel functions defined in this context */
    private val functions: MutableSet<Function> = HashSet()

    /** Holds all the base types defined in this context */
    private val types: MutableSet<BaseType> = HashSet()

    fun addImport(decl: ImportDeclaration) {
        this.imports.add(decl)
    }

    /**
     * Adds the given [BaseType] to this context, possibly overriding
     */
    open fun addBaseType(type: BaseType) {
        types.add(type)
    }

    override fun resolveDefinedType(simpleName: String): BaseType? = types.find { it.simpleName == simpleName }

    override fun resolveAnyType(ref: TypeReference): BaseType? {
        if (ref.declaredName.contains('.')) {
            val swCtx = this.swCtx ?: throw InternalCompilerError("Cannot resolve FQN when no software context is set.")

            // FQN specified
            val fqnName = ref.declaredName.split('.')
            val simpleName = fqnName.last()
            val moduleNameOfType = fqnName.dropLast(1)
            val foreignModuleCtx = swCtx.module(moduleNameOfType)
            return foreignModuleCtx?.context?.resolveDefinedType(simpleName)
        }
        else {
            // try to resolve from this context
            val selfDefined = resolveDefinedType(ref.declaredName)
            if (selfDefined != null) return selfDefined

            // look through the imports
            val importedTypes = importsForSimpleName(ref.declaredName)
                .map { it.resolveDefinedType(ref.declaredName) }
                .filterNotNull()

            // TODO: if importedTypes.size is > 1 the reference is ambigous; how to handle that?
            return importedTypes.firstOrNull()
        }
    }

    /**
     * Adds the given variable to this context; possibly overriding its type with the given type.
     */
    open fun addVariable(declaration: VariableDeclaration, overrideType: TypeReference? = null) {
        variables[declaration.name.value] = Variable(this, declaration, overrideType)
    }

    override fun resolveVariable(name: String, onlyOwn: Boolean): Variable? {
        val ownVar = variables[name]
        if (onlyOwn || ownVar != null) return ownVar

        val importedVars = importsForSimpleName(name)
            .map { it.resolveVariable(name, true) }
            .filterNotNull()

        // TODO: if importedVars.size is > 1 the name is ambigous; how to handle that?
        return importedVars.firstOrNull()
    }

    open fun addFunction(declaration: FunctionDeclaration) {
        functions.add(Function(this, declaration))
    }

    override fun resolveDefinedFunctions(name: String): Collection<Function> = functions.filter { it.declaration.name.value == name }

    override fun resolveAnyFunctions(name: String): Collection<Function> {
        if (name.contains('.')) {
            val swCtx = this.swCtx ?: throw InternalCompilerError("Cannot resolve FQN when no software context is set.")

            // FQN specified
            val fqnName = name.split('.')
            val simpleName = fqnName.last()
            val moduleNameOfType = fqnName.dropLast(1)
            val foreignModuleCtx = swCtx.module(moduleNameOfType)
            return foreignModuleCtx?.context?.resolveDefinedFunctions(simpleName) ?: emptySet()
        }
        else {
            // try to resolve from this context
            val selfDefined = resolveDefinedFunctions(name)

            // look through the imports
            val importedTypes = importsForSimpleName(name)
                .map { it.resolveDefinedFunctions(name) }
                .filterNotNull()

            // TODO: if importedTypes.size is > 1 the reference is ambiguous; how to handle that?
            return selfDefined + (importedTypes.firstOrNull() ?: emptySet())
        }
    }

    /**
     * @return All the imported contexts that could contain the given simple name.
     */
    private fun importsForSimpleName(simpleName: String): Iterable<CTContext> {
        val swCtx = this.swCtx ?: throw InternalCompilerError("Cannot resolve symbol $simpleName including imports when no software context is set.")

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

    // the removed code is not lost of course, thanks to vcs
    // the stuff will be revived from vcs history when the project is actually ready for it
}

private fun <T, R> Iterable<T>.firstNotNull(transform: (T) -> R?): R? = map(transform).filterNot{ it == null }.firstOrNull()

private fun <T, R> Iterable<T>.attachMapNotNull(transform: (T) -> R): Iterable<Pair<T, R>> = map{ it to transform(it) }.filter { it.second != null }