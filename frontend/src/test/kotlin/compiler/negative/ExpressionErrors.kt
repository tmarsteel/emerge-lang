package compiler.compiler.negative

import compiler.binding.expression.BoundIdentifierExpression
import compiler.reportings.ImplicitlyEvaluatedStatementReporting
import compiler.reportings.UnsafeObjectTraversalReporting
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class ExpressionErrors : FreeSpec({
    "unsafe object traversal" {
        validateModule("""
            struct X {
                a: Int
            }
            fun foo(p: X?) -> Int {
                return p.a
            }
        """.trimIndent())
            .shouldReport<UnsafeObjectTraversalReporting> {
                it.nullableExpression.shouldBeInstanceOf<BoundIdentifierExpression>().identifier shouldBe "p"
            }
    }
})