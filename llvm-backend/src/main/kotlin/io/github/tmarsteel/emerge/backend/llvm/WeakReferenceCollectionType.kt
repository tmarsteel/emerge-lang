package io.github.tmarsteel.emerge.backend.llvm

import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmArrayType
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmContext
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmLeafType
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmPointerType
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmStructType

internal class WeakReferenceCollectionType(
    context: LlvmContext,
) : LlvmStructType(context, "weakrefcoll") {
    val weakObjects = structMember {
        LlvmArrayType(this, 10, LlvmLeafType(this, pointerTypeRaw))
    }
    val next = structMember { LlvmPointerType(this@WeakReferenceCollectionType) }
}