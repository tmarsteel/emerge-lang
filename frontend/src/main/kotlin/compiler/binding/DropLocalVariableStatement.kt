package compiler.binding

import compiler.ast.Statement
import compiler.ast.VariableDeclaration
import compiler.binding.context.ExecutionScopedCTContext
import compiler.binding.expression.IrVariableAccessExpressionImpl
import compiler.binding.misc_ir.IrCreateTemporaryValueImpl
import compiler.binding.misc_ir.IrDropStrongReferenceStatementImpl
import compiler.reportings.NothrowViolationReporting
import compiler.reportings.Reporting
import io.github.tmarsteel.emerge.backend.api.ir.IrExecutable

class DropLocalVariableStatement(
    val variable: BoundVariable,
) : Statement {
    override val span = variable.declaration.span
    override fun bindTo(contextOnDeferredExecution: ExecutionScopedCTContext) = object : BoundStatement<VariableDeclaration> {
        override val context = contextOnDeferredExecution
        override val declaration = variable.declaration
        override val returnBehavior = SideEffectPrediction.NEVER
        override val throwBehavior get() = SideEffectPrediction.NEVER

        override fun setNothrow(boundary: NothrowViolationReporting.SideEffectBoundary) {

        }

        override fun toBackendIrStatement(): IrExecutable {
            val valueTemporary = IrCreateTemporaryValueImpl(IrVariableAccessExpressionImpl(variable.backendIrDeclaration))
            return IrCodeChunkImpl(listOf(
                valueTemporary,
                IrDropStrongReferenceStatementImpl(valueTemporary),
            ))
        }

        override fun semanticAnalysisPhase1(): Collection<Reporting> = emptyList()
        override fun semanticAnalysisPhase2(): Collection<Reporting> = emptyList()
        override fun semanticAnalysisPhase3(): Collection<Reporting> = emptyList()
    }
}