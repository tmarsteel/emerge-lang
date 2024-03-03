package io.github.tmarsteel.emerge.backend.llvm.intrinsics

import io.github.tmarsteel.emerge.backend.api.ir.IrFunction
import io.github.tmarsteel.emerge.backend.api.ir.IrStruct
import io.github.tmarsteel.emerge.backend.llvm.codegen.sizeof
import io.github.tmarsteel.emerge.backend.llvm.dsl.BasicBlockBuilder
import io.github.tmarsteel.emerge.backend.llvm.dsl.BasicBlockBuilder.Companion.retVoid
import io.github.tmarsteel.emerge.backend.llvm.dsl.GetElementPointerStep
import io.github.tmarsteel.emerge.backend.llvm.dsl.KotlinLlvmFunction
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmContext
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmGlobal
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmPointerType
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmPointerType.Companion.pointerTo
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmType
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmValue
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmVoidType
import io.github.tmarsteel.emerge.backend.llvm.dsl.buildConstantIn
import io.github.tmarsteel.emerge.backend.llvm.dsl.i32
import io.github.tmarsteel.emerge.backend.llvm.indexInLlvmStruct
import io.github.tmarsteel.emerge.backend.llvm.isCPointerPointed
import io.github.tmarsteel.emerge.backend.llvm.llvmName
import io.github.tmarsteel.emerge.backend.llvm.llvmValueType
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.LLVM.LLVMTypeRef
import org.bytedeco.llvm.global.LLVM

internal class EmergeStructType private constructor(
    val context: EmergeLlvmContext,
    val structRef: LLVMTypeRef,
    val irStruct: IrStruct,
) : LlvmType, EmergeHeapAllocated {
    init {
        assureReinterpretableAsAnyValue(context, structRef)
    }

    override fun getRawInContext(context: LlvmContext): LLVMTypeRef {
        check(context === context)
        return structRef
    }

    override fun assureReinterpretableAsAnyValue(context: LlvmContext, selfInContext: LLVMTypeRef) {
        // done in [fromLlvmStructWithoutBody]
    }

    private val typeinfo = StaticAndDynamicTypeInfo.define(
        irStruct.llvmName,
        emptyList(),
        KotlinLlvmFunction.define(
            "${irStruct.llvmName}__finalize",
            LlvmVoidType,
        ) {
            val self by param(pointerTo(this@EmergeStructType))
            body {
                if (irStruct.fqn.toString() in setOf("emerge.ffi.c.CPointer", "emerge.ffi.c.COpaquePointer", "emerge.ffi.c.CValue")) {
                    // transparent type, no action needed
                    // TODO: throw, this should never be called!
                    return@body retVoid()
                }
                irStruct.members
                    .filter { it.type.llvmValueType == null } // value types need not be dropped
                    .forEach {
                        val referenceAsPointer = getelementptr(self).member(it).get().dereference()
                            .reinterpretAs(PointerToAnyEmergeValue)
                        referenceAsPointer.afterReferenceDropped(it.type)
                    }
                call(context.freeFunction, listOf(self))
                retVoid()
            }
        },
        { emptyList() }
    )

    private val anyValueBaseTemplateDynamic: LlvmGlobal<EmergeHeapAllocatedValueBaseType> by lazy {
        val constant = EmergeHeapAllocatedValueBaseType.buildConstantIn(context) {
            setValue(EmergeHeapAllocatedValueBaseType.strongReferenceCount, context.word(1))
            setValue(EmergeHeapAllocatedValueBaseType.typeinfo, typeinfo.provide(context).dynamic)
            setValue(EmergeHeapAllocatedValueBaseType.weakReferenceCollection, context.nullValue(pointerTo(EmergeWeakReferenceCollectionType)))
        }
        context.addGlobal(constant, LlvmGlobal.ThreadLocalMode.SHARED)
    }

    private val defaultConstructorIr: IrFunction = irStruct.constructors.single().overloads.single()
    val defaultConstructor: KotlinLlvmFunction<EmergeLlvmContext, LlvmPointerType<EmergeStructType>> = KotlinLlvmFunction.define(
        defaultConstructorIr.llvmName,
        pointerTo(this),
    ) {
        val params: List<Pair<IrStruct.Member, KotlinLlvmFunction.ParameterDelegate<*>>> = defaultConstructorIr.parameters.map { irParam ->
            val member = irStruct.members.single { it.name == irParam.name }
            member to param(context.getReferenceSiteType(irParam.type))
        }

        body {
            val heapAllocation = heapAllocate(this@EmergeStructType)
            val basePointer = getelementptr(heapAllocation).anyValueBase().get()
            memcpy(basePointer, anyValueBaseTemplateDynamic, EmergeHeapAllocatedValueBaseType.sizeof(), false)
            for ((irStructMember, llvmParam) in params) {
                val memberPointer = getelementptr(heapAllocation).member(irStructMember).get()
                val paramValue = llvmParam.getValue(null, String::length)
                store(paramValue, memberPointer)
                paramValue.afterReferenceCreated(irStructMember.type)
            }
            ret(heapAllocation)
        }
    }

    override fun pointerToCommonBase(
        builder: BasicBlockBuilder<*, *>,
        value: LlvmValue<*>
    ): GetElementPointerStep<EmergeHeapAllocatedValueBaseType> {
        require(value.type is LlvmPointerType<*>)
        require(value.type.pointed is EmergeStructType)
        @Suppress("UNCHECKED_CAST")
        return builder.getelementptr(value as LlvmValue<LlvmPointerType<out EmergeHeapAllocated>>)
            .stepUnsafe(builder.context.i32(0), EmergeHeapAllocatedValueBaseType)
    }

    override fun toString(): String {
        return "EmergeStruct[${irStruct.fqn}]"
    }

    companion object {
        fun fromLlvmStructWithoutBody(
            context: EmergeLlvmContext,
            structRef: LLVMTypeRef,
            irStruct: IrStruct,
        ): EmergeStructType {
            val baseElements = listOf(
                EmergeHeapAllocatedValueBaseType
            ).map { it.getRawInContext(context) }

            irStruct.members.forEachIndexed { index, member ->
                member.indexInLlvmStruct = baseElements.size + index
            }

            val emergeMemberTypesRaw = irStruct.members.map { context.getReferenceSiteType(it.type).getRawInContext(context) }
            val llvmMemberTypesRaw = (baseElements + emergeMemberTypesRaw).toTypedArray()

            val llvmStructElements = PointerPointer(*llvmMemberTypesRaw)
            LLVM.LLVMStructSetBody(structRef, llvmStructElements, llvmMemberTypesRaw.size, 0)

            check(LLVM.LLVMOffsetOfElement(context.targetData.ref, structRef, 0) == 0L) {
                "Cannot reinterpret emerge type ${irStruct.fqn} as Any"
            }

            return EmergeStructType(context, structRef, irStruct)
        }

        context(BasicBlockBuilder<EmergeLlvmContext, *>)
        internal fun GetElementPointerStep<EmergeStructType>.anyValueBase(): GetElementPointerStep<EmergeHeapAllocatedValueBaseType> {
            return stepUnsafe(context.i32(0), EmergeHeapAllocatedValueBaseType)
        }

        context(BasicBlockBuilder<EmergeLlvmContext, *>)
        fun GetElementPointerStep<EmergeStructType>.member(member: IrStruct.Member): GetElementPointerStep<LlvmType> {
            if (member.isCPointerPointed) {
                return this as GetElementPointerStep<LlvmType>
            }

            check(member in this@member.pointeeType.irStruct.members)
            return stepUnsafe(context.i32(member.indexInLlvmStruct!!), context.getReferenceSiteType(member.type))
        }
    }
}