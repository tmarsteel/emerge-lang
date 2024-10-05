package emerge.platform

import emerge.std.io.PrintStream
import emerge.linux.libc.write
import emerge.linux.libc.getErrno
import emerge.ffi.c.addressOfFirst
import emerge.ffi.c.COpaquePointer
import emerge.std.io.IOException
import emerge.std.collections.ArrayList

StandardOut: mut PrintStream = FileDescriptorPrintStream(FD_STDOUT)
StandardError: mut PrintStream = FileDescriptorPrintStream(FD_STDERR)

class FileDescriptorPrintStream : PrintStream {
    private fd: S32 = init
    
    override fn put(self: mut _, str: String) {
        var bufToWrite: read Array<S8> = str.utf8Data
        while true {
            writeResult = pureWrite(self.fd, bufToWrite.addressOfFirst(), bufToWrite.size)
            if writeResult <= 0 {
                throw WriteFailedException(getErrno())
            }
            
            nBytesWritten = writeResult.asUWord()
            if nBytesWritten >= bufToWrite.size {
                break
            }
            
            // workaround until there are array slices / pointer arithmetic
            newBuf: mut _ = Array.new::<S8>(bufToWrite.size - nBytesWritten, 0 as S8)
            Array.copy(bufToWrite, nBytesWritten, newBuf, 0, newBuf.size)
            set bufToWrite = newBuf
        }
    }
    
    override fn putEndOfLine(self: mut _) {
        self.put("\n")
    }
}

private intrinsic nothrow fn pureWrite(fd: S32, buf: COpaquePointer, count: UWord) -> SWord

private class WriteFailedException : IOException {
    errno: S32 = init
    
    constructor {
        mixin ThrowableTrait("write(2) failed, errno = " + self.errno.toString()) as Throwable
    }
}