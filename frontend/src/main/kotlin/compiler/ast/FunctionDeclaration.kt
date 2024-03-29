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

import compiler.ast.type.FunctionModifier
import compiler.ast.type.TypeParameter
import compiler.ast.type.TypeReference
import compiler.binding.BoundDeclaredFunction
import compiler.binding.BoundFunction
import compiler.binding.BoundParameter
import compiler.binding.BoundParameterList
import compiler.binding.context.CTContext
import compiler.binding.context.ExecutionScopedCTContext
import compiler.binding.context.MutableExecutionScopedCTContext
import compiler.lexer.IdentifierToken
import compiler.lexer.SourceLocation

class FunctionDeclaration(
    override val declaredAt: SourceLocation,
    val modifiers: List<FunctionModifier>,
    val name: IdentifierToken,
    val typeParameters: List<TypeParameter>,
    val parameters: ParameterList,
    parsedReturnType: TypeReference?,
    val body: Body?,
) : AstFileLevelDeclaration {
    /**
     * The return type. Is null if none was declared and it has not been inferred yet (see semantic analysis phase 2)
     */
    val returnType: TypeReference? = parsedReturnType

    fun bindTo(context: CTContext): BoundDeclaredFunction {
        val functionContext = MutableExecutionScopedCTContext.functionRootIn(context)
        val boundTypeParams = typeParameters.map(functionContext::addTypeParameter)

        var contextAfterValueParameters: ExecutionScopedCTContext = functionContext
        val boundParameters = ArrayList<BoundParameter>(parameters.parameters.size)
        for (parameter in parameters.parameters) {
            val bound = parameter.bindToAsParameter(contextAfterValueParameters)
            boundParameters.add(bound)
            contextAfterValueParameters = bound.modifiedContext
        }
        val boundParamList = BoundParameterList(context, parameters, boundParameters)

        return BoundDeclaredFunction(
            functionContext,
            this,
            boundTypeParams,
            boundParamList,
            body?.bindTo(contextAfterValueParameters),
        )
    }

    sealed interface Body {
        fun bindTo(context: ExecutionScopedCTContext): BoundFunction.Body

        class SingleExpression(val expression: Expression) : Body {
            override fun bindTo(context: ExecutionScopedCTContext): BoundFunction.Body {
                return BoundFunction.Body.SingleExpression(
                    expression.bindTo(context)
                )
            }
        }

        class Full(val code: CodeChunk) : Body {
            override fun bindTo(context: ExecutionScopedCTContext): BoundFunction.Body {
                return BoundFunction.Body.Full(code.bindTo(context))
            }
        }
    }
}
