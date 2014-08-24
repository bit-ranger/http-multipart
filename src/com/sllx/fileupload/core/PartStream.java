package com.sllx.fileupload.core;

import com.sllx.fileupload.util.Streams;

import java.io.*;

class PartStream implements Part{

    private ByteArrayOutputStream stream;
    private String fieldName;
    private String fileName;
    private boolean isFormField;

    PartStream(String fieldName, InputStream input, String fileName) throws IOException {
        stream = new ByteArrayOutputStream();
        //ByteArrayOutputStream#close() 什么都不做
        Streams.copy(input, stream, false);
        this.fieldName = fieldName;
        this.fileName = fileName;
        this.isFormField = fileName == null;
    }

    @Override
    public void write(File file) throws IOException{
        FileOutputStream fos = new FileOutputStream(file);
        stream.writeTo(fos);
        stream.close();
        fos.close();
    }

    @Override
    public String getField() {
        return fieldName;
    }

    @Override
    public String getFileName() {
        return fileName;
    }

    @Override
    public boolean isFormField() {
        return isFormField;
    }

    @Override
    public String getValue() {
        return stream.toString();
    }
}
