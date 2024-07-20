package compiler.ast.expression

import compiler.ast.Executable
import compiler.binding.context.ExceptionHandlingExecutionScopedCTContext
import compiler.binding.context.ExecutionScopedCTContext
import compiler.binding.expression.BoundExpression
import compiler.binding.expression.BoundTryCatchExpression
import compiler.lexer.Span
import compiler.ast.CodeChunk as AstCodeChunk
import compiler.ast.Expression as AstExpression
import compiler.ast.Statement as AstStatement

class AstTryCatchExpression(
    override val span: Span,
    val fallibleCode: Executable,
    val catchBlock: AstCatchBlockExpression,
) : AstExpression {
    override fun bindTo(context: ExecutionScopedCTContext): BoundExpression<*> {
        val fallibleCodeAsChunk = when(fallibleCode) {
            is AstCodeChunk -> fallibleCode
            is AstStatement -> AstCodeChunk(listOf(fallibleCode))
        }
        val boundFallibleCode = fallibleCodeAsChunk.bindTo(
            ExceptionHandlingExecutionScopedCTContext(context)
        )

        return BoundTryCatchExpression(
            context,
            this,
            boundFallibleCode,
            catchBlock.bindTo(context),
        )
    }
}