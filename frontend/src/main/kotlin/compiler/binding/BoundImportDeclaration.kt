package compiler.binding

import compiler.ast.ImportDeclaration
import compiler.binding.basetype.BoundBaseType
import compiler.binding.context.CTContext
import compiler.binding.context.PackageContext
import compiler.reportings.Reporting
import io.github.tmarsteel.emerge.common.CanonicalElementName

class BoundImportDeclaration(
    val context: CTContext,
    val declaration: ImportDeclaration,
) : SemanticallyAnalyzable {
    val packageName = CanonicalElementName.Package(declaration.identifiers.dropLast(1).map { it.value })
    private val simpleNameRaw = declaration.identifiers.last().value
    val isImportAll: Boolean = simpleNameRaw == "*"
    val simpleName: String? = simpleNameRaw.takeUnless { isImportAll }

    private val resolutionResult: ResolutionResult by lazy {
        val packageContext = context.swCtx.getPackage(packageName)
            ?: return@lazy ResolutionResult.Erroneous(Reporting.unresolvablePackageName(packageName, declaration.declaredAt))

        if (isImportAll) {
            return@lazy ResolutionResult.EntirePackage(packageContext)
        }

        val overloadSets = packageContext.getTopLevelFunctionOverloadSetsBySimpleName(simpleNameRaw)
        if (overloadSets.isNotEmpty()) {
            return@lazy ResolutionResult.OverloadSets(simpleNameRaw, overloadSets)
        }

        val baseType = packageContext.resolveBaseType(simpleNameRaw)
        if (baseType != null) {
            return@lazy ResolutionResult.BaseType(baseType)
        }

        val variable = packageContext.resolveVariable(simpleNameRaw)
        if (variable != null) {
            return@lazy ResolutionResult.Variable(variable)
        }

        return@lazy ResolutionResult.Erroneous(Reporting.unresolvableImport(this))
    }

    fun getOverloadSetsBySimpleName(simpleName: String): Collection<BoundOverloadSet<*>> = when(val result = resolutionResult) {
        is ResolutionResult.EntirePackage -> result.packageContext.getTopLevelFunctionOverloadSetsBySimpleName(simpleName)
        is ResolutionResult.OverloadSets -> if (result.simpleName == simpleName) result.sets else emptySet()
        is ResolutionResult.BaseType,
        is ResolutionResult.Variable,
        is ResolutionResult.Erroneous -> emptySet()
    }

    fun getBaseTypeOfName(simpleName: String): BoundBaseType? = when(val result = resolutionResult) {
        is ResolutionResult.EntirePackage -> result.packageContext.resolveBaseType(simpleName)
        is ResolutionResult.BaseType -> result.baseType.takeIf { it.simpleName == simpleName }
        is ResolutionResult.OverloadSets,
        is ResolutionResult.Variable,
        is ResolutionResult.Erroneous -> null
    }

    fun getVariableOfName(simpleName: String): BoundVariable? = when(val result = resolutionResult) {
        is ResolutionResult.EntirePackage -> result.packageContext.resolveVariable(simpleName)
        is ResolutionResult.Variable -> result.variable.takeIf { it.name == simpleName }
        is ResolutionResult.BaseType,
        is ResolutionResult.OverloadSets,
        is ResolutionResult.Erroneous -> null
    }

    override fun semanticAnalysisPhase1(): Collection<Reporting> {
        return (resolutionResult as? ResolutionResult.Erroneous)?.errors ?: emptySet()
    }

    override fun semanticAnalysisPhase2(): Collection<Reporting> = emptySet()

    override fun semanticAnalysisPhase3(): Collection<Reporting> {
        val lastIdentifierAt = declaration.identifiers.last().span
        val reportings = mutableListOf<Reporting>()
        when (val result = resolutionResult) {
            is ResolutionResult.Variable -> {
                if (result.variable.kind.allowsVisibility) {
                    reportings.addAll(result.variable.visibility.validateAccessFrom(lastIdentifierAt, result.variable))
                }
            }
            is ResolutionResult.OverloadSets -> {
                val allImplsSeq = result.sets.asSequence().flatMap { it.overloads }
                val noneAccessible = allImplsSeq.none { it.attributes.visibility.validateAccessFrom(lastIdentifierAt, it).isEmpty() }

                if (noneAccessible) {
                    val sampleReportings = allImplsSeq
                        .map { it.validateAccessFrom(lastIdentifierAt) }
                        .filter { it.isNotEmpty() }
                        .first()
                    reportings.addAll(sampleReportings)
                }
            }
            is ResolutionResult.BaseType -> {
                reportings.addAll(result.baseType.validateAccessFrom(lastIdentifierAt))
            }
            else -> { /* nothing to do */ }
        }
        return reportings
    }

    private sealed interface ResolutionResult {
        class EntirePackage(val packageContext: PackageContext) : ResolutionResult
        class OverloadSets(val simpleName: String, val sets: Collection<BoundOverloadSet<*>>) : ResolutionResult
        class BaseType(val baseType: BoundBaseType) : ResolutionResult
        class Variable(val variable: BoundVariable) : ResolutionResult
        class Erroneous(error: Reporting) : ResolutionResult {
            val errors = setOf(error)
        }
    }
}