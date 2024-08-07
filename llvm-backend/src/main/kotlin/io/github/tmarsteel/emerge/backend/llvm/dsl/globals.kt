package io.github.tmarsteel.emerge.backend.llvm.dsl

import io.github.tmarsteel.emerge.backend.llvm.jna.Llvm
import io.github.tmarsteel.emerge.backend.llvm.jna.LlvmValueRef
import io.github.tmarsteel.emerge.backend.llvm.jna.NativePointerArray

fun <S : LlvmStructType, C : LlvmContext> S.buildConstantIn(
    context: C,
    data: ConstantStructBuilder<S, C>.() -> Unit,
): LlvmConstant<S> {
    val builder = ConstantStructBuilder(this, context)
    builder.data()
    return LlvmConstant(builder.build(), this)
}

class ConstantStructBuilder<S : LlvmStructType, C : LlvmContext>(
    private val structType: S,
    val context: C,
) {
    private val valuesByIndex = HashMap<Int, LlvmValue<*>>()

    fun <M : LlvmType> setValue(member: LlvmStructType.Member<S, M>, value: LlvmValue<M>) {
        assert(value.type.isAssignableTo(member.type))
        assert(Llvm.LLVMIsConstant(value.raw) == 1) {
            "all struct elements must be constants. If you need dynamic values, set these later with insertvalue"
        }
        valuesByIndex[member.indexInStruct] = value
    }

    fun setNull(member: LlvmStructType.Member<S, *>) {
        valuesByIndex[member.indexInStruct] = context.nullValue(member.type)
    }

    fun setPoison(member: LlvmStructType.Member<S, *>) {
        valuesByIndex[member.indexInStruct] = context.poisonValue(member.type)
    }

    internal fun build(): LlvmValueRef {
        (0  until structType.nMembers)
            .find { index -> index !in valuesByIndex }
            ?.let {
                throw IllegalArgumentException("Missing data for struct member #$it")
            }

        NativePointerArray.fromJavaPointers(valuesByIndex.entries.sortedBy { it.key }.map { it.value.raw }).use { valuesArray ->
            return Llvm.LLVMConstNamedStruct(structType.getRawInContext(context), valuesArray, valuesArray.length)
        }
    }
}

fun <E : LlvmType> LlvmArrayType<E>.buildConstantIn(
    context: LlvmContext,
    data: Iterable<LlvmValue<E>>,
): LlvmConstant<LlvmArrayType<E>> {
    assert(data.all { Llvm.LLVMIsConstant(it.raw) == 1 }) {
        "all array elements must be constants. If you need dynamics, set these later on with insertvalue"
    }
    val constArray = NativePointerArray.fromJavaPointers(data.map { it.raw }).use { rawConstants ->
        Llvm.LLVMConstArray2(
            elementType.getRawInContext(context),
            rawConstants,
            rawConstants.length.toLong(),
        )
    }
    return LlvmConstant(constArray, this)
}