package compiler.binding.basetype

import compiler.ast.AstCodeChunk
import compiler.ast.ReturnExpression
import compiler.ast.VariableDeclaration
import compiler.ast.expression.IdentifierExpression
import compiler.ast.expression.InvocationExpression
import compiler.ast.expression.MemberAccessExpression
import compiler.binding.BoundMemberFunction
import compiler.lexer.IdentifierToken
import compiler.lexer.Keyword
import compiler.lexer.KeywordToken
import compiler.lexer.Operator
import compiler.lexer.OperatorToken
import compiler.lexer.Span
import compiler.reportings.Reporting
import compiler.util.checkNoDiagnostics
import io.github.tmarsteel.emerge.backend.api.ir.IrClass
import io.github.tmarsteel.emerge.backend.api.ir.IrCodeChunk
import io.github.tmarsteel.emerge.backend.api.ir.IrDelegatingMemberFunction
import io.github.tmarsteel.emerge.backend.api.ir.IrMemberFunction

class DelegatedBoundMemberFunction(
    val inheritedFn: InheritedBoundMemberFunction,
    /**
     * nullable to allow for the case that the delegation expression is not valid. Having a living instance of [DelegatedBoundMemberFunction]
     * anyways allows the compiler to recognize that the abstract function need not be explicitly overridden; hence
     * fewer phantom errors.
     */
    val delegatedTo: BoundBaseTypeMemberVariable?,
    val delegationDeclaredAt: Span,
) : BoundMemberFunction by inheritedFn {
    private val generatedSourceLocation = delegationDeclaredAt.deriveGenerated()
    private val astDelegationBody: AstCodeChunk? = run {
        if (delegatedTo == null) {
            return@run null
        }

        val delegateLocalVar = VariableDeclaration(
            delegationDeclaredAt,
            null,
            null,
            null,
            IdentifierToken("_delegate", generatedSourceLocation),
            null,
            MemberAccessExpression(
                IdentifierExpression(IdentifierToken("self", generatedSourceLocation)),
                OperatorToken(Operator.DOT, generatedSourceLocation),
                IdentifierToken(delegatedTo.name, generatedSourceLocation)
            ),
        )
        val invocation = InvocationExpression(
            MemberAccessExpression(
                IdentifierExpression(delegateLocalVar.name),
                OperatorToken(Operator.DOT, generatedSourceLocation),
                IdentifierToken(inheritedFn.name, generatedSourceLocation),
            ),
            null,
            parameters.parameters.drop(1).map { argument ->
                IdentifierExpression(IdentifierToken(argument.name, generatedSourceLocation))
            },
            generatedSourceLocation
        )
        val returnInvocation = ReturnExpression(
            KeywordToken(Keyword.RETURN, span = generatedSourceLocation),
            invocation
        )

        AstCodeChunk(listOf(delegateLocalVar, returnInvocation))
    }

    private val boundDelegationBody = astDelegationBody?.bindTo(parameters.modifiedContext)

    override fun semanticAnalysisPhase1(): Collection<Reporting> {
        boundDelegationBody?.semanticAnalysisPhase1()?.let(::checkNoDiagnostics)
        return inheritedFn.semanticAnalysisPhase1()
    }

    override fun semanticAnalysisPhase2(): Collection<Reporting> {
        boundDelegationBody?.semanticAnalysisPhase2()?.let(::checkNoDiagnostics)
        return inheritedFn.semanticAnalysisPhase2()
    }

    override val overrides: Set<InheritedBoundMemberFunction> = setOf(inheritedFn)

    override fun semanticAnalysisPhase3(): Collection<Reporting> {
        boundDelegationBody?.semanticAnalysisPhase3()?.let(::checkNoDiagnostics)
        return inheritedFn.semanticAnalysisPhase3()
    }

    private val backendIr by lazy {
        IrDelegatingMemberFunctionImpl(
            inheritedFn.supertypeMemberFn.toBackendIr(),
            delegatedTo!!.toBackendIr(),
            boundDelegationBody!!.toBackendIrStatement(),
        )
    }

    override fun toBackendIr(): IrMemberFunction {
        return backendIr
    }

    override fun toString() = inheritedFn.toString() + " by " + (delegatedTo?.name ?: "<error>")
}

private class IrDelegatingMemberFunctionImpl(
    override val superFunction: IrMemberFunction,
    override val delegatesTo: IrClass.MemberVariable,
    override val body: IrCodeChunk,
) : IrDelegatingMemberFunction, IrMemberFunction by superFunction {
}