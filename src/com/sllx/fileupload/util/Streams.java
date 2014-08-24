package com.sllx.fileupload.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class Streams {

    private static final int DEFAULT_BUFFER_SIZE = 8192;

    public static long copy(InputStream inputStream, OutputStream outputStream, boolean closeOutputStream)
            throws IOException {
        return copy(inputStream, outputStream, closeOutputStream, new byte[DEFAULT_BUFFER_SIZE]);
    }

    public static long copy(InputStream inputStream,
                            OutputStream outputStream, boolean closeOutputStream,
                            byte[] buffer)
            throws IOException {
        OutputStream out = outputStream;
        InputStream in = inputStream;
        try {
            long total = 0;
            for (;;) {
                int res = in.read(buffer);
                if (res == -1) {
                    break;
                }
                if (res > 0) {
                    total += res;
                    if (out != null) {
                        out.write(buffer, 0, res);
                    }
                }
            }
            if (out != null) {
                if (closeOutputStream) {
                    out.close();
                } else {
                    out.flush();
                }
                out = null;
            }
            in.close();
            in = null;
            return total;
        } finally {
            if(in != null){
                in.close();
            }
            if (closeOutputStream) {
                if(out != null){
                    out.close();
                }
            }
        }
    }
}