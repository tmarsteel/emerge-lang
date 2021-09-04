package compiler

import java.util.Objects

private fun getInvocationStackFrame(): StackTraceElement = Thread.currentThread().stackTrace[3]

private class ContextAtStackFrame(
    val context: Any,
    val stackFrame: StackTraceElement,
) {
    override fun equals(other: Any?): Boolean {
        if (other !is ContextAtStackFrame) {
            return false
        }

        return other.context === this.context && other.stackFrame == this.stackFrame
    }

    override fun hashCode(): Int {
        return Objects.hash(System.identityHashCode(context), stackFrame.hashCode())
    }

    override fun toString() = "$context at $stackFrame"
}

class EarlyStackOverflowException() : RuntimeException("Stack overflow")

private val contexts: ThreadLocal<MutableSet<ContextAtStackFrame>> = ThreadLocal.withInitial(::HashSet)

fun <R> throwOnCycle(context: Any, action: () -> R): R {
    val invocationContext = ContextAtStackFrame(context, getInvocationStackFrame())
    val threadContexts = contexts.get()
    if (invocationContext in threadContexts) {
        throw EarlyStackOverflowException()
    }
    threadContexts.add(invocationContext)

    return try {
        action()
    } finally {
        threadContexts.remove(invocationContext)
        if (threadContexts.isEmpty()) {
            contexts.remove()
        }
    }
}
