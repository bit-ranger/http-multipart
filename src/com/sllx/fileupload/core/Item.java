package com.sllx.fileupload.core;

import java.io.*;

interface Item {
    InputStream openStream() throws IOException;
    String getContentType();
    String getFileName();
    String getFieldName();
    boolean isFormField();
    HeaderContext getHeaders();
    void setHeaders(HeaderContext headers);
}
