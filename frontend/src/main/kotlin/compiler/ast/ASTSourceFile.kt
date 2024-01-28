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

import compiler.ast.struct.StructDeclaration
import compiler.binding.context.ModuleContext
import compiler.binding.context.SoftwareContext
import compiler.binding.context.SourceFile
import compiler.binding.context.SourceFileRootContext
import compiler.reportings.Reporting
import io.github.tmarsteel.emerge.backend.api.PackageName

/**
 * AST representation of a source file
 */
class ASTSourceFile(
    val expectedPackageName: PackageName,
) {
    var selfDeclaration: ASTPackageDeclaration? = null

    val imports: MutableList<ImportDeclaration> = mutableListOf()

    val variables: MutableList<VariableDeclaration> = mutableListOf()

    val functions: MutableList<FunctionDeclaration> = mutableListOf()

    val structs: MutableList<StructDeclaration> = mutableListOf()

    /**
     * Works by the same principle as [Bindable.bindTo]; but since binds to a [SoftwareContext] (rather than a
     * [CTContext]) this has its own signature.
     */
    fun bindTo(context: ModuleContext): SourceFile {
        val fileContext = SourceFileRootContext()
        fileContext.moduleContext = context
        val reportings = mutableSetOf<Reporting>()

        imports.forEach(fileContext::addImport)
        functions.forEach(fileContext::addFunction)
        structs.forEach(fileContext::addStruct)

        variables.forEach { declaredVariable ->
            // check double declare
            val existingVariable = fileContext.resolveVariable(declaredVariable.name.value, true)
            if (existingVariable == null || existingVariable.declaration === declaredVariable) {
                fileContext.addVariable(declaredVariable)
            }
            else {
                // variable double-declared
                reportings.add(Reporting.variableDeclaredMoreThanOnce(existingVariable.declaration, declaredVariable))
            }
        }

        // TODO: validate declared package name

        return SourceFile(
            selfDeclaration?.packageName?.names?.map { it.value }?.let(::PackageName) ?: expectedPackageName,
            fileContext,
            reportings
        )
    }
}