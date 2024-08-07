package emerge.platform

import emerge.linux.libc.write
import emerge.ffi.c.addressOfFirst

FD_STDIN: S32 = 0
FD_STDOUT: S32 = 1
FD_STDERR: S32 = 2

export mut nothrow fn print(str: String) {
    // TODO: rewrite in terms of PrintStream
    write(FD_STDOUT, str.utf8Data.addressOfFirst(), str.utf8Data.size)
}

export mut nothrow fn printError(str: String) {
    // TODO: rewrite in terms of PrintStream
    write(FD_STDERR, str.utf8Data.addressOfFirst(), str.utf8Data.size)
}