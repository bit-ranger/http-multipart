package com.sllx.fileupload.core;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;

import static java.lang.String.format;


class RequestContext {

    private static final String CONTENT_LENGTH = "Content-length";

    private final HttpServletRequest request;

    public RequestContext(HttpServletRequest request) {
        this.request = request;
    }

    public String getCharacterEncoding() {
        return request.getCharacterEncoding();
    }

    public String getContentType() {
        return request.getContentType();
    }

    public long contentLength() {
        long size;
        try {
            size = Long.parseLong(request.getHeader(CONTENT_LENGTH));
        } catch (NumberFormatException e) {
            size = request.getContentLength();
        }
        return size;
    }

    public InputStream getInputStream() throws IOException {
        return request.getInputStream();
    }

    @Override
    public String toString() {
        return format("ContentLength=%s, ContentType=%s",Long.valueOf(this.contentLength()),this.getContentType());
    }

}
