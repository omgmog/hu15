// SPDX-License-Identifier: AGPL-3.0-or-later
package hu.hu15;

import java.io.IOException;

/** Byte pipe to the phone: TCP now (emulator/fake phone), USB bulk endpoints later. */
public interface Transport {
    void write(byte[] b, int off, int len) throws IOException;
    /** Blocks until exactly len bytes are read; IOException on EOF/timeout. */
    void readFully(byte[] b, int off, int len) throws IOException;
    void close();
}
