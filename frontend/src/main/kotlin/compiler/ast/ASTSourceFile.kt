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

package compiler.ast

import compiler.CoreIntrinsicsModule
import compiler.InternalCompilerError
import compiler.StandardLibraryModule
import compiler.binding.BoundVariable
import compiler.binding.context.ModuleContext
import compiler.binding.context.SoftwareContext
import compiler.binding.context.SourceFile
import compiler.binding.context.SourceFileRootContext
import compiler.lexer.IdentifierToken
import compiler.lexer.Span
import compiler.reportings.Reporting
import io.github.tmarsteel.emerge.backend.api.CanonicalElementName
import compiler.lexer.SourceFile as LexerSourceFile

private val DEFAULT_IMPORT_PACKAGES = listOf(
    CoreIntrinsicsModule.NAME,
    StandardLibraryModule.NAME,
)

/**
 * AST representation of a source file
 */
class ASTSourceFile(
    val lexerFile: LexerSourceFile,
) {
    val expectedPackageName = lexerFile.packageName

    var selfDeclaration: ASTPackageDeclaration? = null

    val imports: MutableList<ImportDeclaration> = mutableListOf()

    val variables: MutableList<VariableDeclaration> = mutableListOf()

    val functions: MutableList<FunctionDeclaration> = mutableListOf()

    val baseTypes: MutableList<BaseTypeDeclaration> = mutableListOf()

    private var parseTimeReportings: MutableList<Reporting>? = null
    fun addParseTimeReporting(reporting: Reporting) {
        if (parseTimeReportings == null) {
            parseTimeReportings = ArrayList()
        }
        parseTimeReportings!!.add(reporting)
    }

    /**
     * Works by the same principle as [Bindable.bindTo]; but since binds to a [SoftwareContext] (rather than a
     * [CTContext]) this has its own signature.
     */
    fun bindTo(context: ModuleContext): SourceFile {
        val packageContext = context.softwareContext.getPackage(expectedPackageName)
            ?: throw InternalCompilerError("Cannot bind source file in $expectedPackageName because its module hasn't been registered with the software-context yet.")
        val fileContext = SourceFileRootContext(packageContext)
        val reportings = mutableSetOf<Reporting>()
        parseTimeReportings?.let(reportings::addAll)

        bindImportsInto(fileContext)
        bindFunctionsInto(fileContext)
        bindBaseTypesInto(fileContext)
        bindVariablesInto(fileContext, reportings)

        selfDeclaration?.packageName?.let { declaredPackageName ->
            if (declaredPackageName.names.map { it.value } != expectedPackageName.components) {
                reportings.add(Reporting.incorrectPackageDeclaration(declaredPackageName, expectedPackageName))
            }
        }

        return SourceFile(
            lexerFile,
            selfDeclaration?.packageName?.names?.map(IdentifierToken::value)?.let(CanonicalElementName::Package) ?: expectedPackageName,
            fileContext,
            reportings
        )
    }

    private fun bindImportsInto(fileContext: SourceFileRootContext) {
        imports.forEach(fileContext::addImport)

        val defaultImportLocation = selfDeclaration?.declaredAt?.deriveGenerated()
            ?: Span(lexerFile, 1u, 1u, 1u, 1u, true)
        val defaultImports = DEFAULT_IMPORT_PACKAGES
            .map { pkgName ->
                ImportDeclaration(
                    defaultImportLocation,
                    (pkgName.components + "*").map(::IdentifierToken),
                )
            }

        for (defaultImport in defaultImports) {
            if (imports.any { it == defaultImport }) {
                continue
            }
            fileContext.addImport(defaultImport)
        }
    }

    private fun bindFunctionsInto(fileContext: SourceFileRootContext) {
        functions.forEach { fnDecl ->
            fileContext.addFunction(fnDecl.bindToAsTopLevel(fileContext))
        }
    }

    private fun bindBaseTypesInto(fileContext: SourceFileRootContext) {
        baseTypes.forEach(fileContext::addBaseType)
    }

    private fun bindVariablesInto(fileContext: SourceFileRootContext, reportings: MutableCollection<Reporting>) {
        variables.forEach { declaredVariable ->
            // check double declare
            val existingVariable = fileContext.resolveVariable(declaredVariable.name.value, true)
            if (existingVariable == null || existingVariable.declaration === declaredVariable) {
                fileContext.addVariable(declaredVariable.bindTo(fileContext, BoundVariable.Kind.GLOBAL_VARIABLE))
            }
            else {
                // variable double-declared
                reportings.add(Reporting.variableDeclaredMoreThanOnce(existingVariable.declaration, declaredVariable))
            }
        }
    }
}