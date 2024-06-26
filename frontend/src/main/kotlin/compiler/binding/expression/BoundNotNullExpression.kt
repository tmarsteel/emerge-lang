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

package compiler.binding.expression

import compiler.ast.expression.NotNullExpression
import compiler.binding.BoundExecutable
import compiler.binding.BoundStatement
import compiler.binding.context.CTContext
import compiler.binding.context.ExecutionScopedCTContext
import compiler.binding.type.BoundTypeReference
import compiler.reportings.NothrowViolationReporting
import compiler.reportings.Reporting
import io.github.tmarsteel.emerge.backend.api.ir.IrExpression

class BoundNotNullExpression(
    override val context: ExecutionScopedCTContext,
    override val declaration: NotNullExpression,
    val nullableExpression: BoundExpression<*>
) : BoundExpression<NotNullExpression>, BoundExecutable<NotNullExpression> {
    // TODO: reporting on superfluous notnull when nullableExpression.type.nullable == false
    // TODO: obtain type from nullableExpression and remove nullability from the type

    override var type: BoundTypeReference? = null
        private set

    override val throwBehavior get() = nullableExpression.throwBehavior
    override val returnBehavior get() = nullableExpression.returnBehavior

    override fun semanticAnalysisPhase1(): Collection<Reporting> {
        return nullableExpression.semanticAnalysisPhase1()
    }

    override fun semanticAnalysisPhase2(): Collection<Reporting> {
        nullableExpression.markEvaluationResultUsed()
        return nullableExpression.semanticAnalysisPhase2()
    }

    private var nothrowBoundary: NothrowViolationReporting.SideEffectBoundary? = null
    override fun setNothrow(boundary: NothrowViolationReporting.SideEffectBoundary) {
        this.nothrowBoundary = boundary
    }

    override fun semanticAnalysisPhase3(): Collection<Reporting> {
        val reportings = nullableExpression.semanticAnalysisPhase3().toMutableList()
        nothrowBoundary?.let { nothrowBoundary ->
            reportings.add(Reporting.nothrowViolatingNotNullAssertion(this, nothrowBoundary))
        }
        return reportings
    }

    override fun findReadsBeyond(boundary: CTContext): Collection<BoundExpression<*>> {
        return nullableExpression.findReadsBeyond(boundary)
    }

    override fun findWritesBeyond(boundary: CTContext): Collection<BoundStatement<*>> {
        return nullableExpression.findWritesBeyond(boundary)
    }

    override fun setExpectedEvaluationResultType(type: BoundTypeReference) {
        // TODO: do we need to change nullability here before passing it on?
        nullableExpression.setExpectedEvaluationResultType(type)
    }

    override val isEvaluationResultReferenceCounted get() = TODO("not implemented yet")
    override val isCompileTimeConstant get() = nullableExpression.isCompileTimeConstant

    override fun toBackendIrExpression(): IrExpression {
        /*
        should be fairly simply once a cast operator and exceptions are available. Declare in emerge.std:

        fun errorIfNull<T : Any>(value: T?): T {
            if (value != null) {
                return value as T
            }
            throw NullPointerError()
        }

        then just call this function here
         */

        TODO("Not yet implemented")
    }
}