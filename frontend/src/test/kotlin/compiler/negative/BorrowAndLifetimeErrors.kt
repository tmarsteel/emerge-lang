package compiler.compiler.negative

import compiler.reportings.BorrowedVariableCapturedReporting
import compiler.reportings.LifetimeEndingCaptureInLoopReporting
import compiler.reportings.VariableUsedAfterLifetimeReporting
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class BorrowAndLifetimeErrors : FreeSpec({
    "initializing a variable ends the lifetime" - {
        "use by reading member" {
            validateModule("""
                class Test {
                    m: S32 = 0
                }
                fn test() -> S32 {
                    v: exclusive _ = Test()
                    v2 = v
                    return v.m
                }
            """.trimIndent())
                .shouldReport<VariableUsedAfterLifetimeReporting> {
                    it.variable.name.value shouldBe "v"
                    it.lifetimeEndedMaybe shouldBe false
                }
        }

        "use by writing member" {
            validateModule("""
                class Test {
                    var m: S32 = 0
                }
                fn test() {
                    v: exclusive _ = Test()
                    v2 = v
                    set v.m = 1
                }
            """.trimIndent())
                .shouldReport<VariableUsedAfterLifetimeReporting> {
                    it.variable.name.value shouldBe "v"
                    it.lifetimeEndedMaybe shouldBe false
                }
        }

        "use by return" {
            validateModule("""
                class Test {}
                fn test() -> const Test {
                    v: exclusive _ = Test()
                    v2 = v
                    return v
                }
            """.trimIndent())
                .shouldReport<VariableUsedAfterLifetimeReporting> {
                    it.variable.name.value shouldBe "v"
                    it.lifetimeEndedMaybe shouldBe false
                }
        }
    }

    "assigning to a variable ends the lifetime" {
        validateModule("""
            class Test {}
            fn test() -> Test {
                var v2: const _ = Test()
                v: exclusive _ = Test()
                set v2 = v
                return v
            }
            fn capture(p: const Test) {}
        """.trimIndent())
            .shouldReport<VariableUsedAfterLifetimeReporting> {
                it.variable.name.value shouldBe "v"
                it.lifetimeEndedMaybe shouldBe false
            }
    }

    "passing to capturing function parameter ends lifetime" {
        validateModule("""
            class Test {}
            fn test() -> Test {
                v: exclusive _ = Test()
                capture(v)
                return v
            }
            fn capture(p: const Test) {}
        """.trimIndent())
            .shouldReport<VariableUsedAfterLifetimeReporting> {
                it.variable.name.value shouldBe "v"
                it.lifetimeEndedMaybe shouldBe false
            }
    }

    "branching" - {
        "lifetime ended in then-only if" {
            validateModule("""
                class Test {}
                fn capture(p: Test) {}
                fn test(cond: Bool) {
                    v: exclusive _ = Test()
                    if (cond) {
                        v2 = v
                    }
                    capture(v)
                }
            """.trimIndent())
                .shouldReport<VariableUsedAfterLifetimeReporting> {
                    it.variable.name.value shouldBe "v"
                    it.lifetimeEndedMaybe shouldBe true
                }
        }

        "lifetime ended in one branch of if" {
            validateModule("""
                class Test {}
                fn capture(p: Test) {}
                fn test(cond: Bool) {
                    v: exclusive _ = Test()
                    if (cond) {
                        v2 = v
                    } else {
                    }
                    capture(v)
                }
            """.trimIndent())
                .shouldReport<VariableUsedAfterLifetimeReporting> {
                    it.variable.name.value shouldBe "v"
                    it.lifetimeEndedMaybe shouldBe true
                }
        }

        "lifetime ended in both branches of if" {
            validateModule("""
                class Test {}
                fn capture(p: Test) {}
                fn test(cond: Bool) {
                    v: exclusive _ = Test()
                    if (cond) {
                        v2 = v
                    } else {
                        v3 = v
                    }
                    capture(v)
                }
            """.trimIndent())
                .shouldReport<VariableUsedAfterLifetimeReporting> {
                    it.variable.name.value shouldBe "v"
                    it.lifetimeEndedMaybe shouldBe false
                }
        }
    }

    "loops" - {
        "capturing exclusive in while loop" {
            validateModule("""
                class C {}
                fn test(p: exclusive C) {
                    while true {
                        capture(p)
                    }
                }
                intrinsic fn capture(p: const Any)
            """.trimIndent())
                .shouldReport<LifetimeEndingCaptureInLoopReporting>()
        }
    }

    "borrowed variable cannot be captured" - {
        "capture by passing to a capturing function parameter" {
            validateModule("""
                class Test {}
                fn capture(p1: Test) {}
                fn test(borrow p2: Test) {
                    capture(p2)
                }
            """.trimIndent())
                .shouldReport<BorrowedVariableCapturedReporting> {
                    it.variable.name.value shouldBe "p2"
                }
        }

        "capture by initializing a variable" {
            validateModule("""
                class Test {}
                fn test(borrow p2: Test) {
                    p3 = p2
                }
            """.trimIndent())
                .shouldReport<BorrowedVariableCapturedReporting> {
                    it.variable.name.value shouldBe "p2"
                }
        }

        "capture by passing assigning to a variable" {
            validateModule("""
                class Test {}
                fn test(borrow p2: Test) {
                    var v = Test()
                    v = p2
                }
            """.trimIndent())
                .shouldReport<BorrowedVariableCapturedReporting> {
                    it.variable.name.value shouldBe "p2"
                }
        }
    }

    "exclusive value can be borrowed both mutably and immutably" {
        validateModule("""
            class Test {
                m: S32 = 0
            }
            fn borrowMut(borrow p1: mut Test) {}
            fn borrowImm(borrow p2: const Test) {}
            fn test() -> Test {
                v: exclusive _ = Test()
                borrowMut(v)
                borrowImm(v)
                return v
            }
        """.trimIndent())
            .shouldHaveNoDiagnostics()
    }

    "read capture doesn't end a lifetime" {
        validateModule("""
            class Test {
                m: S32 = 0
            }
            fn captureRead(p1: read Test) {}
            fn test() -> Test {
                v: exclusive _ = Test()
                captureRead(v)
                return v
            }
        """.trimIndent())
            .shouldHaveNoDiagnostics()
    }

    "reassigning a variable resets the lifetime" - {
        "definitely with linear control flow" {
            validateModule("""
                class Test {}
                fn capture(p: const Any) {}
                fn test() {
                    var v1: exclusive _ = Test()
                    capture(v1)
                    set v1 = Test()
                    capture(v1)
                }
            """.trimIndent())
                .shouldHaveNoDiagnostics()
        }

        "definitely with branched control flow" {
            validateModule("""
                class Test {}
                fn capture(p: const Any) {}
                fn test(cond: Bool) {
                    var v1: exclusive _ = Test()
                    capture(v1)
                    if cond {
                        set v1 = Test()
                    } else {
                        set v1 = Test()
                    }
                    capture(v1)
                }
            """.trimIndent())
                .shouldHaveNoDiagnostics()
        }

        "maybe with branched control flow" {
            validateModule("""
                class Test {}
                fn capture(p: const Any) {}
                fn test(cond: Bool) {
                    var v1: exclusive _ = Test()
                    capture(v1)
                    if cond {
                        set v1 = Test()
                    }
                    capture(v1)
                }
            """.trimIndent())
                .shouldReport<VariableUsedAfterLifetimeReporting>()
        }
    }
})