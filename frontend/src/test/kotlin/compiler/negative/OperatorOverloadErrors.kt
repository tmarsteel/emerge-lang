package compiler.compiler.negative

import compiler.reportings.FunctionMissingModifierReporting
import compiler.reportings.OperatorNotDeclaredReporting
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class OperatorOverloadErrors : FreeSpec({
    "unary minus not declared" {
        validateModule("""
            fn foo() {
                a = - false
            }
        """.trimIndent())
            .shouldReport<OperatorNotDeclaredReporting>()
    }

    "binary plus not declared" {
        validateModule("""
            fn foo() {
                a = false + true
            }
        """.trimIndent())
            .shouldReport<OperatorNotDeclaredReporting>()
    }

    "unary minus declared without operator modifier" {
        validateModule("""
            intrinsic fn unaryMinus(self: Bool) -> Bool
            fn foo() {
                x = -false
            }
        """.trimIndent())
            .shouldReport<FunctionMissingModifierReporting> {
                it.function.name shouldBe "unaryMinus"
                it.missingAttribute shouldBe "operator"
            }
    }

    "index access read requires operator modifier" {
        validateModule("""
            class Foo {
                fn get(self, index: UWord) {
                }
            }
            
            fn test() {
                v = Foo()
                y = v[3]
            }
        """.trimIndent())
            .shouldReport<FunctionMissingModifierReporting> {
                it.function.name shouldBe "get"
                it.missingAttribute shouldBe "operator"
            }
    }

    "index access write requires operator modifier" {
        validateModule("""
            class Foo {
                fn `set`(self, index: UWord, value: S32) {
                }
            }
            
            fn test() {
                v = Foo()
                set v[3] = 5 
            }
        """.trimIndent())
            .shouldReport<FunctionMissingModifierReporting> {
                it.function.name shouldBe "set"
                it.missingAttribute shouldBe "operator"
            }
    }
})