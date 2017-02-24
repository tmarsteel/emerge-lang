package compiler.parser.postproc

import compiler.ast.type.TypeReference
import compiler.ast.VariableDeclaration
import compiler.ast.expression.Expression
import compiler.ast.type.TypeModifier
import compiler.lexer.*
import compiler.parser.rule.MatchingResult
import compiler.parser.rule.Rule
import compiler.transact.Position
import compiler.transact.TransactionalSequence

fun VariableDeclarationPostProcessor(rule: Rule<List<MatchingResult<*>>>): Rule<VariableDeclaration> {
    return rule
        .flatten()
        .mapResult(::toAST)
}

private fun toAST(input: TransactionalSequence<Any, Position>): VariableDeclaration {
    var modifierOrKeyword = input.next()!!

    val typeModifier: TypeModifier?
    val declarationKeyword: Keyword

    if (modifierOrKeyword is TypeModifier) {
        typeModifier = modifierOrKeyword
        declarationKeyword = (input.next()!! as KeywordToken).keyword
    }
    else {
        typeModifier = null
        declarationKeyword = (modifierOrKeyword as KeywordToken).keyword
    }

    val name = input.next()!! as IdentifierToken

    var type: TypeReference? = null

    var colonOrEqualsOrNewline = input.next()

    if (colonOrEqualsOrNewline == OperatorToken(Operator.COLON)) {
        type = input.next()!! as TypeReference
        colonOrEqualsOrNewline = input.next()!!
    }

    var assignExpression: Expression? = null

    val equalsOrNewline = colonOrEqualsOrNewline

    if (equalsOrNewline == OperatorToken(Operator.EQUALS)) {
        assignExpression = input.next()!! as Expression
    }

    return VariableDeclaration(
        typeModifier,
        name,
        type,
        declarationKeyword == Keyword.VAR,
        assignExpression
    )
}