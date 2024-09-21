package compiler.ast

import compiler.binding.BoundStatement
import compiler.binding.basetype.BoundMixinStatement
import compiler.binding.context.ExecutionScopedCTContext
import compiler.lexer.KeywordToken

class AstMixinStatement(
    val mixinKeyword: KeywordToken,
    val mixinExpression: Expression,
) : Statement {
    override val span = mixinKeyword.span .. mixinExpression.span

    override fun bindTo(context: ExecutionScopedCTContext): BoundStatement<*> {
        return BoundMixinStatement(mixinExpression.bindTo(context), this)
    }
}