package io.github.tmarsteel.emerge.backend.llvm.dsl

import org.bytedeco.llvm.LLVM.LLVMValueRef

/**
 * A type-safe wrapper around [LLVMValueRef].
 * TODO: can this be optimized out to a @JvmInline value type?
 */
class LlvmValue<out Type : LlvmType>(
    val raw: LLVMValueRef,
    val type: Type,
)