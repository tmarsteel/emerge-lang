package emerge.core

import emerge.std.io.PrintStream
import emerge.std.collections.ArrayList
import emerge.core.reflection.reflectType
import emerge.platform.collectStackTrace

export interface Throwable : Printable {
    // TODO: make into virtual property
    export nothrow fn getMessage(self) -> String?
    
    // on the first invocation of this function, saves information on the stack trace
    // to be later returned from getStackTrace
    // called by the throw keyword, so that user-code doesn't need to
    // deal with the necessary read side-effect of collectStackTrace
    export read fn fillStackTrace(self: mut _)
    
    // @return the stack trace collected on the first call to [fillStackTrace], or `null` if
    // it was never called or didn't succeed
    // TODO: make into virtual property
    // TODO: return type Iterable<...>
    export nothrow fn getStackTrace(self) -> const ArrayList<StackTraceElement>?

    export override fn printTo(self, borrow target: mut PrintStream) {
        target.put(self.reflectType().canonicalName)
        target.put(": ")
        target.put(self.getMessage() ?: "<no message>")
        target.putEndOfLine()
        
        stackTrace = self.getStackTrace()
        if isNull(stackTrace) {
            target.put("  ! stack trace not set")
            return
        }
        var i = 0 as UWord
        while i < stackTrace!!.size() {
            target.put("  at ")
            stackTrace!![i].printTo(target)
            target.putEndOfLine()
            set i = i + 1
        }
    }
}
export interface Error : Throwable {}

// implements boilerplate code for all Throwables; intended to be used
// as a delegation implementation in Throwables.
// implements Error so it can be used in both Error and Exception classes
export class ThrowableTrait : Error {
	message: String? = init

    private var stackTrace: const ArrayList<StackTraceElement>? = null

    export override read fn fillStackTrace(self: mut _) {
        set self.stackTrace = self.stackTrace ?: collectStackTrace(2 as U32, false)
    }

    export override nothrow fn getStackTrace(self) -> const ArrayList<StackTraceElement>? {
        return self.stackTrace
    }

    export override nothrow fn getMessage(self) = self.message
}

export class StackTraceElement : Printable {
    export address: UWord = init
    export procedureName: String = init
    
    export override fn printTo(self, borrow target: mut PrintStream) {
        target.put(self.procedureName)
        target.put(" (")
        self.address.printTo(target)
        target.put(")")
    }
}
