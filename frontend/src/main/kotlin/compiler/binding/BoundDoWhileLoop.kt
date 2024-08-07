package compiler.binding

import compiler.ast.AstDoWhileLoop
import compiler.binding.SideEffectPrediction.Companion.combineSequentialExecution
import compiler.binding.context.CTContext
import compiler.binding.context.ExecutionScopedCTContext
import compiler.binding.context.SingleBranchJoinExecutionScopedCTContext
import compiler.binding.expression.BoundExpression
import compiler.binding.expression.IrConditionalBranchImpl
import compiler.binding.misc_ir.IrCreateTemporaryValueImpl
import compiler.binding.misc_ir.IrLoopImpl
import compiler.binding.misc_ir.IrTemporaryValueReferenceImpl
import compiler.binding.type.BoundTypeReference
import compiler.reportings.NothrowViolationReporting
import compiler.reportings.Reporting
import io.github.tmarsteel.emerge.backend.api.ir.IrBreakStatement
import io.github.tmarsteel.emerge.backend.api.ir.IrContinueStatement
import io.github.tmarsteel.emerge.backend.api.ir.IrLoop

class BoundDoWhileLoop(
    override val context: ExecutionScopedCTContext,
    override val declaration: AstDoWhileLoop,
    val condition: BoundCondition,
    val body: BoundCodeChunk,
) : BoundLoop<AstDoWhileLoop> {
    override val throwBehavior get() = body.throwBehavior.combineSequentialExecution(condition.throwBehavior)
    override val returnBehavior get() = body.returnBehavior.combineSequentialExecution(condition.returnBehavior)

    override val modifiedContext = SingleBranchJoinExecutionScopedCTContext(
        context,
        condition.modifiedContext,
    )

    override fun setNothrow(boundary: NothrowViolationReporting.SideEffectBoundary) {
        condition.setNothrow(boundary)
        body.setNothrow(boundary)
    }

    private val seanHelper = SeanHelper()

    override fun semanticAnalysisPhase1(): Collection<Reporting> {
        return seanHelper.phase1 {
            val reportings = mutableListOf<Reporting>()
            reportings.addAll(condition.semanticAnalysisPhase1())
            reportings.addAll(body.semanticAnalysisPhase1())

            return@phase1 reportings
        }
    }

    override fun semanticAnalysisPhase2(): Collection<Reporting> {
        return seanHelper.phase2 {
            val reportings = mutableListOf<Reporting>()
            reportings.addAll(condition.semanticAnalysisPhase2())
            reportings.addAll(body.semanticAnalysisPhase2())

            return@phase2 reportings
        }
    }

    override fun setExpectedReturnType(type: BoundTypeReference) {
        condition.setExpectedReturnType(type)
        body.setExpectedReturnType(type)
    }

    override fun semanticAnalysisPhase3(): Collection<Reporting> {
        return seanHelper.phase3 {
            val reportings = mutableListOf<Reporting>()
            reportings.addAll(condition.semanticAnalysisPhase3())
            reportings.addAll(body.semanticAnalysisPhase3())

            return@phase3 reportings
        }
    }

    override fun findReadsBeyond(boundary: CTContext): Collection<BoundExpression<*>> {
        return condition.findReadsBeyond(boundary) + body.findReadsBeyond(boundary)
    }

    override fun findWritesBeyond(boundary: CTContext): Collection<BoundExecutable<*>> {
        return condition.findWritesBeyond(boundary) + body.findWritesBeyond(boundary)
    }

    private val backendIr by lazy {
        lateinit var loopHolder: IrLoop
        val continueStmt = object : IrContinueStatement {
            override val loop get()= loopHolder
        }
        val breakStmt = object : IrBreakStatement {
            override val fromLoop get() = loopHolder
        }
        val conditionTemporary = IrCreateTemporaryValueImpl(condition.toBackendIrExpression())
        loopHolder = IrLoopImpl(IrCodeChunkImpl(listOf(
            body.toBackendIrStatement(),
            conditionTemporary,
            IrConditionalBranchImpl(
                condition = IrTemporaryValueReferenceImpl(conditionTemporary),
                thenBranch = continueStmt,
                elseBranch = breakStmt
            )
        )))

        loopHolder
    }
    override fun toBackendIrStatement(): IrLoop {
        return backendIr
    }
}