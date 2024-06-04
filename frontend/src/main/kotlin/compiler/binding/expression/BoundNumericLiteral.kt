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

import compiler.InternalCompilerError
import compiler.ast.expression.NumericLiteralExpression
import compiler.binding.BoundStatement
import compiler.binding.SideEffectPrediction
import compiler.binding.basetype.BoundBaseType
import compiler.binding.context.CTContext
import compiler.binding.context.ExecutionScopedCTContext
import compiler.binding.type.BoundTypeFromArgument
import compiler.binding.type.BoundTypeReference
import compiler.binding.type.CoreTypes
import compiler.binding.type.NullableTypeReference
import compiler.binding.type.RootResolvedTypeReference
import compiler.handleCyclicInvocation
import compiler.reportings.NothrowViolationReporting
import compiler.reportings.Reporting
import io.github.tmarsteel.emerge.backend.api.ir.IrExpression
import io.github.tmarsteel.emerge.backend.api.ir.IrIntegerLiteralExpression
import io.github.tmarsteel.emerge.backend.api.ir.IrType
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Should only be used when the numeric literal cannot be parsed correctly. Otherwise, use
 * [BoundIntegerLiteral] and [BoundFloatingPointLiteral].
 */
open class BoundNumericLiteral(
    override val context: ExecutionScopedCTContext,
    override val declaration: NumericLiteralExpression,
    private val bindTimeReportings: Collection<Reporting>
) : BoundLiteralExpression<NumericLiteralExpression> {
    override val type: BoundTypeReference? = null // unknown

    override val throwBehavior = SideEffectPrediction.NEVER
    override val returnBehavior = SideEffectPrediction.NEVER

    override fun semanticAnalysisPhase1(): Collection<Reporting> {
        val reportings = mutableListOf<Reporting>()
        reportings.addAll(bindTimeReportings)
        return reportings
    }
    override fun semanticAnalysisPhase2(): Collection<Reporting> = emptySet()
    override fun semanticAnalysisPhase3(): Collection<Reporting> = emptySet()
    override fun findReadsBeyond(boundary: CTContext): Collection<BoundExpression<*>> = emptySet()
    override fun findWritesBeyond(boundary: CTContext): Collection<BoundStatement<*>> = emptySet()
    override fun setNothrow(boundary: NothrowViolationReporting.SideEffectBoundary) {}

    protected var expectedNumericType: BoundBaseType? = null
    override fun setExpectedEvaluationResultType(type: BoundTypeReference) {
        when (type) {
            is BoundTypeFromArgument -> return setExpectedEvaluationResultType(type.effectiveType)
            is NullableTypeReference -> return setExpectedEvaluationResultType(type.nested)
            is RootResolvedTypeReference -> {
                if (!type.baseType.isCoreNumericType) {
                    return
                }

                // assure completed
                handleCyclicInvocation(
                    context = this,
                    action = { type.baseType.semanticAnalysisPhase1() },
                    onCycle = { }
                )

                expectedNumericType = type.baseType
            }
            else -> {}
        }
    }

    override val isEvaluationResultReferenceCounted = false
    override val isCompileTimeConstant = true

    override fun toBackendIrExpression(): IrExpression {
        throw InternalCompilerError("Numeric literal not completely recognized (int vs floating point)")
    }
}

class BoundIntegerLiteral(
    context: ExecutionScopedCTContext,
    declaration: NumericLiteralExpression,
    val integer: BigInteger,
    reportings: Collection<Reporting>
) : BoundNumericLiteral(context, declaration, reportings) {
    override lateinit var type: RootResolvedTypeReference

    override fun semanticAnalysisPhase2(): Collection<Reporting> {
        val reportings = mutableSetOf<Reporting>()
        type = (expectedNumericType ?: context.swCtx.s32).baseReference
        val typeRange = when (type.baseType) {
            context.swCtx.s8 -> CoreTypes.S8_RANGE
            context.swCtx.u8 -> CoreTypes.U8_RANGE
            context.swCtx.s16 -> CoreTypes.S16_RANGE
            context.swCtx.u16 -> CoreTypes.U16_RANGE
            context.swCtx.s32 -> CoreTypes.S32_RANGE
            context.swCtx.u32 -> CoreTypes.U32_RANGE
            context.swCtx.s64 -> CoreTypes.S64_RANGE
            context.swCtx.u64 -> CoreTypes.U64_RANGE
            context.swCtx.sword -> CoreTypes.SWORD_SAFE_RANGE
            context.swCtx.uword -> CoreTypes.UWORD_SAFE_RANGE
            else -> throw InternalCompilerError("How did the type $type end up here - apparently not an integer type")
        }

        if (integer !in typeRange) {
            reportings.add(Reporting.integerLiteralOutOfRange(declaration, type.baseType, typeRange))
        }

        return reportings
    }

    override fun toBackendIrExpression(): IrExpression {
        return IrIntegerLiteralExpressionImpl(
            integer,
            type.toBackendIr(),
        )
    }
}

class BoundFloatingPointLiteral(
    context: ExecutionScopedCTContext,
    declaration: NumericLiteralExpression,
    val float: BigDecimal,
    reportings: Collection<Reporting>
) : BoundNumericLiteral(context, declaration, reportings) {
    override val type = context.swCtx.f32.baseReference

    override fun toBackendIrExpression(): IrExpression {
        TODO()
    }
}

internal class IrIntegerLiteralExpressionImpl(
    override val value: BigInteger,
    override val evaluatesTo: IrType,
) : IrIntegerLiteralExpression