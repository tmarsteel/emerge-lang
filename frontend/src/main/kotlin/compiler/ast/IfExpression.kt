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

import compiler.binding.context.ExecutionScopedCTContext
import compiler.binding.context.MutableExecutionScopedCTContext
import compiler.binding.expression.BoundIfExpression
import compiler.lexer.SourceLocation

class IfExpression (
    override val sourceLocation: SourceLocation,
    val condition: Expression,
    val thenCode: Executable,
    val elseCode: Executable?
) : Expression {

    override fun bindTo(context: ExecutionScopedCTContext): BoundIfExpression {
        val contextBeforeCondition: ExecutionScopedCTContext = MutableExecutionScopedCTContext(context)
        val boundCondition = condition.bindTo(contextBeforeCondition)

        val thenCodeAsChunk: CodeChunk = if (thenCode is CodeChunk) thenCode else CodeChunk(listOf(thenCode as Statement))
        val elseCodeAsChunk: CodeChunk? = if (elseCode == null) null else if (elseCode is CodeChunk) elseCode else CodeChunk(listOf(elseCode as Statement))

        return BoundIfExpression(
            contextBeforeCondition,
            this,
            boundCondition,
            thenCodeAsChunk.bindTo(boundCondition.modifiedContext),
            elseCodeAsChunk?.bindTo(contextBeforeCondition),
        )
    }
}