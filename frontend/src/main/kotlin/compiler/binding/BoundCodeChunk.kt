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

package compiler.binding

import compiler.ast.CodeChunk
import compiler.binding.SideEffectPrediction.Companion.reduceSequentialExecution
import compiler.binding.context.CTContext
import compiler.binding.context.ExecutionScopedCTContext
import compiler.binding.expression.BoundExpression
import compiler.binding.expression.IrIntegerLiteralExpressionImpl
import compiler.binding.misc_ir.IrCreateStrongReferenceStatementImpl
import compiler.binding.misc_ir.IrCreateTemporaryValueImpl
import compiler.binding.misc_ir.IrImplicitEvaluationExpressionImpl
import compiler.binding.misc_ir.IrTemporaryValueReferenceImpl
import compiler.binding.misc_ir.IrUpdateSourceLocationStatementImpl
import compiler.binding.type.BoundTypeReference
import compiler.handleCyclicInvocation
import compiler.reportings.NothrowViolationReporting
import compiler.reportings.Reporting
import io.github.tmarsteel.emerge.backend.api.ir.IrCodeChunk
import io.github.tmarsteel.emerge.backend.api.ir.IrCreateStrongReferenceStatement
import io.github.tmarsteel.emerge.backend.api.ir.IrExecutable
import java.math.BigInteger

class BoundCodeChunk(
    /**
     * Context that applies to the leftHandSide statement; derivatives are stored within the statements themselves
     */
    override val context: ExecutionScopedCTContext,

    override val declaration: CodeChunk,

    val statements: List<BoundExecutable<*>>,
) : BoundExecutable<CodeChunk> {
    override val returnBehavior get() = statements.map { it.returnBehavior }.reduceSequentialExecution()
    override val throwBehavior get() = statements.map { it.throwBehavior }.reduceSequentialExecution()

    override val modifiedContext: ExecutionScopedCTContext
        get() = statements.lastOrNull()?.modifiedContext ?: context

    override fun setExpectedReturnType(type: BoundTypeReference) {
        this.statements.forEach {
            it.setExpectedReturnType(type)
        }
    }

    override val implicitEvaluationResultType: BoundTypeReference?
        get() = statements.lastOrNull()?.implicitEvaluationResultType

    override fun requireImplicitEvaluationTo(type: BoundTypeReference) {
        statements.lastOrNull()?.requireImplicitEvaluationTo(type)
    }

    private val seanHelper = SeanHelper()
    override fun semanticAnalysisPhase1(): Collection<Reporting> {
        return seanHelper.phase1 {
            statements.flatMap { it.semanticAnalysisPhase1() }
        }
    }

    private lateinit var deferredCode: List<BoundExecutable<*>>

    override fun semanticAnalysisPhase2(): Collection<Reporting> {
        return seanHelper.phase2 {
            statements.flatMap { it.semanticAnalysisPhase2() }
        }
    }

    private var nothrowBoundary: NothrowViolationReporting.SideEffectBoundary? = null
    override fun setNothrow(boundary: NothrowViolationReporting.SideEffectBoundary) {
        seanHelper.requirePhase3NotDone()
        require(nothrowBoundary == null) { "setNothrow called more than once" }

        this.nothrowBoundary = boundary
        statements.forEach { it.setNothrow(boundary) }
    }

    override fun semanticAnalysisPhase3(): Collection<Reporting> {
        return seanHelper.phase3 {
            val reportings = mutableListOf<Reporting>()
            statements.flatMap { it.semanticAnalysisPhase3() }.forEach(reportings::add)

            deferredCode = modifiedContext.getScopeLocalDeferredCode().toList()
            deferredCode.flatMap { it.semanticAnalysisPhase1() }.forEach(reportings::add)
            deferredCode.flatMap { it.semanticAnalysisPhase2() }.forEach(reportings::add)
            nothrowBoundary?.let { nothrowBoundary ->
                deferredCode.forEach { it.setNothrow(nothrowBoundary) }
            }
            deferredCode.flatMap { it.semanticAnalysisPhase3() }.forEach(reportings::add)

            return@phase3 reportings
        }
    }

    override fun findReadsBeyond(boundary: CTContext): Collection<BoundExpression<*>> {
        seanHelper.requirePhase3Done()
        return statements.flatMap {
            handleCyclicInvocation(
                context = this,
                action =  { it.findReadsBeyond(boundary) },
                onCycle = ::emptySet,
            )
        }
    }

    override fun findWritesBeyond(boundary: CTContext): Collection<BoundStatement<*>> {
        return statements.flatMap {
            handleCyclicInvocation(
                context = this,
                action = { it.findWritesBeyond(boundary) },
                onCycle = ::emptySet,
            )
        }
    }

    private fun getDeferredCodeAtEndOfChunk(): List<IrExecutable> {
        return deferredCode.mapToBackendIrWithDebugLocations()
    }

    override fun toBackendIrStatement(): IrCodeChunk {
        return IrCodeChunkImpl(
            statements.mapToBackendIrWithDebugLocations() + getDeferredCodeAtEndOfChunk()
        )
    }

    /**
     * the equivalent of [BoundExpression.isEvaluationResultReferenceCounted]
     */
    val isImplicitEvaluationResultReferenceCounted: Boolean
        get() = (statements.lastOrNull() as? BoundExpression<*>)?.isEvaluationResultReferenceCounted ?: false

    /**
     * @param assureResultHasReferenceCountIncrement if true and if the implicit result has
     * [BoundExpression.isEvaluationResultReferenceCounted] == `false`, will include an [IrCreateStrongReferenceStatement]
     * for the temporary implicit result.
     */
    fun toBackendIrAsImplicitEvaluationExpression(assureResultHasReferenceCountIncrement: Boolean): IrImplicitEvaluationExpressionImpl {
        val plainStatements = statements
            .take((statements.size - 1).coerceAtLeast(0))
            .mapToBackendIrWithDebugLocations()
            .toMutableList()

        val lastStatement = statements.lastOrNull()

        if (lastStatement is BoundExpression<*>) {
            val implicitValueTemporary = IrCreateTemporaryValueImpl(lastStatement.toBackendIrExpression())
            plainStatements += implicitValueTemporary
            if (lastStatement.isEvaluationResultReferenceCounted) {
                check(!assureResultHasReferenceCountIncrement) {
                    "Reference counting bug: the implicit result is implicitly reference counted (${lastStatement::class.simpleName}) but the code using the result isn't aware."
                }
            } else if (assureResultHasReferenceCountIncrement) {
                plainStatements += IrCreateStrongReferenceStatementImpl(implicitValueTemporary)
            }
            return IrImplicitEvaluationExpressionImpl(
                IrCodeChunkImpl(plainStatements + getDeferredCodeAtEndOfChunk()),
                IrTemporaryValueReferenceImpl(implicitValueTemporary),
            )
        }

        val standInLiteralTemporary = IrCreateTemporaryValueImpl(IrIntegerLiteralExpressionImpl(BigInteger.ZERO, context.swCtx.uword.baseReference.toBackendIr()))
        return IrImplicitEvaluationExpressionImpl(
            IrCodeChunkImpl(plainStatements + listOfNotNull(lastStatement?.toBackendIrStatement()) + getDeferredCodeAtEndOfChunk() + listOf(standInLiteralTemporary)),
            IrTemporaryValueReferenceImpl(standInLiteralTemporary),
        )
    }
}

private fun Collection<BoundExecutable<*>>.mapToBackendIrWithDebugLocations(): List<IrExecutable> {
    val iterator = iterator()
    if (!iterator.hasNext()) {
        return emptyList()
    }

    val firstStmt = iterator.next()
    var locationState = firstStmt.declaration.span
    val irList = ArrayList<IrExecutable>(size * 3 / 2)
    irList.add(IrUpdateSourceLocationStatementImpl(locationState))
    irList.add(firstStmt.toBackendIrStatement())

    while (iterator.hasNext()) {
        val stmt = iterator.next()
        if (stmt.declaration.span.lineNumber != locationState.lineNumber || stmt.declaration.span.columnNumber != locationState.columnNumber) {
            locationState = stmt.declaration.span
            irList.add(IrUpdateSourceLocationStatementImpl(locationState))
        }
        irList.add(stmt.toBackendIrStatement())
    }

    return irList
}