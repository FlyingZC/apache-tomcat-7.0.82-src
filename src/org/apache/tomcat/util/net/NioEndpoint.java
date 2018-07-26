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

package org.apache.tomcat.util.net;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.FileChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.X509KeyManager;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.IntrospectionUtils;
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;
import org.apache.tomcat.util.net.SecureNioChannel.ApplicationBufferHandler;
import org.apache.tomcat.util.net.jsse.NioX509KeyManager;

/**
 * NIO tailored thread pool, providing the following services:
 * <ul>
 * <li>Socket acceptor thread</li>
 * <li>Socket poller thread</li>
 * <li>Worker threads pool</li>
 * </ul>
 *
 * When switching to Java 5, there's an opportunity to use the virtual
 * machine's thread pool.
 *
 * @author Mladen Turk
 * @author Remy Maucherat
 * @author Filip Hanik
 */
public class NioEndpoint extends AbstractEndpoint<NioChannel> {


    // -------------------------------------------------------------- Constants


    private static final Log log = LogFactory.getLog(NioEndpoint.class);


    public static final int OP_REGISTER = 0x100; //register interest op
    public static final int OP_CALLBACK = 0x200; //callback interest op

    // ----------------------------------------------------------------- Fields

    protected NioSelectorPool selectorPool = new NioSelectorPool();

    /**
     * Server socket "pointer".
     */
    protected ServerSocketChannel serverSock = null;

    /**
     * use send file
     */
    protected boolean useSendfile = true;

    /**
     * The size of the OOM parachute.
     */
    protected int oomParachute = 1024*1024;
    /**
     * The oom parachute, when an OOM error happens,
     * will release the data, giving the JVM instantly
     * a chunk of data to be able to recover with.
     */
    protected byte[] oomParachuteData = null;

    /**
     * Make sure this string has already been allocated
     */
    protected static final String oomParachuteMsg =
        "SEVERE:Memory usage is low, parachute is non existent, your system may start failing.";

    /**
     * Keep track of OOM warning messages.
     */
    long lastParachuteCheck = System.currentTimeMillis();

    /**
     *
     */
    protected volatile CountDownLatch stopLatch = null;

    /**
     * Cache for SocketProcessor objects
     */
    protected ConcurrentLinkedQueue<SocketProcessor> processorCache = new ConcurrentLinkedQueue<SocketProcessor>() {
        private static final long serialVersionUID = 1L;
        protected AtomicInteger size = new AtomicInteger(0);
        @Override
        public boolean offer(SocketProcessor sc) {
            sc.reset(null,null);
            boolean offer = socketProperties.getProcessorCache()==-1?true:size.get()<socketProperties.getProcessorCache();
            //avoid over growing our cache or add after we have stopped
            if ( running && (!paused) && (offer) ) {
                boolean result = super.offer(sc);
                if ( result ) {
                    size.incrementAndGet();
                }
                return result;
            }
            else return false;
        }

        @Override
        public SocketProcessor poll() {
            SocketProcessor result = super.poll();
            if ( result != null ) {
                size.decrementAndGet();
            }
            return result;
        }

        @Override
        public void clear() {
            super.clear();
            size.set(0);
        }
    };


    /**
     * Cache for key attachment objects. //keyAttachment对象的缓存队列
     */
    protected ConcurrentLinkedQueue<KeyAttachment> keyCache = new ConcurrentLinkedQueue<KeyAttachment>() {
        private static final long serialVersionUID = 1L;
        protected AtomicInteger size = new AtomicInteger(0);
        @Override
        public boolean offer(KeyAttachment ka) {
            ka.reset();
            boolean offer = socketProperties.getKeyCache()==-1?true:size.get()<socketProperties.getKeyCache();
            //avoid over growing our cache or add after we have stopped
            if ( running && (!paused) && (offer) ) {
                boolean result = super.offer(ka);
                if ( result ) {
                    size.incrementAndGet();
                }
                return result;
            }
            else return false;
        }

        @Override
        public KeyAttachment poll() {
            KeyAttachment result = super.poll();
            if ( result != null ) {
                size.decrementAndGet();
            }
            return result;
        }

        @Override
        public void clear() {
            super.clear();
            size.set(0);
        }
    };


    /** PollerEvent缓存队列. PollerEvent包含NioChannel包含SocketChannel
     * Cache for poller events
     */
    protected ConcurrentLinkedQueue<PollerEvent> eventCache = new ConcurrentLinkedQueue<PollerEvent>() {
        private static final long serialVersionUID = 1L;
        protected AtomicInteger size = new AtomicInteger(0);
        @Override
        public boolean offer(PollerEvent pe) {
            pe.reset();
            boolean offer = socketProperties.getEventCache()==-1?true:size.get()<socketProperties.getEventCache();
            //avoid over growing our cache or add after we have stopped
            if ( running && (!paused) && (offer) ) {
                boolean result = super.offer(pe);
                if ( result ) {
                    size.incrementAndGet();
                }
                return result;
            }
            else return false;
        }

        @Override
        public PollerEvent poll() {
            PollerEvent result = super.poll();
            if ( result != null ) {
                size.decrementAndGet();
            }
            return result;
        }

        @Override
        public void clear() {
            super.clear();
            size.set(0);
        }
    };


    /** 将关闭通道的nioChannel放入Queue缓存起来,方便复用.复用时替换掉其内部的SocketChannel对象即可,NioChannel包含的其他属性只需做重置操作即可.当获取不到时再重新创建
     * Bytebuffer cache, each channel holds a set of buffers (two, except for SSL holds four)
     */
    protected ConcurrentLinkedQueue<NioChannel> nioChannels = new ConcurrentLinkedQueue<NioChannel>() {
        private static final long serialVersionUID = 1L;
        protected AtomicInteger size = new AtomicInteger(0);
        protected AtomicInteger bytes = new AtomicInteger(0);
        @Override
        public boolean offer(NioChannel socket) {
            boolean offer = socketProperties.getBufferPool()==-1?true:size.get()<socketProperties.getBufferPool();
            offer = offer && (socketProperties.getBufferPoolSize()==-1?true:(bytes.get()+socket.getBufferSize())<socketProperties.getBufferPoolSize());
            //avoid over growing our cache or add after we have stopped
            if ( running && (!paused) && (offer) ) {
                boolean result = super.offer(socket);
                if ( result ) {
                    size.incrementAndGet();
                    bytes.addAndGet(socket.getBufferSize());
                }
                return result;
            }
            else return false;
        }

        @Override
        public NioChannel poll() {
            NioChannel result = super.poll();
            if ( result != null ) {
                size.decrementAndGet();
                bytes.addAndGet(-result.getBufferSize());
            }
            return result;
        }

        @Override
        public void clear() {
            super.clear();
            size.set(0);
            bytes.set(0);
        }
    };


    // ------------------------------------------------------------- Properties


    /**
     * Generic properties, introspected
     */
    @Override
    public boolean setProperty(String name, String value) {
        final String selectorPoolName = "selectorPool.";
        try {
            if (name.startsWith(selectorPoolName)) {
                return IntrospectionUtils.setProperty(selectorPool, name.substring(selectorPoolName.length()), value);
            } else {
                return super.setProperty(name, value);
            }
        }catch ( Exception x ) {
            log.error("Unable to set attribute \""+name+"\" to \""+value+"\"",x);
            return false;
        }
    }


    /**
     * Priority of the poller threads.
     */
    protected int pollerThreadPriority = Thread.NORM_PRIORITY;
    public void setPollerThreadPriority(int pollerThreadPriority) { this.pollerThreadPriority = pollerThreadPriority; }
    public int getPollerThreadPriority() { return pollerThreadPriority; }


    /**
     * Handling of accepted sockets.
     */
    protected Handler handler = null;
    public void setHandler(Handler handler ) { this.handler = handler; }
    public Handler getHandler() { return handler; }


    /**
     * Allow comet request handling.
     */
    protected boolean useComet = true;
    public void setUseComet(boolean useComet) { this.useComet = useComet; }
    @Override
    public boolean getUseComet() { return useComet; }
    @Override
    public boolean getUseCometTimeout() { return getUseComet(); }
    @Override
    public boolean getUsePolling() { return true; } // Always supported


    /** Poller线程数量,最少2个.最多和cpu核树一致
     * Poller thread count.
     */
    protected int pollerThreadCount = Math.min(2,Runtime.getRuntime().availableProcessors());
    public void setPollerThreadCount(int pollerThreadCount) { this.pollerThreadCount = pollerThreadCount; }
    public int getPollerThreadCount() { return pollerThreadCount; }

    protected long selectorTimeout = 1000;
    public void setSelectorTimeout(long timeout){ this.selectorTimeout = timeout;}
    public long getSelectorTimeout(){ return this.selectorTimeout; }
    /**
     * The socket poller.poller线程池
     */
    protected Poller[] pollers = null;
    protected AtomicInteger pollerRotater = new AtomicInteger(0);
    /** 轮询当前的Poller线程数组,从中取出一个Poller并返回
     * Return an available poller in true round robin fashion
     */
    public Poller getPoller0() {
        int idx = Math.abs(pollerRotater.incrementAndGet()) % pollers.length;// 最简单的轮询调度三方,poller的计数器不断加1 再对poller数组长度 取余数
        return pollers[idx];
    }


    public void setSelectorPool(NioSelectorPool selectorPool) {
        this.selectorPool = selectorPool;
    }

    public void setSocketProperties(SocketProperties socketProperties) {
        this.socketProperties = socketProperties;
    }

    public void setUseSendfile(boolean useSendfile) {
        this.useSendfile = useSendfile;
    }

    /**
     * Is deferAccept supported?
     */
    @Override
    public boolean getDeferAccept() {
        // Not supported
        return false;
    }

    public void setOomParachute(int oomParachute) {
        this.oomParachute = oomParachute;
    }

    public void setOomParachuteData(byte[] oomParachuteData) {
        this.oomParachuteData = oomParachuteData;
    }


    protected SSLContext sslContext = null;
    public SSLContext getSSLContext() { return sslContext;}
    public void setSSLContext(SSLContext c) { sslContext = c;}

    private String[] enabledCiphers;
    private String[] enabledProtocols;


    /**
     * Port in use.
     */
    @Override
    public int getLocalPort() {
        ServerSocketChannel ssc = serverSock;
        if (ssc == null) {
            return -1;
        } else {
            ServerSocket s = ssc.socket();
            if (s == null) {
                return -1;
            } else {
                return s.getLocalPort();
            }
        }
    }


    // --------------------------------------------------------- OOM Parachute Methods

    protected void checkParachute() {
        boolean para = reclaimParachute(false);
        if (!para && (System.currentTimeMillis()-lastParachuteCheck)>10000) {
            try {
                log.fatal(oomParachuteMsg);
            }catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                System.err.println(oomParachuteMsg);
            }
            lastParachuteCheck = System.currentTimeMillis();
        }
    }

    protected boolean reclaimParachute(boolean force) {
        if ( oomParachuteData != null ) return true;
        if ( oomParachute > 0 && ( force || (Runtime.getRuntime().freeMemory() > (oomParachute*2))) )
            oomParachuteData = new byte[oomParachute];
        return oomParachuteData != null;
    }

    protected void releaseCaches() {
        this.keyCache.clear();
        this.nioChannels.clear();
        this.processorCache.clear();
        if ( handler != null ) handler.recycle();

    }

    // --------------------------------------------------------- Public Methods
    /**
     * Number of keepalive sockets.
     */
    public int getKeepAliveCount() {
        if (pollers == null) {
            return 0;
        } else {
            int sum = 0;
            for (int i=0; i<pollers.length; i++) {
                sum += pollers[i].getKeyCount();
            }
            return sum;
        }
    }


    // ----------------------------------------------- Public Lifecycle Methods


    /** 开启一个新的serverSocketChannel.绑定IP地址及端口,将ServerSocketChannel设置为阻塞,设置超时时间.
     * Initialize the endpoint.
     */
    @Override
    public void bind() throws Exception {
        // 开启一个新的serverSocketChannel
        serverSock = ServerSocketChannel.open();
        socketProperties.setProperties(serverSock.socket());
        InetSocketAddress addr = (getAddress()!=null?new InetSocketAddress(getAddress(),getPort()):new InetSocketAddress(getPort()));
        serverSock.socket().bind(addr,getBacklog());// 绑定ip地址
        serverSock.configureBlocking(true); //mimic APR behavior 设置成阻塞模式
        serverSock.socket().setSoTimeout(getSocketProperties().getSoTimeout());// 设置超时.超时抛java.net.SocketTimeoutException

        // Initialize thread count defaults for acceptor, poller
        if (acceptorThreadCount == 0) {// acceptor默认线程数1
            // FIXME: Doesn't seem to work that well with multiple accept threads
            acceptorThreadCount = 1;
        }
        if (pollerThreadCount <= 0) {
            //minimum one poller thread
            pollerThreadCount = 1;
        }
        stopLatch = new CountDownLatch(pollerThreadCount);

        // Initialize SSL if needed
        if (isSSLEnabled()) {
            SSLUtil sslUtil = handler.getSslImplementation().getSSLUtil(this);

            sslContext = sslUtil.createSSLContext();
            sslContext.init(wrap(sslUtil.getKeyManagers()),
                    sslUtil.getTrustManagers(), null);

            SSLSessionContext sessionContext =
                sslContext.getServerSessionContext();
            if (sessionContext != null) {
                sslUtil.configureSessionContext(sessionContext);
            }
            // Determine which cipher suites and protocols to enable
            enabledCiphers = sslUtil.getEnableableCiphers(sslContext);
            enabledProtocols = sslUtil.getEnableableProtocols(sslContext);
        }

        if (oomParachute>0) reclaimParachute(true);
        selectorPool.open();// 创建selector
    }

    public KeyManager[] wrap(KeyManager[] managers) {
        if (managers==null) return null;
        KeyManager[] result = new KeyManager[managers.length];
        for (int i=0; i<result.length; i++) {
            if (managers[i] instanceof X509KeyManager && getKeyAlias()!=null) {
                String keyAlias = getKeyAlias();
                // JKS keystores always convert the alias name to lower case
                if ("jks".equalsIgnoreCase(getKeystoreType())) {
                    keyAlias = keyAlias.toLowerCase(Locale.ENGLISH);
                }
                result[i] = new NioX509KeyManager((X509KeyManager) managers[i], keyAlias);
            } else {
                result[i] = managers[i];
            }
        }
        return result;
    }


    /** 启动endpoint,创建acceptor,poller线程
     * Start the NIO endpoint, creating acceptor, poller threads.
     */
    @Override
    public void startInternal() throws Exception {

        if (!running) {
            running = true;
            paused = false;

            // Create worker collection. 启动executors,用于执行SocketProcessor任务
            if ( getExecutor() == null ) {
                createExecutor();
            }

            initializeConnectionLatch();

            // Start poller threads. 创建并启动poller线程(轮询器),启动后会执行poller里的run()
            pollers = new Poller[getPollerThreadCount()];
            for (int i=0; i<pollers.length; i++) {
                pollers[i] = new Poller();
                Thread pollerThread = new Thread(pollers[i], getName() + "-ClientPoller-"+i);
                pollerThread.setPriority(threadPriority);
                pollerThread.setDaemon(true);
                pollerThread.start();
            }

            startAcceptorThreads();// 启动acceptor,即接收者线程
        }
    }


    /**
     * Stop the endpoint. This will cause all processing threads to stop.
     */
    @Override
    public void stopInternal() {
        releaseConnectionLatch();
        if (!paused) {
            pause();
        }
        if (running) {
            running = false;
            unlockAccept();
            for (int i=0; pollers!=null && i<pollers.length; i++) {
                if (pollers[i]==null) continue;
                pollers[i].destroy();
                pollers[i] = null;
            }
            try {
                if (!stopLatch.await(selectorTimeout + 100, TimeUnit.MILLISECONDS)) {
                    log.warn(sm.getString("endpoint.nio.stopLatchAwaitFail"));
                }
            } catch (InterruptedException e) {
                log.warn(sm.getString("endpoint.nio.stopLatchAwaitInterrupted"), e);
            }
        }
        eventCache.clear();
        keyCache.clear();
        nioChannels.clear();
        processorCache.clear();
        shutdownExecutor();
    }


    /**
     * Deallocate NIO memory pools, and close server socket.
     */
    @Override
    public void unbind() throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("Destroy initiated for "+new InetSocketAddress(getAddress(),getPort()));
        }
        if (running) {
            stop();
        }
        // Close server socket
        serverSock.socket().close();
        serverSock.close();
        serverSock = null;
        sslContext = null;
        releaseCaches();
        selectorPool.close();
        if (log.isDebugEnabled()) {
            log.debug("Destroy completed for "+new InetSocketAddress(getAddress(),getPort()));
        }
    }


    // ------------------------------------------------------ Protected Methods


    public int getWriteBufSize() {
        return socketProperties.getTxBufSize();
    }

    public int getReadBufSize() {
        return socketProperties.getRxBufSize();
    }

    public NioSelectorPool getSelectorPool() {
        return selectorPool;
    }

    @Override
    public boolean getUseSendfile() {
        return useSendfile;
    }

    public int getOomParachute() {
        return oomParachute;
    }

    public byte[] getOomParachuteData() {
        return oomParachuteData;
    }


    @Override
    protected AbstractEndpoint.Acceptor createAcceptor() {
        return new Acceptor();
    }


    /**  设置socket属性,这里面主要 将socketChannel包装到nioChannel里.再将nioChannel包装到PollerEvent里,并放入轮询的事件队列中
     * Process the specified connection.
     */
    protected boolean setSocketOptions(SocketChannel socket) {
        // Process the connection
        try {
            //disable blocking, APR style, we are gonna be polling it
            socket.configureBlocking(false);// 将socket连接通道(socketChannel)设置为非阻塞模式
            Socket sock = socket.socket();// 从nio.SocketChannel中获取java.net.Socket
            socketProperties.setProperties(sock);// 设置socket的参数值(从server.xml的Connector节点上获取参数值),如 socket发送,接收的大小.心跳检测等

            NioChannel channel = nioChannels.poll();// 构造NioChannel对象,从ConcurrentLinkedQueue(NioChannel的线程安全的缓存队列)队列中取
            if ( channel == null ) {// 若从缓存队列Queue中取不到nioChannel,则新创建
                // SSL setup
                if (sslContext != null) {
                    SSLEngine engine = createSSLEngine();
                    int appbufsize = engine.getSession().getApplicationBufferSize();
                    NioBufferHandler bufhandler = new NioBufferHandler(Math.max(appbufsize,socketProperties.getAppReadBufSize()),
                                                                       Math.max(appbufsize,socketProperties.getAppWriteBufSize()),
                                                                       socketProperties.getDirectBuffer());
                    channel = new SecureNioChannel(socket, engine, bufhandler, selectorPool);// 将socketChannel对象 封装到NioChannel对象中.这种是SSL的
                } else {
                    // normal tcp setup. NioBufferHandler这个类用于 分配nio的buffer的读写空间
                    NioBufferHandler bufhandler = new NioBufferHandler(socketProperties.getAppReadBufSize(),
                                                                       socketProperties.getAppWriteBufSize(),
                                                                       socketProperties.getDirectBuffer());

                    channel = new NioChannel(socket, bufhandler);// 将socketChannel对象 封装到NioChannel对象中.非SSL的
                }
            } else {// 从Queue中能去到nioChannel的情况
                channel.setIOChannel(socket);// 将socketChannel对象 封装到NioChannel对象中
                if ( channel instanceof SecureNioChannel ) {
                    SSLEngine engine = createSSLEngine();
                    ((SecureNioChannel)channel).reset(engine);// 使用reset重置nioChannel的属性,实现复用
                } else {
                    channel.reset();// 使用reset重置nioChannel的属性,实现复用
                }
            }
            getPoller0().register(channel);// register(): 将新接收到的SocketChannel注册到event队列上(nioChannel包装成PollerEvent 注册到轮询线程)
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            try {
                log.error("",t);
            } catch (Throwable tt) {
                ExceptionUtils.handleThrowable(tt);
            }
            // Tell to close the socket
            return false;
        }
        return true;
    }

    protected SSLEngine createSSLEngine() {
        SSLEngine engine = sslContext.createSSLEngine();
        if ("false".equals(getClientAuth())) {
            engine.setNeedClientAuth(false);
            engine.setWantClientAuth(false);
        } else if ("true".equals(getClientAuth()) || "yes".equals(getClientAuth())){
            engine.setNeedClientAuth(true);
        } else if ("want".equals(getClientAuth())) {
            engine.setWantClientAuth(true);
        }
        engine.setUseClientMode(false);
        engine.setEnabledCipherSuites(enabledCiphers);
        engine.setEnabledProtocols(enabledProtocols);

        configureUseServerCipherSuitesOrder(engine);

        return engine;
    }


    /**
     * Returns true if a worker thread is available for processing.
     * @return boolean
     */
    protected boolean isWorkerAvailable() {
        return true;
    }


    @Override
    public void processSocketAsync(SocketWrapper<NioChannel> socketWrapper,
            SocketStatus socketStatus) {
        dispatchForEvent(socketWrapper.getSocket(), socketStatus, true);
    }

    public boolean dispatchForEvent(NioChannel socket, SocketStatus status, boolean dispatch) {
        if (dispatch && status == SocketStatus.OPEN_READ) {
            socket.getPoller().add(socket, OP_CALLBACK);
        } else {
            processSocket(socket,status,dispatch);
        }
        return true;
    }
    /**把SocketProcessor交给Excutor去执行.poller.run()-> processKey()处理selector检测到的通道事件-> processSocket()调用具体的通道处理逻辑*/
    public boolean processSocket(NioChannel socket, SocketStatus status, boolean dispatch) {
        try {
            KeyAttachment attachment = (KeyAttachment)socket.getAttachment();
            if (attachment == null) {
                return false;
            }
            attachment.setCometNotify(false); //will get reset upon next reg
            SocketProcessor sc = processorCache.poll();// 从SocketProcessor缓存队列中取出一个来处理socket
            if ( sc == null ) sc = new SocketProcessor(socket,status);// 没有则创建
            else sc.reset(socket,status);// 重置
            if ( dispatch && getExecutor()!=null ) getExecutor().execute(sc);// 若有事件发生的socket交给Executor处理
            else sc.run();
        } catch (RejectedExecutionException rx) {
            log.warn("Socket processing request was rejected for:"+socket,rx);
            return false;
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            // This means we got an OOM or similar creating a thread, or that
            // the pool and its queue are full
            log.error(sm.getString("endpoint.process.fail"), t);
            return false;
        }
        return true;
    }


    @Override
    public void removeWaitingRequest(SocketWrapper<NioChannel> socketWrapper) {
        // NO-OP
    }


    @Override
    protected Log getLog() {
        return log;
    }


    // --------------------------------------------------- Acceptor Inner Class
    /** 后台线程，用于监听TCP/IP连接以及将它们分发给相应的调度器处理。  
     * The background thread that listens for incoming TCP/IP connections and
     * hands them off to an appropriate processor.
     */
    protected class Acceptor extends AbstractEndpoint.Acceptor {

        @Override
        public void run() {

            int errorDelay = 0;

            // Loop until we receive a shutdown command.循环遍历 直到接收到关闭命令
            while (running) {

                // Loop if endpoint is paused. 如果endpoint被暂停 且 running 则一直循环
                while (paused && running) {
                    state = AcceptorState.PAUSED;// acceptor状态设置为paused
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }

                if (!running) {// 不是running 则跳出循环
                    break;
                }
                state = AcceptorState.RUNNING;// acceptor状态 设置为running

                try {
                    //if we have reached max connections, wait. 若已经到达 最大连接数 则让线程等待(直到其他线程释放连接)
                    countUpOrAwaitConnection();

                    SocketChannel socket = null;
                    try {
                        // Accept the next incoming connection from the server
                        // socket. 接收新连接,返回SocketChannel对象,这里用的是阻塞模式.整个循环靠此处阻塞
                        socket = serverSock.accept();
                    } catch (IOException ioe) {
                        //we didn't get a socket
                        countDownConnection();
                        // Introduce delay if necessary
                        errorDelay = handleExceptionWithDelay(errorDelay);
                        // re-throw
                        throw ioe;
                    }
                    // Successful accept, reset the error delay
                    errorDelay = 0;

                    // setSocketOptions() will add channel to the poller.设置socket属性,setSocketOptions()将会 把channel添加到poller中
                    // if successful
                    if (running && !paused) {
                        if (!setSocketOptions(socket)) {// 设置socket属性,这里面主要 将socketChannel包装到nioChannel里.再将nioChannel包装到PollerEvent里,并放入轮询的事件队列events中
                            countDownConnection();
                            closeSocket(socket);
                        }
                    } else {
                        countDownConnection();
                        closeSocket(socket);
                    }
                } catch (SocketTimeoutException sx) {
                    // Ignore: Normal condition
                } catch (IOException x) {
                    if (running) {
                        log.error(sm.getString("endpoint.accept.fail"), x);
                    }
                } catch (OutOfMemoryError oom) {
                    try {
                        oomParachuteData = null;
                        releaseCaches();
                        log.error("", oom);
                    }catch ( Throwable oomt ) {
                        try {
                            try {
                                System.err.println(oomParachuteMsg);
                                oomt.printStackTrace();
                            }catch (Throwable letsHopeWeDontGetHere){
                                ExceptionUtils.handleThrowable(letsHopeWeDontGetHere);
                            }
                        }catch (Throwable letsHopeWeDontGetHere){
                            ExceptionUtils.handleThrowable(letsHopeWeDontGetHere);
                        }
                    }
                } catch (Throwable t) {
                    ExceptionUtils.handleThrowable(t);
                    log.error(sm.getString("endpoint.accept.fail"), t);
                }
            }
            state = AcceptorState.ENDED;
        }
    }

    /**关闭socket连接*/
    private void closeSocket(SocketChannel socket) {
        try {
            socket.socket().close();
        } catch (IOException ioe)  {
            if (log.isDebugEnabled()) {
                log.debug("", ioe);
            }
        }
        try {
            socket.close();
        } catch (IOException ioe) {
            if (log.isDebugEnabled()) {
                log.debug("", ioe);
            }
        }
    }


    // ----------------------------------------------------- Poller Inner Classes

    /**
     * 取出队列中新增的PollerEvent并向Selector注册或更新感兴趣的事件
     * PollerEvent, cacheable object for poller events to avoid GC
     */
    public static class PollerEvent implements Runnable {
        // 持有NioChannel的引用
        protected NioChannel socket;
        protected int interestOps;// 感兴趣的事件码
        protected KeyAttachment key;// KeyAttachment包装nioChannel
        public PollerEvent(NioChannel ch, KeyAttachment k, int intOps) {
            reset(ch, k, intOps);
        }

        public void reset(NioChannel ch, KeyAttachment k, int intOps) {
            socket = ch;
            interestOps = intOps;
            key = k;
        }

        public void reset() {
            reset(null, null, 0);
        }

        @Override
        public void run() {
            if ( interestOps == OP_REGISTER ) {// (PollerEvent的interestOps为OP_REGISTER)当socket第一次注册到selector中时,完成对socket读事件的注册OP_READ(将nio中的channel注册到selector上,这样才能后续从selector中获取key)
                try {
                    socket.getIOChannel().register(socket.getPoller().getSelector(), SelectionKey.OP_READ, key);// 注册 读事件.(socket为NioChannel对象),即socketChannel.register(selector, opts, attr).att-所得键的附件信息KeyAttachment，可能为 null 
                } catch (Exception x) {
                    log.error("", x);
                }
            } else {// 选择键:其实就是socketChannel.keyFor(selector); keyFor获取通道(channel)向给定选择器(selector)注册的键.socket之前已经注册到了selector中,更新socket感兴趣的事件
                final SelectionKey key = socket.getIOChannel().keyFor(socket.getPoller().getSelector());
                try {
                    if (key == null) {
                        // The key was cancelled (e.g. due to socket closure)
                        // and removed from the selector while it was being
                        // processed. Count down the connections at this point
                        // since it won't have been counted down when the socket
                        // closed.
                        socket.getPoller().getEndpoint().countDownConnection();
                    } else {
                        final KeyAttachment att = (KeyAttachment) key.attachment();// 获取selectionKey附加的KeyAttachment对象
                        if ( att!=null ) {
                            //handle callback flag
                            if (att.isComet() && (interestOps & OP_CALLBACK) == OP_CALLBACK ) {
                                att.setCometNotify(true);
                            } else {
                                att.setCometNotify(false);
                            }
                            interestOps = (interestOps & (~OP_CALLBACK));//remove the callback flag
                            att.access();//to prevent timeout. 刷新事件的最后访问时间,防止事件超时
                            //we are registering the key to start with, reset the fairness counter.
                            int ops = key.interestOps() | interestOps;// 叠加注册PollerEvent上的事件.用|操作
                            att.interestOps(ops);
                            key.interestOps(ops);
                        } else {// 若获取不到KeyAttachment,SelectionKey.走这
                            socket.getPoller().cancelledKey(key, SocketStatus.ERROR, false);
                        }
                    }
                } catch (CancelledKeyException ckx) {
                    try {
                        socket.getPoller().cancelledKey(key, SocketStatus.DISCONNECT, true);
                    } catch (Exception ignore) {}
                }
            }//end if
        }//run

        @Override
        public String toString() {
            return super.toString()+"[intOps="+this.interestOps+"]";
        }
    }

    /** 轮询线程.每个Poller维护了一个Selector实例 以及 一个PollerEvent事件队列.每当接收到新的链接时,会将获得的SocketChannel对象封装成NioChannel,再封装成PollerEvent对象,并将其(PollerEvent)注册到Poller(创建一个PollerEvent实例,添加到事件队列events中)
     * Poller class.
     */
    public class Poller implements Runnable {

        protected Selector selector;// 这就是NIO中用到的选择器，每一个Poller都会关联一个Selector  
        protected ConcurrentLinkedQueue<Runnable> events = new ConcurrentLinkedQueue<Runnable>();// 待处理的事件队列.包含PollEvent implements Runnable
        
        protected volatile boolean close = false;
        protected long nextExpiration = 0;//optimize expiration handling. 优化过期处理
        /**唤醒多路复用器(即nio中的selector)的条件阈值.作用:1.告诉Poller当前有多少个新连接，这样当Poller进行selector的操作时，可以选择是否需要阻塞来等待读写请求到达;2.标识Poller在进行select选择时，是否有连接到达。如果有，就让当前的阻塞调用立即返回*/   
        protected AtomicLong wakeupCounter = new AtomicLong(0l);
        /**注册到Poller的channel中，I/O状态已经OK的的个数*/
        protected volatile int keyCount = 0;

        public Poller() throws IOException {
            synchronized (Selector.class) {// Selector.open()在JDK实现中有的版本不是线程安全的
                // Selector.open() isn't thread safe
                // http://bugs.sun.com/view_bug.do?bug_id=6427854
                // Affects 1.6.0_29, fixed in 1.7.0_01
                this.selector = Selector.open();// 创建选择器
            }
        }

        public int getKeyCount() { return keyCount; }

        public Selector getSelector() { return selector;}

        NioEndpoint getEndpoint() {
            return NioEndpoint.this;
        }

        /** 销毁Poller
         * Destroy the poller.
         */
        protected void destroy() {
            // Wait for polltime before doing anything, so that the poller threads
            // exit, otherwise parallel closure of sockets which are still
            // in the poller can cause problems
            close = true;
            selector.wakeup();// 如果另一个线程目前正阻塞在 select() 或 select(long) 方法的调用中,则该调用将立即返回
        }

        /** 将PollerEvent添加到events队列中
         * Only used in this class. Will be made private in Tomcat 8.0.x
         * @deprecated
         */
        @Deprecated
        public void addEvent(Runnable event) {
            events.offer(event);// 将event添加到events队列中
            if ( wakeupCounter.incrementAndGet() == 0 ) selector.wakeup();// 使一个还未返回的 select() 方法立即返回.即若当前事件队列中 没有事件, 则唤醒处于阻塞在selector.select()状态上的线程(会唤醒selector).与1208行对应
        }

        /**
         * Unused. Will be removed in Tomcat 8.0.x
         * @deprecated
         */
        @Deprecated
        public void cometInterest(NioChannel socket) {
            KeyAttachment att = (KeyAttachment)socket.getAttachment();
            add(socket,att.getCometOps());
            if ( (att.getCometOps()&OP_CALLBACK) == OP_CALLBACK ) {
                nextExpiration = 0; //force the check for faster callback
                selector.wakeup();
            }
        }

        /** 将NioChannel对象封装到PollerEvent对象中.  向轮询器poller添加指定的套接字和关联的pool。套接字将被添加到一个临时数组中，并在最大时间之后(在大多数情况下，延迟要低得多)进行轮询。
         * Add specified socket and associated pool to the poller. The socket will
         * be added to a temporary array, and polled first after a maximum amount
         * of time equal to pollTime (in most cases, latency will be much lower,
         * however).
         *
         * @param socket to add to the poller
         */
        public void add(final NioChannel socket) {
            add(socket,SelectionKey.OP_READ);
        }
        /**将NioChannel对象封装到PollerEvent对象中*/
        public void add(final NioChannel socket, final int interestOps) {
            PollerEvent r = eventCache.poll();// 取出PollerEvent
            if ( r==null) r = new PollerEvent(socket,null,interestOps);// 没有则新创建pollerEvent实例
            else r.reset(socket,null,interestOps);// 重置pollerEvent的属性
            addEvent(r);// 添加这个新的pollerEvent到events事件队列中
            if (close) {
                processSocket(socket, SocketStatus.STOP, false);
            }
        }

        /** 从events队列中取出pollerEvent对象并run();即处理轮询器的事件队列中的事件,若事件队列是空的则返回false。
         * Processes events in the event queue of the Poller.
         *
         * @return <code>true</code> if some events were processed,
         *   <code>false</code> if queue was empty
         */
        public boolean events() {
            boolean result = false;

            Runnable r = null;
            while ( (r = events.poll()) != null ) {// 从events队列 中 逐个取出PollerEvent事件,并执行相应的事件线程
                result = true;
                try {
                    r.run();// 运行每一个PollerEvent的处理逻辑
                    if ( r instanceof PollerEvent ) {// 只要从events中取出后,便便重置PollerEvent并缓存到eventCache中,方便复用PollerEvent对象
                        ((PollerEvent)r).reset();
                        eventCache.offer((PollerEvent)r);
                    }
                } catch ( Throwable x ) {
                    log.error("",x);
                }
            }

            return result;
        }
        /**1.创建KeyAttachment并与poller关联; 2.将channelSocket(nioChannel)包装成PollerEvent 注册到事件队列(events)中; 3.是否需要对selector wakeup的处理*/
        public void register(final NioChannel socket) {
            socket.setPoller(this);// 设置 NioChannel与Poller的关联
            KeyAttachment key = keyCache.poll();// 从NioEndpoint的keyCache缓存队列中 取出一个KeyAttachment
            final KeyAttachment ka = key!=null?key:new KeyAttachment(socket);// 获取不到则新建,KeyAttachment实际是NioChannel的包装类
            ka.reset(this,socket,getSocketProperties().getSoTimeout());// 重置KeyAttachment对象中Poller,NioChannel等成员变量的引用
            ka.setKeepAliveLeft(NioEndpoint.this.getMaxKeepAliveRequests());
            ka.setSecure(isSSLEnabled());
            PollerEvent r = eventCache.poll();// 从通道缓存队列中取出一个PollerEvent
            ka.interestOps(SelectionKey.OP_READ);//this is what OP_REGISTER turns into.设置keyAttachement 读操作(SelectionKey.OP_READ) 为感兴趣的操作
            if ( r==null) r = new PollerEvent(socket,ka,OP_REGISTER);// 若取出为空,新建.将nioChannel包装到PollerEvent对象中, 并注册一个OP_REGISTER(表示这是新的channel需要注册 )(对应)928行pollerEvent.run()里(该行会把接收到的新channel注册到selector中)
            else r.reset(socket,ka,OP_REGISTER);// 否则,重置PollerEvent属性
            addEvent(r);// 将PollerEvent添加到轮询队列(events)中
        }
        /**置空附件*/
        public KeyAttachment cancelledKey(SelectionKey key, SocketStatus status, boolean dispatch) {
            KeyAttachment ka = null;
            try {
                if ( key == null ) return null;//nothing to do
                ka = (KeyAttachment) key.attachment();
                if (ka != null && ka.isComet() && status != null) {
                    //the comet event takes care of clean up
                    //processSocket(ka.getChannel(), status, dispatch);
                    ka.setComet(false);//to avoid a loop
                    if (status == SocketStatus.TIMEOUT ) {
                        if (processSocket(ka.getChannel(), status, true)) {
                            return null; // don't close on comet timeout
                        }
                    } else {
                        // Don't dispatch if the lines below are cancelling the key
                        processSocket(ka.getChannel(), status, false);
                    }
                }
                ka = (KeyAttachment) key.attach(null);// 置空附件.即selectionKey.attach(null);通过附加 null 可丢弃当前的附加对象。 
                if (ka!=null) handler.release(ka);// 
                else handler.release((SocketChannel)key.channel());// 即NioEndpoint.handler.release(socketChannel)
                if (key.isValid()) key.cancel();// 即selectionKey.cancel().请求取消此键的通道到其选择器的注册。
                // If it is available, close the NioChannel first which should
                // in turn close the underlying SocketChannel. The NioChannel
                // needs to be closed first, if available, to ensure that TLS
                // connections are shut down cleanly.
                if (ka != null) {
                    try {
                        ka.getSocket().close(true);
                    } catch (Exception e){
                        if (log.isDebugEnabled()) {
                            log.debug(sm.getString(
                                    "endpoint.debug.socketCloseFail"), e);
                        }
                    }
                }
                // The SocketChannel is also available via the SelectionKey. If
                // it hasn't been closed in the block above, close it now.
                if (key.channel().isOpen()) {
                    try {
                        key.channel().close();// 关闭socketChannel
                    } catch (Exception e) {
                        if (log.isDebugEnabled()) {
                            log.debug(sm.getString(
                                    "endpoint.debug.channelCloseFail"), e);
                        }
                    }
                }
                try {
                    if (ka != null && ka.getSendfileData() != null
                            && ka.getSendfileData().fchannel != null
                            && ka.getSendfileData().fchannel.isOpen()) {
                        ka.getSendfileData().fchannel.close();
                    }
                } catch (Exception ignore) {
                }
                if (ka!=null) {
                    ka.reset();
                    countDownConnection();
                }
            } catch (Throwable e) {
                ExceptionUtils.handleThrowable(e);
                if (log.isDebugEnabled()) log.error("",e);
            }
            return ka;
        }
        /**
         * The background thread that listens for incoming TCP/IP connections and
         * hands them off to an appropriate processor.
         */
        @Override
        public void run() {
            // Loop until destroy() is called
            while (true) {
                try {
                    // Loop if endpoint is paused
                    while (paused && (!close) ) {
                        try {
                            Thread.sleep(100);// 0.1s轮询一次
                        } catch (InterruptedException e) {
                            // Ignore
                        }
                    }

                    boolean hasEvents = false;

                    // Time to terminate?
                    if (close) {
                        events();// 处理轮询器的事件队列中的事件
                        timeout(0, false);
                        try {
                            selector.close();
                        } catch (IOException ioe) {
                            log.error(sm.getString(
                                    "endpoint.nio.selectorCloseFail"), ioe);
                        }
                        break;
                    } else {
                        hasEvents = events();// 处理轮询器的事件队列中的事件!!!
                    }
                    try {
                        if ( !close ) {
                            if (wakeupCounter.getAndSet(-1) > 0) {// 把wakeupCounter设置成-1并返回旧值,与addEvent()里的代码相呼应,这样会唤醒selector
                                //if we are here, means we have other stuff to do.到这一步,说明有请求需要处理了
                                //do a non blocking select.开启非阻塞select
                                keyCount = selector.selectNow();// 以非阻塞方式查看selector是否有事件发生 
                            } else {
                                keyCount = selector.select(selectorTimeout);// 查看selector是否有事件发生,超出指定时间则立刻返回
                            }
                            wakeupCounter.set(0);
                        }
                        if (close) {
                            events();
                            timeout(0, false);
                            try {
                                selector.close();
                            } catch (IOException ioe) {
                                log.error(sm.getString(
                                        "endpoint.nio.selectorCloseFail"), ioe);
                            }
                            break;
                        }
                    } catch ( NullPointerException x ) {
                        //sun bug 5076772 on windows JDK 1.5
                        if ( log.isDebugEnabled() ) log.debug("Possibly encountered sun bug 5076772 on windows JDK 1.5",x);
                        if ( wakeupCounter == null || selector == null ) throw x;
                        continue;
                    } catch ( CancelledKeyException x ) {
                        //sun bug 5076772 on windows JDK 1.5
                        if ( log.isDebugEnabled() ) log.debug("Possibly encountered sun bug 5076772 on windows JDK 1.5",x);
                        if ( wakeupCounter == null || selector == null ) throw x;
                        continue;
                    } catch (Throwable x) {
                        ExceptionUtils.handleThrowable(x);
                        log.error("",x);
                        continue;
                    }
                    //either we timed out or we woke up, process events first
                    if ( keyCount == 0 ) hasEvents = (hasEvents | events());

                    Iterator<SelectionKey> iterator =
                        keyCount > 0 ? selector.selectedKeys().iterator() : null;// 根据向selector中注册的key遍历channel中已经就绪的keys,并处理这些key
                    // Walk through the collection of ready keys and dispatch
                    // any active event.
                    while (iterator != null && iterator.hasNext()) {
                        SelectionKey sk = iterator.next();
                        KeyAttachment attachment = (KeyAttachment)sk.attachment();// 这里的attachment()返回的就是在register()中注册的.KeyAttachement对视是对socket的包装类
                        // Attachment may be null if another thread has called
                        // cancelledKey()
                        if (attachment == null) {
                            iterator.remove();
                        } else {
                            attachment.access();// 更新 通道最近一次发生事件的事件,防止因超时没有事件发生而被剔除出selector
                            iterator.remove();
                            processKey(sk, attachment);// 具体处理通道的逻辑,交由SocketProcessor处理
                        }
                    }//while

                    //process timeouts. 多路复用器(selector) 每执行一遍完整的轮询 便查看所有通道是否超时,对超时的通道将会被剔除出多路复用器(selector)
                    timeout(keyCount,hasEvents);
                    if ( oomParachute > 0 && oomParachuteData == null ) checkParachute();
                } catch (OutOfMemoryError oom) {
                    try {
                        oomParachuteData = null;
                        releaseCaches();
                        log.error("", oom);
                    }catch ( Throwable oomt ) {
                        try {
                            System.err.println(oomParachuteMsg);
                            oomt.printStackTrace();
                        }catch (Throwable letsHopeWeDontGetHere){
                            ExceptionUtils.handleThrowable(letsHopeWeDontGetHere);
                        }
                    }
                }
            }//while

            stopLatch.countDown();
        }
        /**处理selector检测到的通道事件*/
        protected boolean processKey(SelectionKey sk, KeyAttachment attachment) {
            boolean result = true;
            try {
                if ( close ) {
                    cancelledKey(sk, SocketStatus.STOP, attachment.comet);
                } else if ( sk.isValid() && attachment != null ) {
                    attachment.access();//make sure we don't time out valid sockets. 确保通道不会因超时而被剔除
                    sk.attach(attachment);//cant remember why this is here
                    NioChannel channel = attachment.getChannel();
                    if (sk.isReadable() || sk.isWritable() ) {// 处理通道发生的 读写事件
                        if ( attachment.getSendfileData() != null ) {
                            processSendfile(sk,attachment, false);
                        } else {
                            if ( isWorkerAvailable() ) {
                                unreg(sk, attachment, sk.readyOps());// 在通道上 注销 对已经发生事件的关注
                                boolean closeSocket = false;
                                // Read goes before write
                                if (sk.isReadable()) {
                                    if (!processSocket(channel, SocketStatus.OPEN_READ, true)) {// 通道 读数据
                                        closeSocket = true;
                                    }
                                }
                                if (!closeSocket && sk.isWritable()) {
                                    if (!processSocket(channel, SocketStatus.OPEN_WRITE, true)) {// 具体的 通道写操作 处理逻辑
                                        closeSocket = true;
                                    }
                                }
                                if (closeSocket) {// 解除无效通道
                                    cancelledKey(sk,SocketStatus.DISCONNECT,false);
                                }
                            } else {
                                result = false;
                            }
                        }
                    }
                } else {
                    //invalid key
                    cancelledKey(sk, SocketStatus.ERROR,false);
                }
            } catch ( CancelledKeyException ckx ) {
                cancelledKey(sk, SocketStatus.ERROR,false);
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                log.error("",t);
            }
            return result;
        }

        public SendfileState processSendfile(SelectionKey sk, KeyAttachment attachment,
                boolean calledByProcessor) {
            NioChannel sc = null;
            try {
                unreg(sk, attachment, sk.readyOps());
                SendfileData sd = attachment.getSendfileData();

                if (log.isTraceEnabled()) {
                    log.trace("Processing send file for: " + sd.fileName);
                }

                //setup the file channel
                if ( sd.fchannel == null ) {
                    File f = new File(sd.fileName);
                    @SuppressWarnings("resource") // Closed when channel is closed
                    FileInputStream fis = new FileInputStream(f);
                    sd.fchannel = fis.getChannel();
                }

                //configure output channel
                sc = attachment.getChannel();
                //ssl channel is slightly different
                WritableByteChannel wc = ((sc instanceof SecureNioChannel)?sc:sc.getIOChannel());

                //we still have data in the buffer
                if (sc.getOutboundRemaining()>0) {
                    if (sc.flushOutbound()) {
                        attachment.access();
                    }
                } else {
                    long written = sd.fchannel.transferTo(sd.pos,sd.length,wc);
                    if ( written > 0 ) {
                        sd.pos += written;
                        sd.length -= written;
                        attachment.access();
                    } else {
                        // Unusual not to be able to transfer any bytes
                        // Check the length was set correctly
                        if (sd.fchannel.size() <= sd.pos) {
                            throw new IOException("Sendfile configured to " +
                                    "send more data than was available");
                        }
                    }
                }
                if ( sd.length <= 0 && sc.getOutboundRemaining()<=0) {
                    if (log.isDebugEnabled()) {
                        log.debug("Send file complete for: "+sd.fileName);
                    }
                    attachment.setSendfileData(null);
                    try {
                        sd.fchannel.close();
                    } catch (Exception ignore) {
                    }
                    // For calls from outside the Poller, the caller is
                    // responsible for registering the socket for the
                    // appropriate event(s) if sendfile completes.
                    if (!calledByProcessor) {
                        switch (sd.keepAliveState) {
                        case NONE: {
                            if (log.isDebugEnabled()) {
                                log.debug("Send file connection is being closed");
                            }
                            cancelledKey(sk,SocketStatus.STOP,false);
                            break;
                        }
                        case PIPELINED: {
                            if (log.isDebugEnabled()) {
                                log.debug("Connection is keep alive, processing pipe-lined data");
                            }
                            if (!processSocket(sc, SocketStatus.OPEN_READ, true)) {
                                cancelledKey(sk, SocketStatus.DISCONNECT, false);
                            }
                            break;
                        }
                        case OPEN: {
                            if (log.isDebugEnabled()) {
                                log.debug("Connection is keep alive, registering back for OP_READ");
                            }
                            reg(sk, attachment, SelectionKey.OP_READ);
                            break;
                        }
                        }
                    }
                    return SendfileState.DONE;
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("OP_WRITE for sendfile: " + sd.fileName);
                    }
                    if (calledByProcessor) {
                        add(attachment.getChannel(),SelectionKey.OP_WRITE);
                    } else {
                        reg(sk,attachment,SelectionKey.OP_WRITE);
                    }
                    return SendfileState.PENDING;
                }
            }catch ( IOException x ) {
                if ( log.isDebugEnabled() ) log.debug("Unable to complete sendfile request:", x);
                if (!calledByProcessor) {
                    cancelledKey(sk,SocketStatus.ERROR,false);
                }
                return SendfileState.ERROR;
            }catch ( Throwable t ) {
                log.error("",t);
                if (!calledByProcessor) {
                    cancelledKey(sk, SocketStatus.ERROR, false);
                }
                return SendfileState.ERROR;
            }
        }
        /**该方法 防止通道对同一个事件不断select的问题*/
        protected void unreg(SelectionKey sk, KeyAttachment attachment, int readyOps) {
            //this is a must, so that we don't have multiple threads messing with the socket
            reg(sk,attachment,sk.interestOps()& (~readyOps));
        }
        /**向NioChannel注册感兴趣的事件*/
        protected void reg(SelectionKey sk, KeyAttachment attachment, int intops) {
            sk.interestOps(intops);
            attachment.interestOps(intops);
            attachment.setCometOps(intops);
        }
        /**是否超时处理*/
        protected void timeout(int keyCount, boolean hasEvents) {
            long now = System.currentTimeMillis();
            // This method is called on every loop of the Poller. Don't process
            // timeouts on every loop of the Poller since that would create too
            // much load and timeouts can afford to wait a few seconds.
            // However, do process timeouts if any of the following are true:
            // - the selector simply timed out (suggests there isn't much load)
            // - the nextExpiration time has passed
            // - the server socket is being closed
            if ((keyCount > 0 || hasEvents) && (now < nextExpiration) && !close) {
                return;
            }
            //timeout
            Set<SelectionKey> keys = selector.keys();
            int keycount = 0;
            try {
                for (Iterator<SelectionKey> iter = keys.iterator(); iter.hasNext();) {
                    SelectionKey key = iter.next();
                    keycount++;
                    try {
                        KeyAttachment ka = (KeyAttachment) key.attachment();
                        if ( ka == null ) {
                            cancelledKey(key, SocketStatus.ERROR,false); //we don't support any keys without attachments
                        } else if ( ka.getError() ) {
                            cancelledKey(key, SocketStatus.ERROR,true);//TODO this is not yet being used
                        } else if (ka.isComet() && ka.getCometNotify() ) {
                            ka.setCometNotify(false);
                            reg(key,ka,0);//avoid multiple calls, this gets reregistered after invocation
                            //if (!processSocket(ka.getChannel(), SocketStatus.OPEN_CALLBACK)) processSocket(ka.getChannel(), SocketStatus.DISCONNECT);
                            if (!processSocket(ka.getChannel(), SocketStatus.OPEN_READ, true)) processSocket(ka.getChannel(), SocketStatus.DISCONNECT, true);
                        } else if ((ka.interestOps()&SelectionKey.OP_READ) == SelectionKey.OP_READ ||
                                  (ka.interestOps()&SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE) {
                            //only timeout sockets that we are waiting for a read from
                            long delta = now - ka.getLastAccess();
                            long timeout = ka.getTimeout();
                            boolean isTimedout = timeout > 0 && delta > timeout;
                            if ( close ) {
                                key.interestOps(0);
                                ka.interestOps(0); //avoid duplicate stop calls
                                processKey(key,ka);
                            } else if (isTimedout) {
                                key.interestOps(0);
                                ka.interestOps(0); //avoid duplicate timeout calls
                                cancelledKey(key, SocketStatus.TIMEOUT,true);
                            }
                        } else if (ka.isAsync() || ka.isComet()) {
                            if (close) {
                                key.interestOps(0);
                                ka.interestOps(0); //avoid duplicate stop calls
                                processKey(key,ka);
                            } else if (!ka.isAsync() || ka.getTimeout() > 0) {
                                // Async requests with a timeout of 0 or less never timeout
                                long delta = now - ka.getLastAccess();
                                long timeout = (ka.getTimeout()==-1)?((long) socketProperties.getSoTimeout()):(ka.getTimeout());
                                boolean isTimedout = delta > timeout;
                                if (isTimedout) {
                                    // Prevent subsequent timeouts if the timeout event takes a while to process
                                    ka.access(Long.MAX_VALUE);
                                    processSocket(ka.getChannel(), SocketStatus.TIMEOUT, true);
                                }
                            }
                        }//end if
                    }catch ( CancelledKeyException ckx ) {
                        cancelledKey(key, SocketStatus.ERROR,false);
                    }
                }//for
            } catch (ConcurrentModificationException cme) {
                // See https://bz.apache.org/bugzilla/show_bug.cgi?id=57943
                log.warn(sm.getString("endpoint.nio.timeoutCme"), cme);
            }
            long prevExp = nextExpiration; //for logging purposes only
            nextExpiration = System.currentTimeMillis() +
                    socketProperties.getTimeoutInterval();
            if (log.isTraceEnabled()) {
                log.trace("timeout completed: keys processed=" + keycount +
                        "; now=" + now + "; nextExpiration=" + prevExp +
                        "; keyCount=" + keyCount + "; hasEvents=" + hasEvents +
                        "; eval=" + ((now < prevExp) && (keyCount>0 || hasEvents) && (!close) ));
            }

        }
    }

// ----------------------------------------------------- Key Attachment Class.注册到SelectionKey上的附件;即选择键(SelectionKey)中需要携带的"附件"信息;对NioChannel的包装类.(在我看来)主要提供了对 KeyAttachment对象中Poller,NioChannel等成员变量的引用的重置操作
    public static class KeyAttachment extends SocketWrapper<NioChannel> {

        public KeyAttachment(NioChannel channel) {
            super(channel);
        }
        /** 重置KeyAttachment对象中Poller,NioChannel等成员变量的引用*/
        public void reset(Poller poller, NioChannel channel, long soTimeout) {
            super.reset(channel, soTimeout);

            cometNotify = false;
            cometOps = SelectionKey.OP_READ;
            interestOps = 0;
            this.poller = poller;// 新的poller
            lastRegistered = 0;
            sendfileData = null;
            if (readLatch != null) {
                try {
                    for (int i = 0; i < (int) readLatch.getCount(); i++) {
                        readLatch.countDown();
                    }
                } catch (Exception ignore) {
                }
            }
            readLatch = null;
            sendfileData = null;
            if (writeLatch != null) {
                try {
                    for (int i = 0; i < (int) writeLatch.getCount(); i++) {
                        writeLatch.countDown();
                    }
                } catch (Exception ignore) {
                }
            }
            writeLatch = null;
            setWriteTimeout(soTimeout);
        }

        public void reset() {
            reset(null,null,-1);
        }

        public Poller getPoller() { return poller;}
        public void setPoller(Poller poller){this.poller = poller;}
        public void setCometNotify(boolean notify) { this.cometNotify = notify; }
        public boolean getCometNotify() { return cometNotify; }
        /**
         * @deprecated Unused (value is set but never read) - will be removed in
         * Tomcat 8
         */
        @Deprecated
        public void setCometOps(int ops) { this.cometOps = ops; }
        /**
         * @deprecated Unused - will be removed in Tomcat 8
         */
        @Deprecated
        public int getCometOps() { return cometOps; }
        public NioChannel getChannel() { return getSocket();}
        public void setChannel(NioChannel channel) { this.socket = channel;}
        protected Poller poller = null;
        protected int interestOps = 0;
        public int interestOps() { return interestOps;}
        public int interestOps(int ops) { this.interestOps  = ops; return ops; }
        public CountDownLatch getReadLatch() { return readLatch; }
        public CountDownLatch getWriteLatch() { return writeLatch; }
        protected CountDownLatch resetLatch(CountDownLatch latch) {
            if ( latch==null || latch.getCount() == 0 ) return null;
            else throw new IllegalStateException("Latch must be at count 0");
        }
        public void resetReadLatch() { readLatch = resetLatch(readLatch); }
        public void resetWriteLatch() { writeLatch = resetLatch(writeLatch); }

        protected CountDownLatch startLatch(CountDownLatch latch, int cnt) {
            if ( latch == null || latch.getCount() == 0 ) {
                return new CountDownLatch(cnt);
            }
            else throw new IllegalStateException("Latch must be at count 0 or null.");
        }
        public void startReadLatch(int cnt) { readLatch = startLatch(readLatch,cnt);}
        public void startWriteLatch(int cnt) { writeLatch = startLatch(writeLatch,cnt);}

        protected void awaitLatch(CountDownLatch latch, long timeout, TimeUnit unit) throws InterruptedException {
            if ( latch == null ) throw new IllegalStateException("Latch cannot be null");
            latch.await(timeout,unit);
        }
        public void awaitReadLatch(long timeout, TimeUnit unit) throws InterruptedException { awaitLatch(readLatch,timeout,unit);}
        public void awaitWriteLatch(long timeout, TimeUnit unit) throws InterruptedException { awaitLatch(writeLatch,timeout,unit);}

        /**
         * @deprecated Unused - will be removed in Tomcat 8
         */
        @Deprecated
        public long getLastRegistered() { return lastRegistered; }
        /**
         * @deprecated Unused - will be removed in Tomcat 8
         */
        @Deprecated
        public void setLastRegistered(long reg) { lastRegistered = reg; }

        public void setSendfileData(SendfileData sf) { this.sendfileData = sf;}
        public SendfileData getSendfileData() { return this.sendfileData;}

        public void setWriteTimeout(long writeTimeout) {
            this.writeTimeout = writeTimeout;
        }
        public long getWriteTimeout() {return this.writeTimeout;}

        protected boolean comet = false;
        protected int cometOps = SelectionKey.OP_READ;
        protected boolean cometNotify = false;
        protected CountDownLatch readLatch = null;
        protected CountDownLatch writeLatch = null;
        protected volatile SendfileData sendfileData = null;
        private long writeTimeout = -1;
    }

    // ------------------------------------------------ Application Buffer Handler 分配nio的buffer的读写空间
    public static class NioBufferHandler implements ApplicationBufferHandler {
        protected ByteBuffer readbuf = null;
        protected ByteBuffer writebuf = null;
        /**构造方法,为buffer分配空间*/
        public NioBufferHandler(int readsize, int writesize, boolean direct) {
            if ( direct ) {
                readbuf = ByteBuffer.allocateDirect(readsize);
                writebuf = ByteBuffer.allocateDirect(writesize);
            }else {
                readbuf = ByteBuffer.allocate(readsize);
                writebuf = ByteBuffer.allocate(writesize);
            }
        }

        @Override
        public ByteBuffer expand(ByteBuffer buffer, int remaining) {return buffer;}
        @Override
        public ByteBuffer getReadBuffer() {return readbuf;}
        @Override
        public ByteBuffer getWriteBuffer() {return writebuf;}

    }

    // ------------------------------------------------ Handler Inner Interface


    /**
     * Bare bones interface used for socket processing. Per thread data is to be
     * stored in the ThreadWithAttributes extra folders, or alternately in
     * thread local fields.
     */
    public interface Handler extends AbstractEndpoint.Handler {
        public SocketState process(SocketWrapper<NioChannel> socket,
                SocketStatus status);
        public void release(SocketWrapper<NioChannel> socket);
        public void release(SocketChannel socket);
        public SSLImplementation getSslImplementation();
    }


    // ---------------------------------------------- SocketProcessor Inner Class
    /**
     * This class is the equivalent of the Worker, but will simply use in an
     * external Executor thread pool.
     */
    protected class SocketProcessor implements Runnable {

        protected NioChannel socket = null;
        protected SocketStatus status = null;

        public SocketProcessor(NioChannel socket, SocketStatus status) {
            reset(socket,status);
        }

        public void reset(NioChannel socket, SocketStatus status) {
            this.socket = socket;
            this.status = status;
        }

        @Override
        public void run() {// 1.使用nio方式 读取套接字 并进行处理, 输出响应报文;2.连接计数器减1腾出通道;3.关闭套接字
            SelectionKey key = socket.getIOChannel().keyFor(
                    socket.getPoller().getSelector());// 从socket中获取SelectionKey
            KeyAttachment ka = null;

            if (key != null) {
                ka = (KeyAttachment)key.attachment();// 获取keyAttachment
            }

            // Upgraded connections need to allow multiple threads to access the
            // connection at the same time to enable blocking IO to be used when
            // NIO has been configured
            if (ka != null && ka.isUpgraded() &&
                    SocketStatus.OPEN_WRITE == status) {
                synchronized (ka.getWriteThreadLock()) {
                    doRun(key, ka);// socketProcessor的具体逻辑
                }
            } else {
                synchronized (socket) {
                    doRun(key, ka);// socketProcessor的具体逻辑
                }
            }
        }
        /**socketProcessor的具体逻辑.*/
        private void doRun(SelectionKey key, KeyAttachment ka) {// 1.使用nio方式 读取套接字 并进行处理, 输出响应报文;2.连接计数器减1腾出通道;3.关闭套接字
            try {
                int handshake = -1;

                try {
                    if (key != null) {
                        // For STOP there is no point trying to handshake as the
                        // Poller has been stopped.
                        if (socket.isHandshakeComplete() ||
                                status == SocketStatus.STOP) {
                            handshake = 0;
                        } else {
                            handshake = socket.handshake(
                                    key.isReadable(), key.isWritable());// SSL握手
                            // The handshake process reads/writes from/to the
                            // socket. status may therefore be OPEN_WRITE once
                            // the handshake completes. However, the handshake
                            // happens when the socket is opened so the status
                            // must always be OPEN_READ after it completes. It
                            // is OK to always set this as it is only used if
                            // the handshake completes.
                            status = SocketStatus.OPEN_READ;
                        }
                    }
                }catch ( IOException x ) {
                    handshake = -1;
                    if ( log.isDebugEnabled() ) log.debug("Error during SSL handshake",x);
                }catch ( CancelledKeyException ckx ) {
                    handshake = -1;
                }
                if ( handshake == 0 ) {
                    SocketState state = SocketState.OPEN;
                    // Process the request from this socket
                    if (status == null) {// 处理KeyAttachment(实际上就是socket)请求
                        state = handler.process(ka, SocketStatus.OPEN_READ);
                    } else {
                        state = handler.process(ka, status);
                    }// 后续根据处理返回的state结果进行后续出来
                    if (state == SocketState.CLOSED) {// 若socketState为Close状态,执行channel关闭流程
                        // Close socket and pool
                        close(ka, socket, key, SocketStatus.ERROR);
                    }
                } else if (handshake == -1 ) {
                    close(ka, socket, key, SocketStatus.DISCONNECT);
                } else {
                    ka.getPoller().add(socket, handshake);
                }
            } catch (CancelledKeyException cx) {
                socket.getPoller().cancelledKey(key, null, false);
            } catch (OutOfMemoryError oom) {
                try {
                    oomParachuteData = null;
                    log.error("", oom);
                    if (socket != null) {
                        socket.getPoller().cancelledKey(key,SocketStatus.ERROR, false);
                    }
                    releaseCaches();
                }catch ( Throwable oomt ) {
                    try {
                        System.err.println(oomParachuteMsg);
                        oomt.printStackTrace();
                    }catch (Throwable letsHopeWeDontGetHere){
                        ExceptionUtils.handleThrowable(letsHopeWeDontGetHere);
                    }
                }
            } catch (VirtualMachineError vme) {
                ExceptionUtils.handleThrowable(vme);
            }catch ( Throwable t ) {
                log.error("",t);
                if (socket != null) {
                    socket.getPoller().cancelledKey(key,SocketStatus.ERROR,false);
                }
            } finally {
                socket = null;
                status = null;
                //return to cache
                if (running && !paused) {
                    processorCache.offer(this);
                }
            }
        }
        /**关闭socket*/
        private void close(KeyAttachment ka, NioChannel socket, SelectionKey key,
                SocketStatus socketStatus) {
            try {
                if (ka != null) {
                    ka.setComet(false);
                }
                if (socket.getPoller().cancelledKey(key, socketStatus, false) != null) {// 取消注册,释放资源并关闭channel
                    // SocketWrapper (attachment) was removed from the
                    // key - recycle both. This can only happen once
                    // per attempted closure so it is used to determine
                    // whether or not to return socket and ka to
                    // their respective caches. We do NOT want to do
                    // this more than once - see BZ 57340 / 57943.
                    if (running && !paused) {
                        nioChannels.offer(socket);// 回收channel
                    }
                    if (running && !paused && ka != null) {
                        keyCache.offer(ka);// 回收key
                    }
                }
            } catch ( Exception x ) {
                log.error("",x);
            }
        }
    }

    // ----------------------------------------------- SendfileData Inner Class
    /**
     * SendfileData class.
     */
    public static class SendfileData {
        // File
        public volatile String fileName;
        public volatile FileChannel fchannel;
        public volatile long pos;
        public volatile long length;
        // KeepAlive flag
        public SendfileKeepAliveState keepAliveState = SendfileKeepAliveState.NONE;
    }
}