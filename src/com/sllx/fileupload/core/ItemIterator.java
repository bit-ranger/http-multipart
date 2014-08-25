package com.sllx.fileupload.core;



import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.Locale;
import java.util.NoSuchElementException;

class ItemIterator{
    private static final String CONTENT_TYPE = "Content-type";
    private static final String MULTIPART = "multipart/";
    private static final String MULTIPART_FORM_DATA = "multipart/form-data";
    private static final String MULTIPART_MIXED = "multipart/mixed";
    private static final String CONTENT_DISPOSITION = "Content-disposition";
    private static final String FORM_DATA = "form-data";
    private static final String ATTACHMENT = "attachment";

    /**
     * 多个Stream段的持有者，可以从其中获取一段一段的Stream
     */
    private final MultipartStream multi;

    /**
     * 运行过程的记录器，记录已读取字节数，总条目数等
     */
    private final MultipartStream.ProgressNotifier notifier;

    /**
     * 分界线
     */
    private final byte[] boundary;

    /**
     * 当前条目
     */
    private ItemImpl currentItem;

    /**
     * 当前条目的字段名(form中的name值)
     */
    private String currentFieldName;

    /**
     * 是否跳过前言
     */
    private boolean skipPreamble;

    /**
     * 当前条目是否仍在被读取
     */
    private boolean itemValid;

    /**
     * 是否读到了文件末尾
     */
    private boolean eof;

    ItemIterator(RequestContext ctx) throws IOException{
        String contentType = ctx.getContentType();
        if ((null == contentType) || (!contentType.toLowerCase(Locale.ENGLISH).startsWith(MULTIPART))) {
            throw new RuntimeException(
                    String.format("the request doesn't contain a %s or %s stream, content type header is %s",
                            MULTIPART_FORM_DATA, MULTIPART_MIXED, contentType));
        }
        InputStream input = ctx.getInputStream();
        boundary = getBoundary(contentType);
        notifier = new MultipartStream.ProgressNotifier();
        multi = new MultipartStream(input, boundary, notifier);
        multi.setHeaderEncoding(ctx.getCharacterEncoding());
        skipPreamble = true;
        findNextItem();
    }


    /**
     * @return 是否找到下一个条目
     */
    private boolean findNextItem() throws IOException {
        //读到文件末尾,返回false
        if(eof) {
            return false;
        }
        if(currentItem != null){
            //关闭时，Stream将跳过剩余字节，直到发现下一个boundary
            currentItem.close();
            currentItem = null;
        }
        while (true){
            boolean hasNextItem; //是否存在下一个条目
            if(skipPreamble){
                hasNextItem = multi.skipPreamble();
            }else {
                hasNextItem = multi.readBoundary();
            }
            if(!hasNextItem){
                if(currentFieldName == null){
                    //multipart终止,没有更多数据
                    eof = true;
                    multi.close();
                    return false;
                }
                multi.setBoundary(boundary);
                currentFieldName = null;
                continue;
            }
            //如果存在下一条目，解析itme的Headers
            HeaderContext headers = getParsedHeaders(multi.readHeaders());
            //当前条目字段名不存在,混合类型时使用(多个文件使用同一字段)
            if (currentFieldName == null) {
                //字段名
                String fieldName = getFieldName(headers);
                //字段名为空,当前item将被舍弃
                if (fieldName != null) {
                    String subContentType = headers.getHeader(CONTENT_TYPE);
                    //mixed需要重新指定boundary,保留字段名,舍弃body体
                    if (subContentType != null &&  subContentType.toLowerCase(Locale.ENGLISH).startsWith(MULTIPART_MIXED)) {
                        currentFieldName = fieldName;
                        byte[] subBoundary = getBoundary(subContentType);
                        multi.setBoundary(subBoundary);
                        skipPreamble = true;
                        continue;
                    }
                    //文件名
                    String fileName = getFileName(headers);
                    currentItem = new ItemImpl(fileName,
                            fieldName, headers.getHeader(CONTENT_TYPE),
                            fileName == null);
                    currentItem.setHeaders(headers);
                    notifier.noteItem();
                    itemValid = true;
                    return true;
                }
            } else {
                String fileName = getFileName(headers);
                if (fileName != null) {
                    currentItem = new ItemImpl(fileName,
                            currentFieldName,
                            headers.getHeader(CONTENT_TYPE),
                            false);
                    currentItem.setHeaders(headers);
                    notifier.noteItem();
                    itemValid = true;
                    return true;
                }
            }
            //如果没有创建一个Item的条件(比如没有字段名),将跳过此Item的字节
            multi.discardBodyData();
        }
    }

    /**
     * @param contentType  contentType完整字符串
     *
     * @return 分界线的byte数组
     */
    private byte[] getBoundary(String contentType) {
        String boundaryStr = contentType.replaceAll(".*boundary=", "").trim();
        byte[] boundary;
        try {
            boundary = boundaryStr.getBytes("ISO-8859-1");
        } catch (UnsupportedEncodingException e) {
            boundary = boundaryStr.getBytes(); // Intentionally falls back to default charset
        }
        return boundary;
    }


    /**
     * <p> 解析 <code>header-part</code> 并返回键值对
     *
     * <p> 如果有多个header的name相同，那么name的值将以逗号分隔列表的形式返回
     *
     * @param headerPart    当前包裹的 <code>header-part</code>
     *
     * @return 一个包含HTTP Request Header 的映射
     */
    private HeaderContext getParsedHeaders(String headerPart) {
        final int len = headerPart.length();
        HeaderContext headers = new HeaderContext();
        int start = 0;
        for (;;) {
            int end = parseEndOfLine(headerPart, start);
            if (start == end) {
                break;
            }
            StringBuilder header = new StringBuilder(headerPart.substring(start, end));
            start = end + 2;
            //若行首有特殊字符('' or '\t')将"\r\n"替换为" "并清除特殊字符
            while (start < len) {
                int nonWs = start;
                //记录连续的空白或者制表符的末尾索引,预计出现在一行的开头
                while (nonWs < len) {
                    char c = headerPart.charAt(nonWs);
                    if (c != ' '  &&  c != '\t') {
                        break;
                    }
                    ++nonWs;
                }
                if (nonWs == start) {
                    break;
                }
                // 跳过空白或制表符解析一行
                end = parseEndOfLine(headerPart, nonWs);
                //裁剪空白符或制表符末尾至end的部分,拼接至header,用空格隔开
                header.append(" ").append(headerPart.substring(nonWs, end));
                //start跳过空格
                start = end + 2;
            }
            parseHeaderLine(headers, header.toString());
        }
        return headers;
    }

    /**
     * 从指定位置开始找到下一个"\r\n"的索引
     * @param headerPart header
     * @param start 开始位置
     * @return  \r\n 序列的索引，该序列标志的一行结束
     */
    private int parseEndOfLine(String headerPart, int start) {
        int index = start;
        for (;;) {
            int offset = headerPart.indexOf('\r', index);
            if (offset == -1  ||  offset + 1 >= headerPart.length()) {
                throw new IllegalStateException(
                        "Expected headers to be terminated by an empty line.");
            }
            if (headerPart.charAt(offset + 1) == '\n') {
                return offset;
            }
            index = offset + 1;
        }
    }

    /**
     * 解析header
     * @param headers HeaderContext
     * @param header 整个header字符串
     */
    private void parseHeaderLine(HeaderContext headers, String header) {
        final int colonOffset = header.indexOf(':');
        if (colonOffset == -1) {
            //没有冒号的行忽略
            return;
        }
        String headerName = header.substring(0, colonOffset).trim();
        String headerValue =
                header.substring(header.indexOf(':') + 1).trim();
        headers.addHeader(headerName, headerValue);
    }


    /**
     * 从<code>Content-disposition</code>中检索字段名
     *
     * @param headers HeaderContext
     *
     * @return 字段名
     */
    private String getFieldName(HeaderContext headers) {
        String pContentDisposition = headers.getHeader(CONTENT_DISPOSITION);
        String fieldName = null;
        if (pContentDisposition != null && pContentDisposition.toLowerCase(Locale.ENGLISH).startsWith(FORM_DATA)) {
            for (String desc : pContentDisposition.split(";")) {
                desc = desc.trim();
                String[] kv = desc.split("=");
                if(kv.length > 1 && kv[0].equals("name")){
                    fieldName = strip(kv[1]);
                    break;
                }
            }
            if (fieldName != null) {
                fieldName = fieldName.trim();
            }
        }
        return fieldName;
    }


    /**
     * 从<code>Content-disposition</code>中检索文件名
     *
     * @param headers HeaderContext
     *
     * @return 文件名
     */
    private String getFileName(HeaderContext headers) {
        String pContentDisposition = headers.getHeader(CONTENT_DISPOSITION);
        String fileName = null;
        boolean hasFileName = false;
        if (pContentDisposition != null) {
            String cdl = pContentDisposition.toLowerCase(Locale.ENGLISH);
            if (cdl.startsWith(FORM_DATA) || cdl.startsWith(ATTACHMENT)) {
                for (String desc : pContentDisposition.split(";")) {
                    desc = desc.trim();
                    String[] kv = desc.split("=");
                    if(kv.length > 1 && kv[0].equals("filename")){
                        hasFileName = true;
                        fileName = strip(kv[1]);
                        break;
                    }
                }
                if(!hasFileName){
                    return null;
                }
                if (fileName != null) {
                    fileName = fileName.trim();
                } else {
                    //如果fileName == null, 这种情况应该不会发生
                    fileName = "";
                }
            }
        }
        return fileName;
    }


    /**
     * @param str 字符串
     * @return 剥去外层引号后的字符串
     */
    private String strip(String str){
        char[] chars = str.toCharArray();
        if(chars[0] == '"' && chars[chars.length-1] == '"'){
            return str.substring(1, chars.length - 1);
        }
        return str;
    }


    /**
     * 返回是否有其他的 {@link Item} 可用
     *
     * @throws IOException Reading the file item failed.
     * @return True, 如果有1个或更多额外的条目可用；否则返回false
     */
    boolean hasNext() throws IOException{
        if (eof) {
            return false;
        }
        if (itemValid) {
            return true;
        }
        return findNextItem();
    }

    /**
     * 返回下一个可用的 {@link Item}.
     *
     * @throws java.util.NoSuchElementException No more items are
     *   available. Use {@link #hasNext()} to prevent this exception.
     * @throws IOException Reading the file item failed.
     * @return 返回一个Item实例，该实例提供了访问下一个文件条目的入口
     */
    Item next() throws IOException{
        if (eof  ||  (!itemValid && !hasNext())) {
            throw new NoSuchElementException();
        }
        itemValid = false;
        return currentItem;
    }

    private class ItemImpl implements Item {

        /**
         * 当前条目的contentType
         */
        private final String contentType;

        /**
         * 字段名(即input文本框name属性的值)
         */
        private final String fieldName;

        /**
         * 条目名(即文件名)
         */
        private final String name;

        /**
         * 该条目是否为表单域
         */
        private final boolean isFormField;

        /**
         * 该条目的流
         */
        private final InputStream stream;

        /**
         * 当前条目是否已打开
         */
        private boolean opened;

        /**
         * The headers, if any.
         */
        private HeaderContext headers;

        /**
         * 创建一个新的对象
         * @param name 文件名
         * @param fieldName 字段名
         * @param contentType ContentType
         * @param isFormField 是否为表单域
         * @throws java.io.IOException Creating the file item failed.
         */
        ItemImpl(String name, String fieldName,
             String contentType, boolean isFormField) throws IOException {
            this.name = name;
            this.fieldName = fieldName;
            this.contentType = contentType;
            this.isFormField = isFormField;
            this.stream = multi.newInputStream();
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public String getFieldName() {
            return fieldName;
        }

        @Override
        public String getFileName() {
            return name;
        }

        @Override
        public boolean isFormField() {
            return isFormField;
        }

        @Override
        public InputStream openStream() throws IOException {
            if (opened) {
                throw new IllegalStateException(
                        "The stream was already opened.");
            }
            opened = true;
            return stream;
        }

        void close() throws IOException {
            stream.close();
        }

        @Override
        public void setHeaders(HeaderContext pHeaders) {
            headers = pHeaders;
        }

        @Override
        public HeaderContext getHeaders() {
            return headers;
        }
    }
}
