package io.github.tmarsteel.emerge.backend.llvm.intrinsics

import io.github.tmarsteel.emerge.backend.llvm.dsl.BasicBlockBuilder
import io.github.tmarsteel.emerge.backend.llvm.dsl.GetElementPointerStep
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmArrayType
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmCachedType
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmConstant
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmContext
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmFunctionAddressType
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmFunctionType
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmIntegerType
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmNamedStructType
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmPointerType
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmPointerType.Companion.pointerTo
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmType
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmValue
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmVoidType
import io.github.tmarsteel.emerge.backend.llvm.jna.Llvm
import io.github.tmarsteel.emerge.backend.llvm.jna.LlvmTypeRef

internal object EmergeWordType : LlvmCachedType(), LlvmIntegerType {
    override fun getNBitsInContext(context: LlvmContext): Int = context.targetData.pointerSizeInBytes * 8
    override fun computeRaw(context: LlvmContext) = Llvm.LLVMIntTypeInContext(context.ref, getNBitsInContext(context))
    override fun toString() = "%word"
}

internal fun LlvmContext.word(value: Int): LlvmConstant<EmergeWordType> {
    val wordMax = EmergeWordType.getMaxUnsignedValueInContext(this)
    check (value.toBigInteger() <= wordMax) {
        "The value $value cannot be represented by the word type on this target (max $wordMax)"
    }

    return LlvmConstant(
        Llvm.LLVMConstInt(EmergeWordType.getRawInContext(this), value.toLong(), 0),
        EmergeWordType,
    )
}

internal fun LlvmContext.word(value: Long): LlvmConstant<EmergeWordType> {
    val wordMax = EmergeWordType.getMaxUnsignedValueInContext(this)
    check (value.toBigInteger() <= wordMax) {
        "The value $value cannot be represented by the word type on this target (max $wordMax)"
    }

    return LlvmConstant(
        Llvm.LLVMConstInt(EmergeWordType.getRawInContext(this), value, 0),
        EmergeWordType,
    )
}

internal fun LlvmContext.word(value: ULong): LlvmConstant<EmergeWordType> = word(value.toLong())

internal object EmergeAnyValueVirtualsType : LlvmNamedStructType("anyvalue_virtuals") {
    val finalizeFunction by structMember(LlvmFunctionAddressType)

    val finalizeFunctionType = LlvmFunctionType(LlvmVoidType, listOf(PointerToAnyEmergeValue))
}

internal val PointerToAnyEmergeValue: LlvmPointerType<EmergeHeapAllocatedValueBaseType> by lazy { pointerTo(EmergeHeapAllocatedValueBaseType) }

internal object EmergeWeakReferenceCollectionType : LlvmNamedStructType("weakrefcoll") {
    /**
     * pointers to the actual memory locations where a pointer to another object is kept. In practice this
     * will exclusively be the addresses of the `value` member in the `emerge.core.Weak` class.
     */
    val pointersToWeakReferences by structMember(
        LlvmArrayType(10, pointerTo(PointerToAnyEmergeValue)),
    )
    val next by structMember(pointerTo(this@EmergeWeakReferenceCollectionType))
}

/**
 * The data common to all heap-allocated objects in emerge
 */
internal object EmergeHeapAllocatedValueBaseType : LlvmNamedStructType("anyvalue"), EmergeHeapAllocated {
    val strongReferenceCount by structMember(EmergeWordType)
    val typeinfo by structMember(pointerTo(TypeinfoType.GENERIC))
    val weakReferenceCollection by structMember(pointerTo(EmergeWeakReferenceCollectionType))

    override fun pointerToCommonBase(
        builder: BasicBlockBuilder<*, *>,
        value: LlvmValue<*>,
    ): GetElementPointerStep<EmergeHeapAllocatedValueBaseType> {
        require(value.type is LlvmPointerType<*>)
        return builder.getelementptr(value.reinterpretAs(PointerToAnyEmergeValue))
    }

    override fun assureReinterpretableAsAnyValue(context: LlvmContext, selfInContext: LlvmTypeRef) {
        // this is AnyValue itself, noop
    }
}

internal interface EmergeHeapAllocated : LlvmType {
    fun pointerToCommonBase(builder: BasicBlockBuilder<*, *>, value: LlvmValue<*>): GetElementPointerStep<EmergeHeapAllocatedValueBaseType>

    /**
     * This abstract method is a reminder to check *at emerge compile time* that your subtype of [EmergeHeapAllocated]#
     * can actually be [LlvmValue.reinterpretAs] any([EmergeHeapAllocatedValueBaseType]).
     * @throws CodeGenerationException if that is not the case.
     */
    fun assureReinterpretableAsAnyValue(context: LlvmContext, selfInContext: LlvmTypeRef)
}