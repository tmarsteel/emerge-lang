package compiler.compiler.negative

import compiler.binding.expression.BoundIdentifierExpression
import compiler.binding.expression.BoundMemberAccessExpression
import compiler.reportings.IllegalAssignmentReporting
import compiler.reportings.ValueNotAssignableReporting
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class MutabilityErrors : FreeSpec({
    "struct initialized in a val is immutable" - {
        "members cannot be mutated" {
            validateModule("""
                struct X {
                    a: Int
                }
                fun test() {
                    myX = X(2)
                    set myX.a = 3
                }
            """.trimIndent())
                    .shouldReport<IllegalAssignmentReporting> {
                        it.statement.targetExpression.shouldBeInstanceOf<BoundMemberAccessExpression>().let { targetExpr ->
                            targetExpr.valueExpression.shouldBeInstanceOf<BoundIdentifierExpression>().identifier shouldBe "myX"
                            targetExpr.memberName shouldBe "a"
                        }
                    }
        }

        "cannot be assigned to a mutable reference" {
            validateModule("""
                struct X {
                    a: Int
                }
                fun test() {
                    myX = X(2)
                    var otherX: mutable X = myX
                }
            """.trimIndent())
                .shouldReport<ValueNotAssignableReporting> {
                    it.reason shouldBe "cannot assign a immutable value to a mutable reference"
                }
        }

        "can be assigned to an immutable reference" {
            validateModule("""
                struct X {
                    a: Int
                }
                fun test() {
                    myX = X(2)
                    otherX: immutable X = myX
                }
            """.trimIndent()) should haveNoDiagnostics()
        }
    }

    "mutability from use-site generics" - {
        "prohibits writes to immutable element" {
            validateModule("""
                struct A {
                    someVal: Int
                }
                struct B<T> {
                    genericVal: T
                }
                fun test() {
                    myB: mutable B<immutable A> = B::<immutable A>(A(3))
                    set myB.genericVal = A(2)
                    set myB.genericVal.someVal = 5
                }
            """.trimIndent())
                .shouldReport<IllegalAssignmentReporting> {
                    it.statement.targetExpression.shouldBeInstanceOf<BoundMemberAccessExpression>().memberName shouldBe "someVal"
                }
        }
    }
})