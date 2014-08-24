package com.sllx.fileupload.core;

import com.sllx.fileupload.util.Streams;

import java.io.*;

import static java.lang.String.format;

class MultipartStream {

    /**
     * 回车ASCII
     */
    private static final byte CR = 0x0D;

    /**
     * 换行ASCII
     */
    private static final byte LF = 0x0A;

    /**
     * (-)ASCII
     */
    private static final byte DASH = 0x2D;

    /**
     * header-part最大长度
     */
    private static final int HEADER_PART_SIZE_MAX = 10240;

    /**
     * 默认缓冲大小
     */
    private static final int DEFAULT_BUFSIZE = 4096;

    /**
     * header-part结尾处的标志,两个回车换行符(表现为空一行)
     */
    private static final byte[] HEADER_SEPARATOR = {CR, LF, CR, LF};

    /**
     * 分界线结尾处的标志,一个回车换行符
     */
    private static final byte[] FIELD_SEPARATOR = {CR, LF};

    /**
     *结束线结尾处的标志,两个短横(--)
     */
    private static final byte[] STREAM_TERMINATOR = {DASH, DASH};

    /**
     * 分界线前面的标志,回车换行加两个短横(<code>CRLF--</code>).
     */
    private static final byte[] BOUNDARY_PREFIX = {CR, LF, DASH, DASH};

    /**
     * 输入流对象
     */
    private final InputStream input;

    /**
     *必须保持的byte数据量,keepRegion范围内可能包含boundary
     */
    private final int keepRegion;

    /**
     *分界线加上前面标志(CRLF--)的总长度
     */
    private int boundaryLength;

    /**
     *分界线的byte数组形式, 用来分隔Stream
     */
    private final byte[] boundary;

    /**
     * 缓冲大小
     */
    private final int bufSize;

    /**
     * 缓冲数组
     */
    private final byte[] buffer;

    /**
     * 缓冲中的第一个有效字符的索引
     * <br>
     * 0 <= head < bufSize
     */
    private int head;

    /**
     * 缓冲中最后一个有效字符的索引+1
     * <br>
     * 0 <= tail <= bufSize
     */
    private int tail;

    /**
     * 读取 headers 时使用的编码.
     */
    private String headerEncoding;

    /**
     * 记录器
     */
    private final ProgressNotifier notifier;


    /**
     * <p>提供了默认缓冲大小的重载构造方法</p>
     * @see MultipartStream#MultipartStream(InputStream, byte[], int, ProgressNotifier)
     * @param input    输入流
     * @param boundary 分界线
     * @param notifier 通知器
     */
    MultipartStream(InputStream input, byte[] boundary, ProgressNotifier notifier ){
        this(input, boundary, DEFAULT_BUFSIZE, notifier);
    }


    /**
     * <p> 缓冲大小必须至少足够容纳一个分界线加上4个字符(回车/换行以及两个短横),再加上至少一个byte,
     * 太小的缓冲会降低性能</p>
     * @param input    输入流
     * @param boundary 分界线
     * @param bufSize  缓冲大小
     * @param notifier 通知器
     *
     * @throws IllegalArgumentException 如果缓冲过小
     *
     */
    private MultipartStream(InputStream input, byte[] boundary, int bufSize, ProgressNotifier notifier) {
        if (boundary == null) {
            throw new IllegalArgumentException("boundary may not be null");
        }

        this.input = input;
        this.bufSize = bufSize;
        this.buffer = new byte[bufSize];
        this.notifier = notifier;

        // 将boundary前面的item最后的 CR/LF 当做boundary的开头,并拼接上去
        this.boundaryLength = boundary.length + BOUNDARY_PREFIX.length;
        if (bufSize < this.boundaryLength + 1) {
            throw new IllegalArgumentException(
                    "The buffer size specified for the MultipartStream is too small");
        }
        this.boundary = new byte[this.boundaryLength];
        this.keepRegion = this.boundary.length;

        //为boundary加上前缀
        System.arraycopy(BOUNDARY_PREFIX, 0, this.boundary, 0,
                BOUNDARY_PREFIX.length);
        System.arraycopy(boundary, 0, this.boundary, BOUNDARY_PREFIX.length,
                boundary.length);

        head = 0;
        tail = 0;
    }


    /**
     * 找到第一个 <code>item</code> 的开头.
     *
     * <p>实际效果为将boundary开头的回车换行符去掉，再检索boundary所在的位置，
     * 若{@link ItemIterator#skipPreamble}设置为true，该方法将在{@link ItemIterator#findNextItem()}被循环调用，
     *这意味着,在检索boundary时，始终都忽略开头的回车换行符(CR/LF).
     *
     * @return 如果找到了返回true
     *
     * @throws IOException
     */
    boolean skipPreamble() throws IOException {
        //第一个item的boundary前面没有回车换行符
        System.arraycopy(boundary, 2, boundary, 0, boundary.length - 2);
        boundaryLength = boundary.length - 2;
        try {
            // 舍弃所有数据直到boundary
            discardBodyData();

            //如果成功找到分界线,判断是否存在更多item
            return readBoundary();
        } catch (IOException e) {
            return false;
        } finally {
            // 将boundary还原,!!!重要,若不将boundary还原,body体将包含结尾的回车换行符
            System.arraycopy(boundary, 0, boundary, 2, boundary.length - 2);
            boundaryLength = boundary.length;
            boundary[0] = CR;
            boundary[1] = LF;
        }
    }


    /**
     * 越过boundary,检测后面是否还有item
     *
     * @return <code>true</code> 如果存在更多item;
     *
     */
    boolean readBoundary(){
        byte[] marker = new byte[2];
        boolean nextChunk = false;
        //跳过分界线
        head += boundaryLength;
        try {
            //读分界线的后一个字节
            marker[0] = readByte();
            if (marker[0] == LF) {
                //在IE5和Mac中,当input type=image时存在BUG,因为boundary尾部不包含CRLF.
                //这个丢失的CR应该归因于奇葩的浏览器,而并非文件中存在与boundary类似的东西
                return true;
            }

            marker[1] = readByte();
            //读两个字节,判断这两个字节是换行还是结尾,如果是换行表示有下一个item,反之表示没有
            if (arrayequals(marker, STREAM_TERMINATOR, 2)) {
                nextChunk = false;
            } else if (arrayequals(marker, FIELD_SEPARATOR, 2)) {
                nextChunk = true;
            } else {
                throw new RuntimeException(
                        "Unexpected characters follow a boundary");
            }
        } catch (IOException e) {
            throw new RuntimeException("Stream ended unexpectedly");
        }
        return nextChunk;
    }



    /**
     * <p> 改变分界线
     *
     * <p> 必须与MultipartStream中的分界线拥有相同的长度
     *
     * @param boundary 分界线
     *
     */
    void setBoundary(byte[] boundary){
        if (boundary.length != boundaryLength - BOUNDARY_PREFIX.length) {
            throw new IllegalArgumentException("The length of a boundary token can not be changed");
        }
        System.arraycopy(boundary, 0, this.boundary, BOUNDARY_PREFIX.length,
                boundary.length);
    }


    /**
     * <p> 从当前 <code>item</code> 中读取 <code>header-part</code>
     *
     * <p>Headers 是逐字节读取的，一直读到(包含)结尾处的{@link #HEADER_SEPARATOR}
     *
     * <p>只有当{@link #readBoundary()} 返回ture时才能调用此方法，否则将出现不可预知的情况
     *
     * @return 当前包裹的 <code>header-part</code>
     *
     */
    String readHeaders() throws IOException{
        //与 HEADER_SEPARATOR 匹配的字节数
        int i = 0;
        byte b;
        // to support multi-byte characters
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int size = 0;
        while (i < HEADER_SEPARATOR.length) {
            //从buffer中读取1byte
            b = readByte();
            if (++size > HEADER_PART_SIZE_MAX) {
                throw new RuntimeException(
                        format("Header section has more than %s bytes (maybe it is not properly terminated)",
                                Integer.valueOf(HEADER_PART_SIZE_MAX)));
            }
            //找到匹配的字节,将匹配数i加一,直至完全匹配,在此过程中一旦出现任何一个不匹配的字节,匹配数归零
            if (b == HEADER_SEPARATOR[i]) {
                i++;
            } else {
                i = 0;
            }
            //在匹配 HEADER_SEPARATOR 之前一直将读取的字节写入baos
            baos.write(b);
        }

        //转换成String返回
        String headers = null;
        if (headerEncoding != null) {
            try {
                headers = baos.toString(headerEncoding);
            } catch (UnsupportedEncodingException e) {
                //若平台不知道指定的编码,将使用默认值
                headers = baos.toString();
            }
        } else {
            headers = baos.toString();
        }

        return headers;
    }


    /**
     * <p>从当前<code>item</code>中读取 <code>body-data</code> 并丢弃他
     *
     * <p>使用该方法跳过不需要的item
     *
     * @return 跳过的数据量
     *
     * @throws IOException
     *
     * @see MultipartStream#readBodyData(OutputStream)
     */
    int discardBodyData() throws  IOException {
        return readBodyData(null);
    }


    /**
     * @return  {@link ItemInputStream}.
     */
    ItemInputStream newInputStream() {
        return new ItemInputStream();
    }


    /**
     * 关闭
     * @throws IOException
     */
    void close() throws IOException{
        input.close();
    }


    /**
     * 从缓冲中读取一个byte,如果缓冲耗尽,将会再次填充
     *
     * @return 下一个byte
     *
     * @throws java.io.IOException 如果没有更多数据
     */
    private byte readByte() throws IOException {
        //缓冲耗尽
        if (head == tail) {
            head = 0;
            // 再填充
            tail = input.read(buffer, head, bufSize);
            if (tail == -1) {
                //没有更多数据数据了
                throw new IOException("No more data is available");
            }
            if (notifier != null) {
                notifier.noteBytesRead(tail);
            }
        }
        return buffer[head++];
    }


    /**
     * 对比在a和b两个byte数组中的前count个byte,如果全部相等,返回true
     *
     * @param a     第一个数组
     * @param b     第二个数组
     * @param count 比较个个数
     *
     * @return <code>true</code> 如果前 <code>count</code> 个byte在
     *         <code>a</code> 和 <code>b</code> 中相等.
     */
    private boolean arrayequals(byte[] a, byte[] b, int count) {
        for (int i = 0; i < count; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }


    /**
     * <p>从当前<code>item</code>中读取 <code>body-data</code> 并写入到输出流
     *
     * <p>该方法具有更深层的含义,在调用{@link Streams#copy(java.io.InputStream, java.io.OutputStream, boolean)}
     * 时,{@link ItemInputStream#read(byte[], int, int)}将被不断执行,一直执行到发现下一个分界线为止.
     *
     * @param output  <code>Stream</code> 写入的目标,如果为null,该方法将等效于 {@link #discardBodyData()}.
     *
     * @return 写入的数据量
     *
     * @throws IOException
     */
    private int readBodyData(OutputStream output) throws  IOException {
        return (int) Streams.copy(newInputStream(), output, false); // N.B. Streams.copy closes the input stream
    }



    /**
     * 从当前 <code>buffer</code> 中寻找 <code>boundary</code>,
     * 限定范围为<code>head</code> 和 <code>tail-boundaryLength</code> 之间.
     *
     * <p>当first == maxpos时,将会对最后的boundaryLength部分进行匹配,若匹配失败,则表示这部分不存在boundary或者boundary被截断了.
     * 因为截断位置不确定,所以最后boundaryLength长度的字节将被保留{@link ItemInputStream#findSeparator()},而在后续操作中,这段字节将被移动到buffer开头{@link ItemInputStream#makeAvailable()}.
     *
     * @return 如果在 <code>buffer</code> 中找到了分界线,返回分界线在buffer中的起始位置, 如果未找到返回 <code>-1</code>
     */
    private int findSeparator() {
        int first;
        int match = 0;
        //若buffer中head至tail之间的字节数小于boundaryLength,那么maxpos将小于head,循环将不会运行,返回值为-1
        int maxpos = tail - boundaryLength;
        for (first = head; first <= maxpos && match != boundaryLength; first++) {
            first = findByte(boundary[0], first);
            if (first == -1 || first > maxpos) {
                return -1;
            }
            for (match = 1; match < boundaryLength; match++) {
                if (buffer[first + match] != boundary[match]) {
                    break;
                }
            }
        }
        if (match == boundaryLength) {
            return first - 1;
        }
        return -1;
    }


    /**
     *在buffer中从指定的位置pos开始寻找一格指定的value
     *
     * @param value 寻找的值
     * @param pos   在buffer中开始寻找的位置
     *
     * @return 在 <code>buffer</code> 中找到的 valued 的位置, 如果未找到返回 <code>-1</code>.
     */
    private int findByte(byte value, int pos) {
        for (int i = pos; i < tail; i++) {
            if (buffer[i] == value) {
                return i;
            }
        }

        return -1;
    }


    /**
     * {@link InputStream}子类,用于读取{@link MultipartStream#input }中的内容
     */
     private class ItemInputStream extends InputStream {

        /**
         * 已读取总字节数
         */
        private long total;

        /**
         * 必须保留的字节数,因为这些字节可能包含boundary的一部分
         */
        private int pad;

        /**
         * buffer中boundary的起始坐标,-1表示未发现boundary
         */
        private int pos;

        /**
         * 当前Stream是否已关闭
         */
        private boolean closed;

        /**
         * 创建一个实例
         */
        ItemInputStream() {
            findSeparator();
        }

        /**
         * <p>在当前buffer中寻找boundary,并设定pos 与 pad
         *
         * <p>该方法只有在buffer中读入了不确定数据时才会调用,
         * {@link MultipartStream#readByte()}中也存在对buffer的写入操作,但其读取结果是可预判的,
         * 因此不需要寻找boundary
         */
        private void findSeparator() {
            pos = MultipartStream.this.findSeparator();
            if (pos == -1) {
                if (tail - head > keepRegion) {
                    pad = keepRegion;
                } else {
                    //若buffer中的数据量小于keepRegion(boundaryLength),pos值必为-1,这些数据将被全部保留
                    pad = tail - head;
                }
            }
        }


        /**
         * 读取一个字节
         *
         * @return Stream中下一个字节的int形式, 如果读到了boundary,返回-1
         * @throws IOException
         */
        @Override
        public int read() throws IOException {
            if (closed) {
                throw new RuntimeException("the stream is closed");
            }
            if (available() == 0 && makeAvailable() == 0) {
                return -1;
            }
            ++total;
            int b = buffer[head++];
            if (b >= 0) {
                return b;
            }
            return b + 256;
        }

        /**
         * 向给定的buffer中读入字节,当读至下一个boundary时,返回-1
         *
         * @param b 写入的目的地
         * @param off buffer中第一个字符的偏移量
         * @param len 读入字节数的最大数量
         * @return 实际读入的字节数, 当到达末尾时,返回-1
         * @throws IOException An I/O error occurred.
         */
        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (closed) {
                throw new RuntimeException("the stream is closed");
            }
            if (len == 0) {
                return 0;
            }
            int res = available();
            if (res == 0) {
                res = makeAvailable();
                if (res == 0) {
                    return -1;
                }
            }
            res = Math.min(res, len);
            System.arraycopy(buffer, head, b, off, res);
            head += res;
            total += res;
            return res;
        }

        /**
         * 关闭流,将跳过当前Stremd的所有字节,直至下一个boundary
         *
         * @throws IOException An I/O error occurred.
         */
        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            for (;;) {
                int av = available();
                if (av == 0) {
                    av = makeAvailable();
                    if (av == 0) {
                        break;
                    }
                }
                skip(av);
            }
            closed = true;
        }


        /**
         * 跳过给定数量的字节
         *
         * @param bytes 给定的数量
         * @return 实际跳过的字节数
         * @throws IOException An I/O error occurred.
         */
        @Override
        public long skip(long bytes) throws IOException {
            if (closed) {
                throw new RuntimeException("the stream is closed");
            }
            int av = available();
            if (av == 0) {
                av = makeAvailable();
                if (av == 0) {
                    return 0;
                }
            }
            long res = Math.min(av, bytes);
            head += res;
            return res;
        }

        /**
         * 返回当前可用的byte数
         *
         * @throws IOException An I/O error occurs.
         * @return 当前可用byte数
         */
        @Override
        public int available() throws IOException {
            //若在当前buffer中未找到boundary,则可用字节为pad之前的所有字节
            if (pos == -1) {
                return tail - head - pad;
            }
            //若找到了boundary,可用字节为boundary所在位置之前的所有字节
            return pos - head;
        }


        /**
         * <p>增大可用字节数
         *
         * <p>将<code>buffer</code>中最后的{@link ItemInputStream#pad}部分移动到开头,然后再将<code>buffer</code>填充,这样无论上个<code>buffer</code>中
         *  的<code>boundary</code>是否被截断,如此处理后将变成连续的.
         * @return 可用字节的数量
         * @throws IOException An I/O error occurred.
         */
        private int makeAvailable() throws IOException {
            //该方法在available返回0时才会被调用,若pos!=-1那pos==head,表示boundary处于head位,可用字节数为0
            if (pos != -1) {
                return 0;
            }

            // 将pad位之后的数据移动到buffer开头
            total += tail - head - pad;
            System.arraycopy(buffer, tail - pad, buffer, 0, pad);

            // 将buffer填满
            head = 0;
            tail = pad;
            //循环读取数据,直至将buffer填满,在此过程中,每次读取都将检索buffer中是否存在boundary,无论存在与否,都将即时返回可用数据量
            for (;;) {
                int bytesRead = input.read(buffer, tail, bufSize - tail);
                if (bytesRead == -1) {
                    //理论上因为会对buffer不断进行检索,读到boundary时就会return 0,read方法将返回 -1,
                    //所以不会读到input末尾,如果运行到了这里,表示发生了错误.
                    final String msg = "Stream ended unexpectedly";
                    throw new RuntimeException(msg);
                }
                if (notifier != null) {
                    notifier.noteBytesRead(bytesRead);
                }
                tail += bytesRead;
                findSeparator();
                //若buffer中的数据量小于keepRegion(boundaryLength),av将必定等于0,循环将继续,直至数据量大于或等于keepRegion(boundaryLength).
                //此时将检索buffer中是否包含boundary,若包含,将返回boundary所在位置pos之前的数据量,若不包含,将返回pad位之前的数据量
                int av = available();

                if (av > 0 || pos != -1) {
                    return av;
                }
            }
        }

        /**
         *
         * @return 流是否已关闭
         */
        public boolean isClosed() {
            return closed;
        }

        /**
         *
         * @return 返回已读取的总字节数
         */
        public long getBytesRead() {
            return total;
        }

    }

    /**
     * 运行过程的记录器，记录已读取字节数，总条目数
     */
    static class ProgressNotifier {

        /**
         * 已读取字节数
         */
        private long bytesRead;

        /**
         * 已读取条目数
         */
        private int items;

        /**
         * 改变已读取总字节数
         *
         * @param pBytes 读取字节数
         */
        void noteBytesRead(int pBytes) {
            bytesRead += pBytes;
        }

        /**
         * 改变已发现的总条目数
         */
        void noteItem() {
            ++items;
        }

    }
}
