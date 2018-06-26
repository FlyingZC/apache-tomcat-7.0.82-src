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

import java.io.IOException;

import org.apache.coyote.InputBuffer;
import org.apache.coyote.Request;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.net.AbstractEndpoint;
import org.apache.tomcat.util.net.SocketWrapper;
import org.apache.tomcat.util.res.StringManager;

public abstract class AbstractInputBuffer<S> implements InputBuffer{

    /**
     * The string manager for this package.
     */
    protected static final StringManager sm = StringManager.getManager(Constants.Package);

    /** 封装的请求对象
     * Associated Coyote request.
     */
    protected Request request;


    /** 请求头
     * Headers of the associated request.
     */
    protected MimeHeaders headers;


    /** 是否正在处理header的 状态标志
     * State.
     */
    protected boolean parsingHeader;


    /** 是否 还要接收 当前关联的连接的数据
     * Swallow input ? (in the case of an expectation)
     */
    protected boolean swallowInput;


    /** 用于保存数据的字节数组.指向当前读取缓冲区的指针。字节数组buf
     * Pointer to the current read buffer.
     */
    protected byte[] buf;


    /**最后一个有效字节.最后一个可用数据的下标
     * Last valid byte.
     */
    protected int lastValid;


    /** 当前读取到的下标.在缓冲区中的位置.
     * Position in the buffer.
     */
    protected int pos;


    /** 指向header在buffer尾部的下标.即在缓冲区的header末端的位置,也就是body的开始.
     * Pos of the end of the header in the buffer, which is also the
     * start of the body.
     */
    protected int end;


    /** 内部包含一个InputBuffer,代理当前对象的一些方法.用于宝露露给外部读取数据的api.潜在的输入缓冲区
     * Underlying input buffer.
     */
    protected InputBuffer inputStreamInputBuffer;


    /** 所有filter数组
     * Filter library.
     * Note: Filter[0] is always the "chunked" filter.
     */
    protected InputFilter[] filterLibrary;


    /** 活动的filter数组.当外部用buffer读取数据时,若有filter,则用filter处理
     * Active filters (in order).
     */
    protected InputFilter[] activeFilters;


    /** 最后一个活动的filter的下标
     * Index of the last active filter.
     */
    protected int lastActiveFilter;


    protected boolean rejectIllegalHeaderName;


    // ------------------------------------------------------------- Properties


    /**
     * Add an input filter to the filter library.
     */
    public void addFilter(InputFilter filter) {

        // FIXME: Check for null ?

        InputFilter[] newFilterLibrary = 
            new InputFilter[filterLibrary.length + 1];
        for (int i = 0; i < filterLibrary.length; i++) {
            newFilterLibrary[i] = filterLibrary[i];
        }
        newFilterLibrary[filterLibrary.length] = filter;
        filterLibrary = newFilterLibrary;

        activeFilters = new InputFilter[filterLibrary.length];

    }


    /**
     * Get filters.
     */
    public InputFilter[] getFilters() {

        return filterLibrary;

    }


    /**
     * Add an input filter to the filter library.
     */
    public void addActiveFilter(InputFilter filter) {

        if (lastActiveFilter == -1) {
            filter.setBuffer(inputStreamInputBuffer);
        } else {
            for (int i = 0; i <= lastActiveFilter; i++) {
                if (activeFilters[i] == filter)
                    return;
            }
            filter.setBuffer(activeFilters[lastActiveFilter]);
        }

        activeFilters[++lastActiveFilter] = filter;

        filter.setRequest(request);

    }


    /**
     * Set the swallow input flag.
     */
    public void setSwallowInput(boolean swallowInput) {
        this.swallowInput = swallowInput;
    }


    /** 解析请求行
     * Implementations are expected to call {@link Request#setStartTime(long)}
     * as soon as the first byte is read from the request.
     */
    public abstract boolean parseRequestLine(boolean useAvailableDataOnly)
        throws IOException;
    /**解析请求头*/
    public abstract boolean parseHeaders() throws IOException;
    
    protected abstract boolean fill(boolean block) throws IOException; 
    /**将inputbuf底层用的socket与当前要处理的socket关联起来,一些数据大小限制参数的设置  */
    protected abstract void init(SocketWrapper<S> socketWrapper,
            AbstractEndpoint<S> endpoint) throws IOException;


    // --------------------------------------------------------- Public Methods


    /** 当关闭连接时调用,回收input buffer
     * Recycle the input buffer. This should be called when closing the 
     * connection.
     */
    public void recycle() {

        // Recycle Request object
        request.recycle();

        // Recycle filters
        for (int i = 0; i <= lastActiveFilter; i++) {
            activeFilters[i].recycle();
        }

        lastValid = 0;
        pos = 0;
        lastActiveFilter = -1;
        parsingHeader = true;
        swallowInput = true;

    }


    /** 结束当前http请求的处理
     * End processing of current HTTP request.
     * Note: All bytes of the current request should have been already 
     * consumed. This method only resets all the pointers so that we are ready
     * to parse the next HTTP request.
     */
    public void nextRequest() {

        // Recycle Request object
        request.recycle();

        // Copy leftover bytes to the beginning of the buffer
        if (lastValid - pos > 0 && pos > 0) {
            System.arraycopy(buf, pos, buf, 0, lastValid - pos);
        }
        // Always reset pos to zero
        lastValid = lastValid - pos;
        pos = 0;

        // Recycle filters
        for (int i = 0; i <= lastActiveFilter; i++) {
            activeFilters[i].recycle();
        }

        // Reset pointers
        lastActiveFilter = -1;
        parsingHeader = true;
        swallowInput = true;
    }


    /**
     * End request (consumes leftover bytes).
     * 
     * @throws IOException an underlying I/O error occurred
     */
    public void endRequest() throws IOException {

        if (swallowInput && (lastActiveFilter != -1)) {
            int extraBytes = (int) activeFilters[lastActiveFilter].end();
            pos = pos - extraBytes;
        }
    }
    

    /**
     * Available bytes in the buffers (note that due to encoding, this may not
     * correspond).
     */
    public int available() {
        int result = (lastValid - pos);
        if ((result == 0) && (lastActiveFilter >= 0)) {
            for (int i = 0; (result == 0) && (i <= lastActiveFilter); i++) {
                result = activeFilters[i].available();
            }
        }
        return result;
    }


    // ---------------------------------------------------- InputBuffer Methods

    /** 读取数据
     * Read some bytes.
     */
    @Override
    public int doRead(ByteChunk chunk, Request req) 
        throws IOException {

        if (lastActiveFilter == -1)// 若没有 活动的filter,inputStreamInputBuffer可直接读取request
            return inputStreamInputBuffer.doRead(chunk, req);
        else
            return activeFilters[lastActiveFilter].doRead(chunk,req);// 否则需要filter链介入

    }
}
