package com.sllx.fileupload.core;

import java.io.File;
import java.io.IOException;

public interface Part {
    void write(File fIle) throws IOException;
    String getField();
    String getFileName();
    boolean isFormField();
    String getValue();
}
