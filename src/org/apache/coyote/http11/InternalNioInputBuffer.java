/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.coyote.http11;

import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.Selector;
import java.nio.charset.Charset;

import org.apache.coyote.InputBuffer;
import org.apache.coyote.Request;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.parser.HttpParser;
import org.apache.tomcat.util.net.AbstractEndpoint;
import org.apache.tomcat.util.net.NioChannel;
import org.apache.tomcat.util.net.NioEndpoint;
import org.apache.tomcat.util.net.NioSelectorPool;
import org.apache.tomcat.util.net.SocketWrapper;

/**
 * Implementation of InputBuffer which provides HTTP request header parsing as
 * well as transfer decoding.
 *
 * @author <a href="mailto:remm@apache.org">Remy Maucherat</a>
 * @author Filip Hanik
 */
public class InternalNioInputBuffer extends AbstractInputBuffer<NioChannel> {

    private static final org.apache.juli.logging.Log log =
        org.apache.juli.logging.LogFactory.getLog(InternalNioInputBuffer.class);
    /**默认字符编码*/
    private static final Charset DEFAULT_CHARSET =
        Charset.forName("ISO-8859-1");

    // -------------------------------------------------------------- Constants

    enum HeaderParseStatus {
        DONE, HAVE_MORE_HEADERS, NEED_MORE_DATA
    }

    enum HeaderParsePosition {
        /**
         * Start of a new header. A CRLF here means that there are no more
         * headers. Any other character starts a header name.
         */
        HEADER_START,
        /**
         * Reading a header name. All characters of header are HTTP_TOKEN_CHAR.
         * Header name is followed by ':'. No whitespace is allowed.<br />
         * Any non-HTTP_TOKEN_CHAR (this includes any whitespace) encountered
         * before ':' will result in the whole line being ignored.
         */
        HEADER_NAME,
        /**
         * Skipping whitespace before text of header value starts, either on the
         * first line of header value (just after ':') or on subsequent lines
         * when it is known that subsequent line starts with SP or HT.
         */
        HEADER_VALUE_START,
        /**
         * Reading the header value. We are inside the value. Either on the
         * first line or on any subsequent line. We come into this state from
         * HEADER_VALUE_START after the first non-SP/non-HT byte is encountered
         * on the line.
         */
        HEADER_VALUE,
        /**
         * Before reading a new line of a header. Once the next byte is peeked,
         * the state changes without advancing our position. The state becomes
         * either HEADER_VALUE_START (if that first byte is SP or HT), or
         * HEADER_START (otherwise).
         */
        HEADER_MULTI_LINE,
        /**
         * Reading all bytes until the next CRLF. The line is being ignored.
         */
        HEADER_SKIPLINE
    }

    // ----------------------------------------------------------- Constructors


    /** 关联底层nioChannel,一些配置
     * Alternate constructor.
     */
    public InternalNioInputBuffer(Request request, int headerBufferSize,
            boolean rejectIllegalHeaderName) {

        this.request = request;
        headers = request.getMimeHeaders();

        this.headerBufferSize = headerBufferSize;
        this.rejectIllegalHeaderName = rejectIllegalHeaderName;
        
        inputStreamInputBuffer = new SocketInputBuffer();

        filterLibrary = new InputFilter[0];
        activeFilters = new InputFilter[0];
        lastActiveFilter = -1;

        parsingHeader = true;
        parsingRequestLine = true;
        parsingRequestLinePhase = 0;
        parsingRequestLineEol = false;
        parsingRequestLineStart = 0;
        parsingRequestLineQPos = -1;
        headerParsePos = HeaderParsePosition.HEADER_START;
        headerData.recycle();
        swallowInput = true;

    }

    /**
     * Parsing state - used for non blocking parsing so that
     * when more data arrives, we can pick up where we left off.
     */
    private boolean parsingRequestLine;
    private int parsingRequestLinePhase = 0;
    private boolean parsingRequestLineEol = false;
    private int parsingRequestLineStart = 0;
    private int parsingRequestLineQPos = -1;
    private HeaderParsePosition headerParsePos;

    /** 关联底层的nioChannel
     * Underlying socket.
     */
    private NioChannel socket;

    /** 提供selector的pool.它用于读取http请求的body数据.因为必须要阻塞的读取,所以使用selector协作
     * Selector pool, for blocking reads and blocking writes
     */
    private NioSelectorPool pool;


    /** 允许的最大header大小
     * Maximum allowed size of the HTTP request line plus headers plus any
     * leading blank lines.
     */
    private final int headerBufferSize;

    /** channel设置的读取的buffer的size
     * Known size of the NioChannel read buffer.
     */
    private int socketReadBufferSize;


    // --------------------------------------------------------- Public Methods

    /**
     * Recycle the input buffer. This should be called when closing the
     * connection.
     */
    @Override
    public void recycle() {
        super.recycle();
        socket = null;
        headerParsePos = HeaderParsePosition.HEADER_START;
        parsingRequestLine = true;
        parsingRequestLinePhase = 0;
        parsingRequestLineEol = false;
        parsingRequestLineStart = 0;
        parsingRequestLineQPos = -1;
        headerData.recycle();
    }


    /**
     * End processing of current HTTP request.
     * Note: All bytes of the current request should have been already
     * consumed. This method only resets all the pointers so that we are ready
     * to parse the next HTTP request.
     */
    @Override
    public void nextRequest() {
        super.nextRequest();
        headerParsePos = HeaderParsePosition.HEADER_START;
        parsingRequestLine = true;
        parsingRequestLinePhase = 0;
        parsingRequestLineEol = false;
        parsingRequestLineStart = 0;
        parsingRequestLineQPos = -1;
        headerData.recycle();
    }

    /**
     * Read the request line. This function is meant to be used during the
     * HTTP request header parsing. Do NOT attempt to read the request body
     * using it.
     *
     * @throws IOException If an exception occurs during the underlying socket
     * read operations, or if the given buffer is not big enough to accommodate
     * the whole line.
     * @return true if data is properly fed; false if no data is available
     * immediately and thread should be freed
     */
    @Override
    public boolean parseRequestLine(boolean useAvailableDataOnly)
        throws IOException {

        //check state
        if ( !parsingRequestLine ) return true;
        //
        // Skipping blank lines
        //
        if ( parsingRequestLinePhase == 0 ) {
            byte chr = 0;
            do {

                // Read new bytes if needed. 若需要,则读取新的字节
                if (pos >= lastValid) {
                    if (useAvailableDataOnly) {
                        return false;
                    }
                    // Do a simple read with a short timeout.做一个短超时的简单读取操作.fill(是否有超时, 是否阻塞)
                    if (!fill(true, false)) {
                        return false;
                    }
                }
                // Set the start time once we start reading data (even if it is
                // just skipping blank lines) 设置 读取请求 的开始时间
                if (request.getStartTime() < 0) {
                    request.setStartTime(System.currentTimeMillis());
                }
                chr = buf[pos++];// 读取当前字节(pos位置的字节)到buf缓冲区中,并将缓冲区中的位置++. chr是byte类型
            } while ((chr == Constants.CR) || (chr == Constants.LF));// 若遇到 字符是CR 或LF继续读取,即\r和\n代表请求行解析完毕
            pos--;

            parsingRequestLineStart = pos;
            parsingRequestLinePhase = 2;
            if (log.isDebugEnabled()) {
                log.debug("Received ["
                        + new String(buf, pos, lastValid - pos, DEFAULT_CHARSET)
                        + "]");
            }
        }
        if ( parsingRequestLinePhase == 2 ) {
            //
            // Reading the method name 下面读取请求行中的  方法名
            // Method name is a token
            //
            boolean space = false;
            while (!space) {
                // Read new bytes if needed
                if (pos >= lastValid) {
                    if (!fill(true, false)) //request line parsing
                        return false;
                }
                // Spec says method name is a token followed by a single SP but
                // also be tolerant of multiple SP and/or HT.
                if (buf[pos] == Constants.SP || buf[pos] == Constants.HT) {// 空格 或 \t
                    space = true;
                    request.method().setBytes(buf, parsingRequestLineStart, pos - parsingRequestLineStart);// 设置请求方法,如GET
                } else if (!HttpParser.isToken(buf[pos])) {
                    throw new IllegalArgumentException(sm.getString("iib.invalidmethod"));
                }
                pos++;
            }
            parsingRequestLinePhase = 3;
        }
        if ( parsingRequestLinePhase == 3 ) {// 跳过多个空格
            // Spec says single SP but also be tolerant of multiple SP and/or HT
            boolean space = true;
            while (space) {
                // Read new bytes if needed
                if (pos >= lastValid) {
                    if (!fill(true, false)) //request line parsing
                        return false;
                }
                if (buf[pos] == Constants.SP || buf[pos] == Constants.HT) {
                    pos++;
                } else {
                    space = false;
                }
            }
            parsingRequestLineStart = pos;
            parsingRequestLinePhase = 4;
        }
        if (parsingRequestLinePhase == 4) {
            // Mark the current buffer position

            int end = 0;
            //
            // Reading the URI 下面读取请求行中的URI
            //
            boolean space = false;
            while (!space) {
                // Read new bytes if needed
                if (pos >= lastValid) {
                    if (!fill(true,false)) //request line parsing
                        return false;
                }
                if (buf[pos] == Constants.SP || buf[pos] == Constants.HT) {
                    space = true;
                    end = pos;
                } else if ((buf[pos] == Constants.CR)
                           || (buf[pos] == Constants.LF)) {
                    // HTTP/0.9 style request
                    parsingRequestLineEol = true;
                    space = true;
                    end = pos;
                } else if ((buf[pos] == Constants.QUESTION) && (parsingRequestLineQPos == -1)) {
                    parsingRequestLineQPos = pos;
                } else if (HttpParser.isNotRequestTarget(buf[pos])) {
                    throw new IllegalArgumentException(sm.getString("iib.invalidRequestTarget"));
                }
                pos++;
            }
            request.unparsedURI().setBytes(buf, parsingRequestLineStart, end - parsingRequestLineStart);// 设置URI
            if (parsingRequestLineQPos >= 0) {// 设置 查询参数
                request.queryString().setBytes(buf, parsingRequestLineQPos + 1,
                                               end - parsingRequestLineQPos - 1);
                request.requestURI().setBytes(buf, parsingRequestLineStart, parsingRequestLineQPos - parsingRequestLineStart);
            } else {
                request.requestURI().setBytes(buf, parsingRequestLineStart, end - parsingRequestLineStart);
            }
            parsingRequestLinePhase = 5;
        }
        if ( parsingRequestLinePhase == 5 ) {
            // Spec says single SP but also be tolerant of multiple and/or HT
            boolean space = true;
            while (space) {// 跳过多个空格
                // Read new bytes if needed
                if (pos >= lastValid) {
                    if (!fill(true, false)) //request line parsing
                        return false;
                }
                if (buf[pos] == Constants.SP || buf[pos] == Constants.HT) {
                    pos++;
                } else {
                    space = false;
                }
            }
            parsingRequestLineStart = pos;
            parsingRequestLinePhase = 6;

            // Mark the current buffer position
            end = 0;
        }
        if (parsingRequestLinePhase == 6) {
            //
            // Reading the protocol 下面解析请求行中的 请求协议,如HTTP/1.1
            // Protocol is always "HTTP/" DIGIT "." DIGIT
            //
            while (!parsingRequestLineEol) {
                // Read new bytes if needed
                if (pos >= lastValid) {
                    if (!fill(true, false)) //request line parsing
                        return false;
                }

                if (buf[pos] == Constants.CR) {
                    end = pos;
                } else if (buf[pos] == Constants.LF) {
                    if (end == 0)
                        end = pos;
                    parsingRequestLineEol = true;
                } else if (!HttpParser.isHttpProtocol(buf[pos])) {
                    throw new IllegalArgumentException(sm.getString("iib.invalidHttpProtocol"));
                }
                pos++;
            }

            if ( (end - parsingRequestLineStart) > 0) {
                request.protocol().setBytes(buf, parsingRequestLineStart, end - parsingRequestLineStart);// 设置请求协议
            } else {
                request.protocol().setString("");
            }
            parsingRequestLine = false;
            parsingRequestLinePhase = 0;
            parsingRequestLineEol = false;
            parsingRequestLineStart = 0;
            return true;
        }
        throw new IllegalStateException("Invalid request line parse phase:"+parsingRequestLinePhase);
    }

    private void expand(int newsize) {
        if ( newsize > buf.length ) {
            if (parsingHeader) {
                throw new IllegalArgumentException(
                        sm.getString("iib.requestheadertoolarge.error"));
            }
            // Should not happen
            log.warn("Expanding buffer size. Old size: " + buf.length
                    + ", new size: " + newsize, new Exception());
            byte[] tmp = new byte[newsize];
            System.arraycopy(buf,0,tmp,0,buf.length);
            buf = tmp;
        }
    }

    /** 从底层socket读取数据.有阻塞式 和 非阻塞式 从 socket中读.若采用nio,在处理http请求的requestLine和header时非阻塞. 处理body时必须阻塞(因为读取body部分的 代码一般是在servlet部分控制的即上层代码控制,已经超出tomcat控制范围).从底层channel读取数据,将数据从channel拷贝到当前对象的buf中
     * Perform blocking read with a timeout if desired
     * @param timeout boolean - if we want to use the timeout data
     * @param block - true if the system should perform a blocking read, false otherwise
     * @return boolean - true if data was read, false is no data read, EOFException if EOF is reached
     * @throws IOException if a socket exception occurs
     * @throws EOFException if end of stream is reached
     */

    private int readSocket(boolean timeout, boolean block) throws IOException {
        int nRead = 0;
        socket.getBufHandler().getReadBuffer().clear();// 清空nio.ByteBuffer,方便下面将socket里读取的新数据写进去
        if ( block ) {// 若是阻塞的话(读取body时阻塞),将会获取selector关联的socket,节约cpu
            Selector selector = null;
            try {
                selector = pool.get();// 从selector池中获取一个selector
            } catch ( IOException x ) {
                // Ignore
            }
            try {
                NioEndpoint.KeyAttachment att =
                        (NioEndpoint.KeyAttachment) socket.getAttachment();
                if (att == null) {
                    throw new IOException("Key must be cancelled.");
                }
                nRead = pool.read(socket.getBufHandler().getReadBuffer(),
                        socket, selector, att.getTimeout());
            } catch ( EOFException eof ) {
                nRead = -1;
            } finally {
                if ( selector != null ) pool.put(selector);
            }
        } else {// 非阻塞读数据,有多少则读取多少数据
            nRead = socket.read(socket.getBufHandler().getReadBuffer());// socketChannel.read(byteBuffer) 从 channel读到 buffer中
        }
        if (nRead > 0) {// 若有数据读取出来
            socket.getBufHandler().getReadBuffer().flip();// 调用ByteBuffer.flip()将buffer切换到读模式
            socket.getBufHandler().getReadBuffer().limit(nRead);// 设置buffer.limit
            expand(nRead + pos);// 检查当前对象的buf字节数组容量是否够大,若不够则扩容
            socket.getBufHandler().getReadBuffer().get(buf, pos, nRead);// 将socket读取的数据 转移到当前对象的buf数组中. public ByteBuffer get(byte[] dst,int offset,int length)此方法将此缓冲区的字节传输到给定的目标数组dest中
            lastValid = pos + nRead;// 更新最后一个有效数据的下标
            return nRead;
        } else if (nRead == -1) {// 若-1,表示出错了
            //return false;
            throw new EOFException(sm.getString("iib.eof.error"));
        } else {
            return 0;
        }
    }

    /**
     * Parse the HTTP headers.
     */
    @Override
    public boolean parseHeaders()
        throws IOException {
        if (!parsingHeader) {
            throw new IllegalStateException(
                    sm.getString("iib.parseheaders.ise.error"));
        }

        HeaderParseStatus status = HeaderParseStatus.HAVE_MORE_HEADERS;// 请求头解析状态 设置为 "还有更多请求头" 要解析

        do {
            status = parseHeader();
            // Checking that
            // (1) Headers plus request line size does not exceed its limit
            // (2) There are enough bytes to avoid expanding the buffer when
            // reading body
            // Technically, (2) is technical limitation, (1) is logical
            // limitation to enforce the meaning of headerBufferSize
            // From the way how buf is allocated and how blank lines are being
            // read, it should be enough to check (1) only.
            if (pos > headerBufferSize
                    || buf.length - pos < socketReadBufferSize) {
                throw new IllegalArgumentException(
                        sm.getString("iib.requestheadertoolarge.error"));
            }
        } while ( status == HeaderParseStatus.HAVE_MORE_HEADERS );
        if (status == HeaderParseStatus.DONE) {
            parsingHeader = false;
            end = pos;
            return true;
        } else {
            return false;
        }
    }


    /**
     * Parse an HTTP header.
     *
     * @return false after reading a blank line (which indicates that the
     * HTTP header parsing is done
     */
    private HeaderParseStatus parseHeader()
        throws IOException {

        //
        // Check for blank line
        //

        byte chr = 0;
        while (headerParsePos == HeaderParsePosition.HEADER_START) {

            // Read new bytes if needed
            if (pos >= lastValid) {
                if (!fill(true,false)) {//parse header
                    headerParsePos = HeaderParsePosition.HEADER_START;
                    return HeaderParseStatus.NEED_MORE_DATA;
                }
            }

            chr = buf[pos];

            if (chr == Constants.CR) {
                // Skip
            } else if (chr == Constants.LF) {
                pos++;
                return HeaderParseStatus.DONE;
            } else {
                break;
            }

            pos++;

        }

        if ( headerParsePos == HeaderParsePosition.HEADER_START ) {
            // Mark the current buffer position
            headerData.start = pos;
            headerParsePos = HeaderParsePosition.HEADER_NAME;
        }

        //
        // Reading the header name
        // Header name is always US-ASCII
        //

        while (headerParsePos == HeaderParsePosition.HEADER_NAME) {

            // Read new bytes if needed
            if (pos >= lastValid) {
                if (!fill(true,false)) { //parse header
                    return HeaderParseStatus.NEED_MORE_DATA;
                }
            }

            chr = buf[pos];
            if (chr == Constants.COLON) {// 冒号
                headerParsePos = HeaderParsePosition.HEADER_VALUE_START;
                headerData.headerValue = headers.addValue(buf, headerData.start, pos - headerData.start);
                pos++;
                // Mark the current buffer position
                headerData.start = pos;
                headerData.realPos = pos;
                headerData.lastSignificantChar = pos;
                break;
            } else if (!HttpParser.isToken(chr)) {
                // Non-token characters are illegal in header names
                // Parsing continues so the error can be reported in context
                headerData.lastSignificantChar = pos;
                // skipLine() will handle the error
                return skipLine();
            }

            // chr is next byte of header name. Convert to lowercase.
            if ((chr >= Constants.A) && (chr <= Constants.Z)) {// 大写转小写
                buf[pos] = (byte) (chr - Constants.LC_OFFSET);
            }
            pos++;
        }

        // Skip the line and ignore the header
        if (headerParsePos == HeaderParsePosition.HEADER_SKIPLINE) {
            return skipLine();
        }

        //
        // Reading the header value (which can be spanned over multiple lines)
        //

        while (headerParsePos == HeaderParsePosition.HEADER_VALUE_START ||
               headerParsePos == HeaderParsePosition.HEADER_VALUE ||
               headerParsePos == HeaderParsePosition.HEADER_MULTI_LINE) {

            if ( headerParsePos == HeaderParsePosition.HEADER_VALUE_START ) {
                // Skipping spaces
                while (true) {
                    // Read new bytes if needed
                    if (pos >= lastValid) {
                        if (!fill(true,false)) {//parse header
                            //HEADER_VALUE_START
                            return HeaderParseStatus.NEED_MORE_DATA;
                        }
                    }

                    chr = buf[pos];
                    if (chr == Constants.SP || chr == Constants.HT) {
                        pos++;
                    } else {
                        headerParsePos = HeaderParsePosition.HEADER_VALUE;
                        break;
                    }
                }
            }
            if ( headerParsePos == HeaderParsePosition.HEADER_VALUE ) {

                // Reading bytes until the end of the line
                boolean eol = false;
                while (!eol) {

                    // Read new bytes if needed
                    if (pos >= lastValid) {
                        if (!fill(true,false)) {//parse header
                            //HEADER_VALUE
                            return HeaderParseStatus.NEED_MORE_DATA;
                        }
                    }

                    chr = buf[pos];
                    if (chr == Constants.CR) {
                        // Skip
                    } else if (chr == Constants.LF) {
                        eol = true;
                    } else if (chr == Constants.SP || chr == Constants.HT) {
                        buf[headerData.realPos] = chr;
                        headerData.realPos++;
                    } else {
                        buf[headerData.realPos] = chr;
                        headerData.realPos++;
                        headerData.lastSignificantChar = headerData.realPos;
                    }

                    pos++;
                }

                // Ignore whitespaces at the end of the line
                headerData.realPos = headerData.lastSignificantChar;

                // Checking the first character of the new line. If the character
                // is a LWS, then it's a multiline header
                headerParsePos = HeaderParsePosition.HEADER_MULTI_LINE;
            }
            // Read new bytes if needed
            if (pos >= lastValid) {
                if (!fill(true,false)) {//parse header

                    //HEADER_MULTI_LINE
                    return HeaderParseStatus.NEED_MORE_DATA;
                }
            }

            chr = buf[pos];
            if ( headerParsePos == HeaderParsePosition.HEADER_MULTI_LINE ) {
                if ( (chr != Constants.SP) && (chr != Constants.HT)) {
                    headerParsePos = HeaderParsePosition.HEADER_START;
                    break;
                } else {
                    // Copying one extra space in the buffer (since there must
                    // be at least one space inserted between the lines)
                    buf[headerData.realPos] = chr;
                    headerData.realPos++;
                    headerParsePos = HeaderParsePosition.HEADER_VALUE_START;
                }
            }
        }
        // Set the header value
        headerData.headerValue.setBytes(buf, headerData.start,
                headerData.lastSignificantChar - headerData.start);
        headerData.recycle();
        return HeaderParseStatus.HAVE_MORE_HEADERS;
    }

    public int getParsingRequestLinePhase() {
        return parsingRequestLinePhase;
    }

    private HeaderParseStatus skipLine() throws IOException {
        headerParsePos = HeaderParsePosition.HEADER_SKIPLINE;
        boolean eol = false;

        // Reading bytes until the end of the line
        while (!eol) {

            // Read new bytes if needed
            if (pos >= lastValid) {
                if (!fill(true,false)) {
                    return HeaderParseStatus.NEED_MORE_DATA;
                }
            }

            if (buf[pos] == Constants.CR) {
                // Skip
            } else if (buf[pos] == Constants.LF) {
                eol = true;
            } else {
                headerData.lastSignificantChar = pos;
            }

            pos++;
        }
        if (rejectIllegalHeaderName || log.isDebugEnabled()) {
            String message = sm.getString("iib.invalidheader", new String(buf, headerData.start,
                    headerData.lastSignificantChar - headerData.start + 1, DEFAULT_CHARSET));
            if (rejectIllegalHeaderName) {
                throw new IllegalArgumentException(message);
            }
            log.debug(message);
        }

        headerParsePos = HeaderParsePosition.HEADER_START;
        return HeaderParseStatus.HAVE_MORE_HEADERS;
    }

    private HeaderParseData headerData = new HeaderParseData();
    public static class HeaderParseData {
        /**
         * When parsing header name: first character of the header.<br />
         * When skipping broken header line: first character of the header.<br />
         * When parsing header value: first character after ':'.
         */
        int start = 0;
        /**
         * When parsing header name: not used (stays as 0).<br />
         * When skipping broken header line: not used (stays as 0).<br />
         * When parsing header value: starts as the first character after ':'.
         * Then is increased as far as more bytes of the header are harvested.
         * Bytes from buf[pos] are copied to buf[realPos]. Thus the string from
         * [start] to [realPos-1] is the prepared value of the header, with
         * whitespaces removed as needed.<br />
         */
        int realPos = 0;
        /**
         * When parsing header name: not used (stays as 0).<br />
         * When skipping broken header line: last non-CR/non-LF character.<br />
         * When parsing header value: position after the last not-LWS character.<br />
         */
        int lastSignificantChar = 0;
        /**
         * MB that will store the value of the header. It is null while parsing
         * header name and is created after the name has been parsed.
         */
        MessageBytes headerValue = null;
        public void recycle() {
            start = 0;
            realPos = 0;
            lastSignificantChar = 0;
            headerValue = null;
        }
    }


    // ------------------------------------------------------ Protected Methods

    @Override
    protected void init(SocketWrapper<NioChannel> socketWrapper,
            AbstractEndpoint<NioChannel> endpoint) throws IOException {
        // socketWrapper为KeyAttachment对象
        socket = socketWrapper.getSocket();// 获取NioChannel
        socketReadBufferSize =
            socket.getBufHandler().getReadBuffer().capacity();// 获取nio.buffer的容量

        int bufLength = headerBufferSize + socketReadBufferSize;// 允许的最大header大小 + nio.buffer的容量
        if (buf == null || buf.length < bufLength) {
            buf = new byte[bufLength];// 初始化buf数组的大小
        }

        pool = ((NioEndpoint)endpoint).getSelectorPool();// 设置selector池
    }


    /**
     * Fill the internal buffer using data from the underlying input stream.
     *
     * @return false if at end of stream
     */
    @Override
    protected boolean fill(boolean block) throws IOException, EOFException {
        return fill(true,block);
    }
    /**读取socket数据到buf字节数组.timeout:是否有超时; block:是否阻塞读*/
    protected boolean fill(boolean timeout, boolean block) throws IOException, EOFException {


        boolean read = false;

        if (parsingHeader) {// 正在解析header

            if (lastValid > headerBufferSize) {
                throw new IllegalArgumentException
                    (sm.getString("iib.requestheadertoolarge.error"));
            }

            // Do a simple read with a short timeout.短时间简单读取socket数据
            read = readSocket(timeout,block)>0;// 具体调用readSocket()处理
        } else {// body的数据
            lastValid = pos = end;
            // Do a simple read with a short timeout
            read = readSocket(timeout, block)>0;
        }
        return read;
    }


    // ------------------------------------- InputStreamInputBuffer Inner Class


    /** 代理当前对象的读取数据的方法,将方法暴露给外部对象
     * This class is an input buffer which will read its data from an input
     * stream.
     */
    protected class SocketInputBuffer
        implements InputBuffer {


        /** 若需要则从socket读数据到buf数组. 将当前对象的字节数组buf 设置到chunk中
         * Read bytes into the specified chunk.
         */
        @Override
        public int doRead(ByteChunk chunk, Request req )
            throws IOException {

            if (pos >= lastValid) {// 若字节数组没有数据可以读取了,调用fill从socket里读取数据,并保存到当前对象的字节数组
                if (!fill(true,true)) //此处阻塞读.必须阻塞读，因为线程在应用程序内部.read body, must be blocking, as the thread is inside the app
                    return -1;
            }

            int length = lastValid - pos;// bufr中还有多少数据可以读取
            chunk.setBytes(buf, pos, length);// 将当前对象的字节数组buf 设置到chunk中 
            pos = lastValid;// 更新pos下标

            return (length);
        }
    }
}
