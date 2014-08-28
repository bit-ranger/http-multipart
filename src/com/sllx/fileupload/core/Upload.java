package com.sllx.fileupload.core;


import javax.servlet.http.HttpServletRequest;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

public class Upload {

    public List<Part> parseRequest(HttpServletRequest request) throws IOException {
        RequestContext ctx  = new RequestContext(request);
        ArrayList<Part> fileParts = new ArrayList<Part>();
        ItemIterator iter = new ItemIterator(ctx);
        while (iter.hasNext()){
            Item item = iter.next();
            PartStream part = new PartStream(item.getFieldName(), item.openStream(), item.getFileName(), ctx.getCharacterEncoding());
            fileParts.add(part);
        }
        fileParts.trimToSize();
        return fileParts;
    }
}
