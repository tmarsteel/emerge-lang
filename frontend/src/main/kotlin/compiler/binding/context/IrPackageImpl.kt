package compiler.binding.context

import compiler.binding.expression.BoundExpression
import io.github.tmarsteel.emerge.backend.api.CodeGenerationException
import io.github.tmarsteel.emerge.backend.api.ir.IrBaseType
import io.github.tmarsteel.emerge.backend.api.ir.IrClass
import io.github.tmarsteel.emerge.backend.api.ir.IrFunction
import io.github.tmarsteel.emerge.backend.api.ir.IrGlobalVariable
import io.github.tmarsteel.emerge.backend.api.ir.IrInterface
import io.github.tmarsteel.emerge.backend.api.ir.IrOverloadGroup
import io.github.tmarsteel.emerge.backend.api.ir.IrPackage
import io.github.tmarsteel.emerge.backend.api.ir.IrVariableDeclaration
import io.github.tmarsteel.emerge.common.CanonicalElementName

internal class IrPackageImpl(
    packageContext: PackageContext,
) : IrPackage {
    override val name: CanonicalElementName.Package = packageContext.packageName
    override val functions: Set<IrOverloadGroup<IrFunction>> = packageContext
        .allToplevelFunctionOverloadSets
        .map { it.toBackendIr() }
        .toSet()

    private val irBaseTypes: Sequence<IrBaseType> = packageContext.sourceFiles
        .flatMap { it.context.types }
        .map { it.toBackendIr() }

    override val classes: Set<IrClass> = irBaseTypes
        .filterIsInstance<IrClass>()
        .toSet()

    override val interfaces: Set<IrInterface> = irBaseTypes
        .filterIsInstance<IrInterface>()
        .toSet()

    override val variables: Set<IrGlobalVariable> = packageContext.sourceFiles
        .flatMap { it.context.variables }
        .map {
            val initializer = it.initializerExpression
                ?: throw CodeGenerationException("Missing initializer for global variable ${this.name.plus(it.name)}")

            IrGlobalVariableImpl(
                CanonicalElementName.Global(packageContext.packageName, it.name),
                it.backendIrDeclaration,
                initializer,
            )
        }
        .toSet()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as IrPackageImpl

        return name == other.name
    }

    override fun hashCode(): Int {
        return name.hashCode()
    }

    override fun toString() = name.toString()
}

internal class IrGlobalVariableImpl(
    override val name: CanonicalElementName.Global,
    override val declaration: IrVariableDeclaration,
    private val boundInitialValue: BoundExpression<*>,
) : IrGlobalVariable {
    override val initializer get() = boundInitialValue.toBackendIrExpression()
    override val declaredAt = boundInitialValue.declaration.span
}