package io.github.tmarsteel.emerge.backend.api.ir

sealed interface IrExecutable

interface IrCodeChunk : IrExecutable {
    val components: List<IrExecutable>
}

/**
 * Temporary values behave just like `val` variables in emerge source. Additionally, any compound expression, e.g.
 * `a + 3` can only reference temporaries for the nested parts. This makes expressions in this IR [SSA](https://en.wikipedia.org/wiki/Static_single-assignment_form).
 *
 * The purpose of this is to allow the frontend to do the heavy lifting of figuring out when and which references
 * need to be counted, including all the optimizations on that. This allows backend code to be much simpler and simply
 * rely on [IrCreateStrongReferenceStatement] and [IrDropStrongReferenceStatement] nodes to generate correctly reference-counted
 * code.
 */
interface IrCreateTemporaryValue : IrExecutable {
    val value: IrExpression
    val type: IrType get() = value.evaluatesTo
}

/**
 * The reference-count on an object needs to be incremented.
 *
 * **Caveats:**
 * * the frontend will emit these for values of **all** types.
 * * the frontend may omit [IrCreateStrongReferenceStatement] and [IrDropStrongReferenceStatement] IR nodes when it can prove
 *   that the mutation of the reference counter cannot be observed by the input program.
 */
interface IrCreateStrongReferenceStatement : IrExecutable {
    /** the temporary holding the reference to the object whichs reference count needs to increased */
    val reference: IrCreateTemporaryValue
}

/**
 * A reference has reached the end of its lifetime. The reference counter of the referred object must be
 * decremented and if it reaches 0, the object must be finalized.
 *
 * **Attention!!:** there are caveats, see [IrCreateStrongReferenceStatement]
 */
interface IrDropStrongReferenceStatement : IrExecutable {
    val reference: IrTemporaryValueReference
}

/**
 * The declaration of a re-assignable or not-re-assignable variable. The frontend may choose to convert any
 * not-re-assignable variable into a [IrCreateTemporaryValue] and corresponding [IrTemporaryValueReference.Temporary]s,
 * as the semantics are identical.
 */
interface IrVariableDeclaration : IrExecutable {
    val name: String
    val type: IrType
    val isBorrowed: Boolean
}

interface IrAssignmentStatement : IrExecutable {
    val target: Target
    val value: IrTemporaryValueReference

    sealed interface Target {
        val type: IrType

        interface Variable : Target {
            val declaration: IrVariableDeclaration
            override val type: IrType get() = declaration.type
        }
        interface ClassMemberVariable : Target {
            val objectValue: IrTemporaryValueReference
            val memberVariable: IrClass.MemberVariable
            override val type: IrType get() = memberVariable.type
        }
    }
}

interface IrReturnStatement : IrExecutable {
    val value: IrTemporaryValueReference
}

interface IrWhileLoop : IrExecutable {
    val condition: IrExpression
    val body: IrCodeChunk
}

/**
 * Stop executing the loop body and jump to the code after the loop
 */
interface IrBreakStatement : IrExecutable {
    /**
     * the loop to break out from; is guaranteed to be a parent of this statement. Or in
     * other words, `this` statement is guaranteed to be located in the body of the loop it is breaking out from
     */
    val fromLoop: IrWhileLoop
}

/**
 * Stop executing the loop body and jump back to the loop condition, starting the body again should the condition
 * still hold
 */
interface IrContinueStatement : IrExecutable {
    /**
     * the loop to continue; is guaranteed to be a parent of this statement. Or in
     * other words, `this` statement is guaranteed to be located in the body of the loop it is breaking out from
     */
    val loop: IrWhileLoop
}

/**
 * The counterpart to [IrDeallocateObjectStatement]. It makes the memory occupied by the given reference.
 *
 * The frontend must emit code prior to this statement that ensures that
 * * any references stored/nested in the object are dropped (see [IrDropStrongReferenceStatement])
 * * no other references ot the object, including weak ones, exist. This is usually the job of the backend as
 *   part of [IrDropStrongReferenceStatement]; Hence, the only safe place for the frontend to put this code is in the
 *   finalizer of a class (see [IrClass.destructor]). Backends *may* emit code that throws an exception if this
 *   statement is called on an object that still has live references.
 *
 * The backend must emit code that achieves these things for this statement:
 * * make the memory available for use by other [IrAllocateObjectExpression]s again.
 */
interface IrDeallocateObjectStatement : IrExecutable {
    val value: IrTemporaryValueReference
}

/**
 * The frontend must emit these alongside the construction of `emerge.core.Weak` instances. A weak
 * reference needs to be registered with the referred object.
 */
interface IrRegisterWeakReferenceStatement : IrExecutable {
    val referenceStoredIn: IrAssignmentStatement.Target.ClassMemberVariable
    val referredObject: IrTemporaryValueReference
}

/**
 * The frontend must emit these alongside the destruction of `emerge.core.Weak` instances. A weak
 * reference needs to be de-registered with the referred object.
 */
interface IrUnregisterWeakReferenceStatement : IrExecutable {
    val referenceStoredIn: IrAssignmentStatement.Target.ClassMemberVariable
    val referredObject: IrTemporaryValueReference
}