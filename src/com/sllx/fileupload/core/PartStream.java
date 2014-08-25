package com.sllx.fileupload.core;

import com.sllx.fileupload.util.Streams;

import java.io.*;

class PartStream implements Part{

    private ByteArrayOutputStream stream;
    private String fieldName;
    private String fileName;
    private boolean isFormField;
    private String characterEncoding;

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
        String value = null;
        if(characterEncoding != null){
            try {
                value = stream.toString(characterEncoding);
            } catch (UnsupportedEncodingException e) {
                //若平台不知道指定的编码,将使用默认值
                value = stream.toString();
            }
        } else {
            value = stream.toString();
        }
        return value;
    }

    public void setCharacterEncoding(String characterEncoding) {
        this.characterEncoding = characterEncoding;
    }
}
