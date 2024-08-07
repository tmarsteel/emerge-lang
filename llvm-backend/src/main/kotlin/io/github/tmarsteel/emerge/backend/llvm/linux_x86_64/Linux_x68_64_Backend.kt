package io.github.tmarsteel.emerge.backend.llvm.linux_x86_64

import com.sun.jna.ptr.PointerByReference
import io.github.tmarsteel.emerge.backend.SystemPropertyDelegate.Companion.systemProperty
import io.github.tmarsteel.emerge.backend.api.CanonicalElementName
import io.github.tmarsteel.emerge.backend.api.CodeGenerationException
import io.github.tmarsteel.emerge.backend.api.EmergeBackend
import io.github.tmarsteel.emerge.backend.api.ModuleSourceRef
import io.github.tmarsteel.emerge.backend.api.ir.IrSoftwareContext
import io.github.tmarsteel.emerge.backend.llvm.Autoboxer
import io.github.tmarsteel.emerge.backend.llvm.autoboxer
import io.github.tmarsteel.emerge.backend.llvm.dsl.KotlinLlvmFunction
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmCompiler
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmFunction
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmFunctionType
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmI32Type
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmPointerType
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmPointerType.Companion.pointerTo
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmTarget
import io.github.tmarsteel.emerge.backend.llvm.dsl.LlvmVoidType
import io.github.tmarsteel.emerge.backend.llvm.dsl.PassBuilderOptions
import io.github.tmarsteel.emerge.backend.llvm.getClasspathResourceAsFileOnDisk
import io.github.tmarsteel.emerge.backend.llvm.intrinsics.EmergeLlvmContext
import io.github.tmarsteel.emerge.backend.llvm.jna.Llvm
import io.github.tmarsteel.emerge.backend.llvm.jna.LlvmVerifierFailureAction
import io.github.tmarsteel.emerge.backend.llvm.linux.EmergeEntrypoint
import io.github.tmarsteel.emerge.backend.llvm.linux.LinuxLinker
import io.github.tmarsteel.emerge.backend.llvm.llvmRef
import io.github.tmarsteel.emerge.backend.llvm.packagesSeq
import java.nio.file.Path
import java.nio.file.Paths

class Linux_x68_64_Backend : EmergeBackend {
    override val targetName = "linux-x86_64"

    override val targetSpecificModules: Collection<ModuleSourceRef> = setOf(
        ModuleSourceRef(FFI_C_SOURCES_PATH, CanonicalElementName.Package(listOf("emerge", "ffi", "c"))),
        ModuleSourceRef(LINUX_LIBC_SOURCES_PATH, CanonicalElementName.Package(listOf("emerge", "linux", "libc"))),
        ModuleSourceRef(LINUX_PLATFORM_PATH, CanonicalElementName.Package(listOf("emerge", "platform"))),
    )

    override fun emit(softwareContext: IrSoftwareContext, directory: Path) {
        val bitcodeFilePath = directory.resolve("out.bc").toAbsolutePath()
        writeSoftwareToBitcodeFile(softwareContext, bitcodeFilePath)

        /* we cannot use LLVMTargetMachineEmitToFile because:
        it insists on using .ctor sections for static initializers, but these are LONG deprecated
        the c runtime only looks for init_array sections. For some reason, LLVMTargetMachineEmitToFile
        wants to use .ctors. In C++, one can configure this in the TargetOptions class by setting
        useInitArray to true. Though, there currently is no way to modify the C++ TargetOptions object
        that gets passed to TargetMachine::create via the C interface. I might submit a PR when i feel like
        it. If things like LLVMCreateTargetOptions and LLVMTargetOptionsSetUseInitArray get added to llvm-c,
        this roundtrip through the filesystem can be replaced by a direct call to LLVMTargetMachineEmitToFile.

        I've also read online that LLVMTargetMachineEmitToFile ignores the datalayout string and instead uses
        the default for the target-triple. Not a problem as of writing, since its not customized, but also a bug
        i don't want to have to re-discover when chasing a segfault or something like that...
         */

        val objectFilePath = directory.resolve("out.o")
        LlvmCompiler.compileBitcodeFile(
            bitcodeFilePath,
            objectFilePath,
        )

        val executablePath = directory.resolve("runnable").toAbsolutePath()
        LinuxLinker.linkObjectFilesToELF(
            listOf(
                // here is what all of these object files are for:
                // https://dev.gentoo.org/~vapier/crt.txt
                getClasspathResourceAsFileOnDisk(javaClass, "/io/github/tmarsteel/emerge/backend/llvm/x86_64-pc-linux-gnu/Scrt1.o"),
                getClasspathResourceAsFileOnDisk(javaClass, "/io/github/tmarsteel/emerge/backend/llvm/x86_64-pc-linux-gnu/crtbeginS.o"),
                objectFilePath,
                getClasspathResourceAsFileOnDisk(javaClass, "/io/github/tmarsteel/emerge/backend/llvm/x86_64-pc-linux-gnu/crtendS.o"),
                getClasspathResourceAsFileOnDisk(javaClass, "/io/github/tmarsteel/emerge/backend/llvm/x86_64-pc-linux-gnu/libunwind-x86_64.a"),
            ),
            executablePath,
            dynamicallyLinkAtRuntime = listOf("c"),
            libraryPaths = listOf(
                getClasspathResourceAsFileOnDisk(javaClass, "/io/github/tmarsteel/emerge/backend/llvm/x86_64-pc-linux-gnu/libc.so"),
            ).map { it.parent }.distinct(),
        )
    }

    private fun writeSoftwareToBitcodeFile(softwareContext: IrSoftwareContext, bitcodeFilePath: Path) {
        Llvm.LLVMInitializeX86TargetInfo()
        Llvm.LLVMInitializeX86Target()
        Llvm.LLVMInitializeX86TargetMC()
        Llvm.LLVMInitializeX86AsmPrinter()
        Llvm.LLVMInitializeX86AsmParser()

        EmergeLlvmContext.createDoAndDispose(LlvmTarget.fromTriple("x86_64-pc-linux-gnu")) { llvmContext ->
            softwareContext.packagesSeq
                .flatMap { it.interfaces }
                .forEach(llvmContext::registerBaseType)
            softwareContext.packagesSeq
                .flatMap { it.classes }
                .forEach(llvmContext::registerBaseType)
            softwareContext.packagesSeq
                .flatMap { it.interfaces }
                .flatMap { it.memberFunctions }
                .flatMap { it.overloads }
                .forEach(llvmContext::registerFunction)
            softwareContext.packagesSeq
                .flatMap { it.classes }
                .flatMap { it.memberFunctions }
                .flatMap { it.overloads }
                .forEach {
                    llvmContext.registerFunction(
                        it,
                        symbolNameOverride = FUNCTION_SYMBOL_NAME_OVERRIDES[it.canonicalName],
                    )
                }

            softwareContext.packagesSeq
                .flatMap { it.functions }
                .flatMap { it.overloads }
                .forEach {
                    val fn = llvmContext.registerFunction(
                        it,
                        symbolNameOverride = FUNCTION_SYMBOL_NAME_OVERRIDES[it.canonicalName]
                    )
                        ?: throw CodeGenerationException("toplevel fn not defined/declared in llvm - what?")

                    storeCoreFunctionReference(llvmContext, it.canonicalName, fn)
                }

            softwareContext.packagesSeq
                .flatMap { it.classes }
                .filter { it.autoboxer !is Autoboxer.PrimitiveType }
                .forEach(llvmContext::defineClassStructure)

            softwareContext.packagesSeq
                .flatMap { it.variables }
                .forEach {
                    llvmContext.registerGlobal(it)
                }

            softwareContext.packagesSeq
                .flatMap { it.functions }
                .flatMap { it.overloads }
                .filter { it.body != null }
                .forEach(llvmContext::defineFunctionBody)

            softwareContext.packagesSeq
                .flatMap { it.interfaces }
                .flatMap { it.memberFunctions }
                .flatMap { it.overloads }
                .filter { it.body != null }
                .forEach(llvmContext::defineFunctionBody)

            softwareContext.modules
                .flatMap { it.packages }
                .flatMap { it.classes }
                .forEach { clazz ->
                    clazz.memberFunctions
                        .flatMap { it.overloads }
                        .filter { it.body != null }
                        .forEach(llvmContext::defineFunctionBody)
                }

            llvmContext.registerIntrinsic(KotlinLlvmFunction.define("_Ux86_64_setcontext", LlvmI32Type) {
                val contextPtr by param(pointerTo(LlvmVoidType))
                body {
                    val setctxfnaddr = context.getNamedFunctionAddress("setcontext")!!
                    val setctffntype = LlvmFunctionType<LlvmI32Type>(LlvmI32Type, listOf(LlvmPointerType(LlvmVoidType)))
                    ret(call(setctxfnaddr, setctffntype, listOf(contextPtr)))
                }
            })

            // assure the entrypoint is in the object file
            llvmContext.registerIntrinsic(EmergeEntrypoint)
            llvmContext.complete()

            val errorMessageRef = PointerByReference()
            if (Llvm.LLVMPrintModuleToFile(
                llvmContext.module,
                bitcodeFilePath.parent.resolve("out.ll").toString(),
                errorMessageRef
            ) != 0) {
                val errorMessageStr = errorMessageRef.value.getString(0)
                Llvm.LLVMDisposeMessage(errorMessageRef.value)
                throw CodeGenerationException(errorMessageStr)
            }

            if (Llvm.LLVMVerifyModule(llvmContext.module, LlvmVerifierFailureAction.RETURN_STATUS, errorMessageRef) != 0) {
                val errorMessageStr = errorMessageRef.value.getString(0)
                Llvm.LLVMDisposeMessage(errorMessageRef.value)
                throw CodeGenerationException(errorMessageStr)
            }

            PassBuilderOptions().use { pbo ->
                val error = Llvm.LLVMRunPasses(
                    llvmContext.module,
                    "default<O0>",
                    llvmContext.targetMachine.ref,
                    pbo.ref,
                )
                if (error != null) {
                    val errorStrPtr = Llvm.LLVMGetErrorMessage(error)
                    val errorStr = errorStrPtr.getString(0)
                    Llvm.LLVMDisposeErrorMessage(errorStrPtr)
                    throw CodeGenerationException("LLVM passes failed: $errorStr")
                }
            }

            if (Llvm.LLVMWriteBitcodeToFile(llvmContext.module, bitcodeFilePath.toString()) != 0) {
                throw CodeGenerationException("Failed to write LLVM bitcode to $bitcodeFilePath")
            }
        }
    }

    private fun findMainFunction(softwareContext: IrSoftwareContext): LlvmFunction<*> {
        return softwareContext.modules
            .flatMap { it.packages }
            .flatMap { it.functions }
            .flatMap { it.overloads }
            .single { it.canonicalName.simpleName == "main" }
            .llvmRef!!
    }

    private fun storeCoreFunctionReference(context: EmergeLlvmContext, functionName: CanonicalElementName.Function, fn: LlvmFunction<*>) {
        when (functionName) {
            ALLOCATOR_FUNCTION_NAME -> {
                @Suppress("UNCHECKED_CAST")
                context.allocateFunction = fn as LlvmFunction<LlvmPointerType<LlvmVoidType>>
            }
            FREE_FUNCTION_NAME -> {
                @Suppress("UNCHECKED_CAST")
                context.freeFunction = fn as LlvmFunction<LlvmVoidType>
            }
            EXIT_FUNCTION_NAME -> {
                @Suppress("UNCHECKED_CAST")
                context.exitFunction = fn as LlvmFunction<LlvmVoidType>
            }
        }
    }

    companion object {
        val FFI_C_SOURCES_PATH by systemProperty("emerge.compiler.native.c-ffi-sources", Paths::get)
        val LINUX_LIBC_SOURCES_PATH by systemProperty("emerge.compiler.native.libc-wrapper.sources", Paths::get)
        val LINUX_PLATFORM_PATH by systemProperty("emerge.compiler.native.linux-platform.sources", Paths::get)

        private val LIBC_PACKAGE = CanonicalElementName.Package(listOf("emerge", "linux", "libc"))
        private val ALLOCATOR_FUNCTION_NAME = CanonicalElementName.Function(LIBC_PACKAGE, "malloc")
        private val FREE_FUNCTION_NAME = CanonicalElementName.Function(LIBC_PACKAGE, "free")
        private val EXIT_FUNCTION_NAME = CanonicalElementName.Function(LIBC_PACKAGE, "exit")

        private val FUNCTION_SYMBOL_NAME_OVERRIDES: Map<CanonicalElementName.Function, String> = mapOf(
            // find those by calling the c-pre-processor on libunwind.h
            CanonicalElementName.Function(
                CanonicalElementName.Package(listOf("emerge", "platform")),
                "unw_getcontext",
            ) to "getcontext",
            CanonicalElementName.Function(
                CanonicalElementName.Package(listOf("emerge", "platform")),
                "unw_init_local",
            ) to "_Ux86_64_init_local",
            CanonicalElementName.Function(
                CanonicalElementName.Package(listOf("emerge", "platform")),
                "unw_step",
            ) to "_Ux86_64_step",
            CanonicalElementName.Function(
                CanonicalElementName.Package(listOf("emerge", "platform")),
                "unw_get_proc_name",
            ) to "_Ux86_64_get_proc_name",
            CanonicalElementName.Function(
                CanonicalElementName.Package(listOf("emerge", "platform")),
                "unw_get_proc_info",
            ) to "_Ux86_64_get_proc_info",
            CanonicalElementName.Function(
                CanonicalElementName.Package(listOf("emerge", "platform")),
                "unw_get_reg",
            ) to "_Ux86_64_get_reg",
        )
    }
}

