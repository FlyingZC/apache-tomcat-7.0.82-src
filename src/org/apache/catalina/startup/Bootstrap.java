/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.startup;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

import org.apache.catalina.Globals;
import org.apache.catalina.security.SecurityClassLoad;
import org.apache.catalina.startup.ClassLoaderFactory.Repository;
import org.apache.catalina.startup.ClassLoaderFactory.RepositoryType;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

/** 与开始/关闭shell脚本交互的主类，因此如果要研究启动和关闭的过程，就从这个类开始看起。
 * Bootstrap loader for Catalina.  This application constructs a class loader
 * for use in loading the Catalina internal classes (by accumulating all of the
 * JAR files found in the "server" directory under "catalina.home"), and
 * starts the regular execution of the container.  The purpose of this
 * roundabout approach is to keep the Catalina internal classes (and any
 * other classes they depend on, such as an XML parser) out of the system
 * class path and therefore not visible to application level classes.
 *
 * @author Craig R. McClanahan
 * @author Remy Maucherat
 */
public final class Bootstrap {

    private static final Log log = LogFactory.getLog(Bootstrap.class);


    // ------------------------------------------------------- Static Variables


    /**
     * Daemon object used by main.此处就是 bootstrap 的实例
     */
    private static Bootstrap daemon = null;


    // -------------------------------------------------------------- Variables


    /**
     * Daemon reference.此处设置为catalina的实例
     */
    private Object catalinaDaemon = null;

    // 类加载器
    ClassLoader commonLoader = null;
    ClassLoader catalinaLoader = null;
    ClassLoader sharedLoader = null;


    // -------------------------------------------------------- Private Methods

    /** 初始化类加载器 */
    private void initClassLoaders() {
        try {
            commonLoader = createClassLoader("common", null); // 创建一个 Common 类加载器
            if( commonLoader == null ) {
                // no config file, default to this loader - we might be in a 'single' env.
                commonLoader=this.getClass().getClassLoader();
            }// 默认catalinaLoader和sharedLoader都使用父类加载器commonLoader
            catalinaLoader = createClassLoader("server", commonLoader); // 再根据 Common 类加载器创建 server 类加载器
            sharedLoader = createClassLoader("shared", commonLoader); // 再根据 Common 类加载器创建 shared 类加载器
        } catch (Throwable t) {
            handleThrowable(t);
            log.error("Class loader creation threw exception", t);
            System.exit(1);
        }
    }

    /** 若配置了路径,则创建 classLoader,否则返回父 classLoader.可用于创建 commonClassLoader,serverClassLoader,sharedClassLoader @param name 类加载器名 */
    private ClassLoader createClassLoader(String name, ClassLoader parent)
        throws Exception {
        // 创建一个类加载器的步骤: 1.把要加载的资源都添加到一个列表中; 2.确定父类加载器,若使用默认的则传 null; 3.把这些参数传入 ClassLoaderFactory 工厂类去创建 URLClassLoader 实例
        String value = CatalinaProperties.getProperty(name + ".loader"); // value 是需要加载的资源列表
        if ((value == null) || (value.equals(""))) // 如果没有配置 server.loader 或 shared.loader 等,则返回父类加载器,即此处为 commonLoader
            return parent;
        // 替换占位符
        value = replace(value);

        List<Repository> repositories = new ArrayList<Repository>();
        // D:\workspace-e3\.metadata\.plugins\org.eclipse.wst.server.core\tmp2/lib,D:\workspace-e3\.metadata\.plugins\org.eclipse.wst.server.core\tmp2/lib/*.jar,E:\Java\apache-tomcat-7.0.82-01/lib,E:\Java\apache-tomcat-7.0.82-01/lib/*.jar
        StringTokenizer tokenizer = new StringTokenizer(value, ",");
        while (tokenizer.hasMoreElements()) {
            String repository = tokenizer.nextToken().trim(); // 截取成一个个的资源列表
            if (repository.length() == 0) {
                continue;
            }

            // Check for a JAR URL repository 先判断是不是网络资源
            try {
                @SuppressWarnings("unused")
                URL url = new URL(repository); // 若不确定要加载的资源是在网络上 还是在本地,尝试使用 URL 加载.若是网络资源则不抛异常走到119行 continue 进行下一个操作.若是本地资源抛异常,接着走下面的代码
                repositories.add(
                        new Repository(repository, RepositoryType.URL));
                continue;
            } catch (MalformedURLException e) {
                // Ignore
            }

            // Local repository 本地资源
            if (repository.endsWith("*.jar")) { // 表示整个目录下所有的 Jar 包资源,仅仅是 .jar 后缀的资源(*.jar 即所有 jar)
                repository = repository.substring
                    (0, repository.length() - "*.jar".length());
                repositories.add(
                        new Repository(repository, RepositoryType.GLOB));
            } else if (repository.endsWith(".jar")) { // 单个jar
                repositories.add(
                        new Repository(repository, RepositoryType.JAR));
            } else {
                repositories.add( // dir.表示整个目录下的资源,包括所有 Class,Jar包 以及 其他类型资源
                        new Repository(repository, RepositoryType.DIR));
            }
        }

        return ClassLoaderFactory.createClassLoader(repositories, parent);
    }

    /**将${catalina.home}等替换成具体的路径
     * System property replacement in the given string.
     * 
     * @param str The original string
     * @return the modified string
     */
    protected String replace(String str) {
        // Implementation is copied from ClassLoaderLogManager.replace(),
        // but added special processing for catalina.home and catalina.base.
        String result = str;
        int pos_start = str.indexOf("${");
        if (pos_start >= 0) {
            StringBuilder builder = new StringBuilder();
            int pos_end = -1;
            while (pos_start >= 0) {
                builder.append(str, pos_end + 1, pos_start);
                pos_end = str.indexOf('}', pos_start + 2);
                if (pos_end < 0) {
                    pos_end = pos_start - 1;
                    break;
                }
                String propName = str.substring(pos_start + 2, pos_end);
                String replacement;
                if (propName.length() == 0) {
                    replacement = null;
                } else if (Globals.CATALINA_HOME_PROP.equals(propName)) {
                    replacement = getCatalinaHome();
                } else if (Globals.CATALINA_BASE_PROP.equals(propName)) {
                    replacement = getCatalinaBase();
                } else {
                    replacement = System.getProperty(propName);
                }
                if (replacement != null) {
                    builder.append(replacement);
                } else {
                    builder.append(str, pos_start, pos_end + 1);
                }
                pos_start = str.indexOf("${", pos_end + 1);
            }
            builder.append(str, pos_end + 1, str.length());
            result = builder.toString();
        }
        return result;
    }


    /** 1.创建 catalinaLoader 类加载器,并设置为 main 线程的线程上下文类加载器; 2.通过 catalinaLoader 加载类 Catalina 并创建 Catalina 对象,并保存到 bootstrap 对象
     * Initialize daemon.
     */
    public void init()
        throws Exception
    {

        // Set Catalina path
        setCatalinaHome();
        setCatalinaBase();
        // 实例化 **ClassLoaders,创建 URLClassLoader
        initClassLoaders();
        // 设置当前线程的上下文类加载器为 catalinaLoader
        Thread.currentThread().setContextClassLoader(catalinaLoader);

        SecurityClassLoad.securityClassLoad(catalinaLoader);

        // Load our startup class and call its process() method, 使用 catalinaLoader 加载 Catalina 类
        if (log.isDebugEnabled())
            log.debug("Loading startup class");
        Class<?> startupClass =
            catalinaLoader.loadClass
            ("org.apache.catalina.startup.Catalina");
        Object startupInstance = startupClass.newInstance();

        // Set the shared extensions class loader, 调用 catalina.setParentClassLoader() 方法.将 ParentClassLoader 设为 sharedLoader
        if (log.isDebugEnabled())
            log.debug("Setting startup class properties");
        String methodName = "setParentClassLoader";
        Class<?> paramTypes[] = new Class[1];
        paramTypes[0] = Class.forName("java.lang.ClassLoader");
        Object paramValues[] = new Object[1];
        paramValues[0] = sharedLoader;
        Method method =
            startupInstance.getClass().getMethod(methodName, paramTypes);
        method.invoke(startupInstance, paramValues);

        catalinaDaemon = startupInstance;

    }


    /**
     * Load daemon.调用 load 方法
     */
    private void load(String[] arguments)
        throws Exception {

        // Call the load() method
        String methodName = "load";
        Object param[];
        Class<?> paramTypes[];
        if (arguments==null || arguments.length==0) {
            paramTypes = null;
            param = null;
        } else {
            paramTypes = new Class[1];
            paramTypes[0] = arguments.getClass();
            param = new Object[1];
            param[0] = arguments;
        }
        Method method =
            catalinaDaemon.getClass().getMethod(methodName, paramTypes);
        if (log.isDebugEnabled())
            log.debug("Calling startup class " + method);
        method.invoke(catalinaDaemon, param);

    }


    /**
     * getServer() for configtest
     */
    private Object getServer() throws Exception {

        String methodName = "getServer";
        Method method =
            catalinaDaemon.getClass().getMethod(methodName);
        return method.invoke(catalinaDaemon);

    }


    // ----------------------------------------------------------- Main Program


    /**
     * Load the Catalina daemon.
     */
    public void init(String[] arguments)
        throws Exception {

        init();
        load(arguments);

    }


    /**
     * Start the Catalina daemon.
     */
    public void start()
        throws Exception {
        if( catalinaDaemon==null ) init();

        Method method = catalinaDaemon.getClass().getMethod("start", (Class [] )null);
        method.invoke(catalinaDaemon, (Object [])null);

    }


    /**
     * Stop the Catalina Daemon.
     */
    public void stop()
        throws Exception {

        Method method = catalinaDaemon.getClass().getMethod("stop", (Class [] ) null);
        method.invoke(catalinaDaemon, (Object [] ) null);

    }


    /**
     * Stop the standalone server.
     */
    public void stopServer()
        throws Exception {

        Method method =
            catalinaDaemon.getClass().getMethod("stopServer", (Class []) null);
        method.invoke(catalinaDaemon, (Object []) null);

    }


   /**
     * Stop the standalone server.
     */
    public void stopServer(String[] arguments)
        throws Exception {

        Object param[];
        Class<?> paramTypes[];
        if (arguments==null || arguments.length==0) {
            paramTypes = null;
            param = null;
        } else {
            paramTypes = new Class[1];
            paramTypes[0] = arguments.getClass();
            param = new Object[1];
            param[0] = arguments;
        }
        Method method =
            catalinaDaemon.getClass().getMethod("stopServer", paramTypes);
        method.invoke(catalinaDaemon, param);

    }


    /**
     * Set flag.
     */
    public void setAwait(boolean await)
        throws Exception {

        Class<?> paramTypes[] = new Class[1];
        paramTypes[0] = Boolean.TYPE;
        Object paramValues[] = new Object[1];
        paramValues[0] = Boolean.valueOf(await);
        Method method =
            catalinaDaemon.getClass().getMethod("setAwait", paramTypes);
        method.invoke(catalinaDaemon, paramValues);

    }

    public boolean getAwait()
        throws Exception
    {
        Class<?> paramTypes[] = new Class[0];
        Object paramValues[] = new Object[0];
        Method method =
            catalinaDaemon.getClass().getMethod("getAwait", paramTypes);
        Boolean b=(Boolean)method.invoke(catalinaDaemon, paramValues);
        return b.booleanValue();
    }


    /**
     * Destroy the Catalina Daemon.
     */
    public void destroy() {

        // FIXME

    }


    /**
     * Main method and entry point when starting Tomcat via the provided
     * scripts.
     *
     * @param args Command line arguments to be processed
     */
    public static void main(String args[]) {

        if (daemon == null) {
            // Don't set daemon until init() has completed
            Bootstrap bootstrap = new Bootstrap(); // 实例化 Bootstrap 的一个实例
            try {
                bootstrap.init(); // 设置 Catalina path, 初始化 classLoader 和 Catalina.setParentClassLoader()
            } catch (Throwable t) {
                handleThrowable(t);
                t.printStackTrace();
                return;
            }
            daemon = bootstrap;
        } else {
            // When running as a service the call to stop will be on a new
            // thread so make sure the correct class loader is used to prevent
            // a range of class not found exceptions.
            Thread.currentThread().setContextClassLoader(daemon.catalinaLoader);
        }

        try {
            String command = "start";
            if (args.length > 0) {
                command = args[args.length - 1];
            }

            if (command.equals("startd")) {
                args[args.length - 1] = "start";
                daemon.load(args);
                daemon.start();
            } else if (command.equals("stopd")) {
                args[args.length - 1] = "stop";
                daemon.stop();
            } else if (command.equals("start")) { // 处理 start 命令
                daemon.setAwait(true);
                daemon.load(args); // 加载配置资源,通过反射调用 catalina.load() 方法,创建 digester 实例,解析 conf/server.xml 文件.调用server.init()方法
                daemon.start(); // catalina.start() 运行各个组件,容器开始启动
            } else if (command.equals("stop")) {
                daemon.stopServer(args);
            } else if (command.equals("configtest")) {
                daemon.load(args);
                if (null==daemon.getServer()) {
                    System.exit(1);
                }
                System.exit(0);
            } else {
                log.warn("Bootstrap: command \"" + command + "\" does not exist.");
            }
        } catch (Throwable t) {
            // Unwrap the Exception for clearer error reporting
            if (t instanceof InvocationTargetException &&
                    t.getCause() != null) {
                t = t.getCause();
            }
            handleThrowable(t);
            t.printStackTrace();
            System.exit(1);
        }

    }

    public void setCatalinaHome(String s) {
        System.setProperty(Globals.CATALINA_HOME_PROP, s);
    }

    public void setCatalinaBase(String s) {
        System.setProperty(Globals.CATALINA_BASE_PROP, s);
    }


    /**
     * Set the <code>catalina.base</code> System property to the current
     * working directory if it has not been set.
     */
    private void setCatalinaBase() {

        if (System.getProperty(Globals.CATALINA_BASE_PROP) != null)
            return;
        if (System.getProperty(Globals.CATALINA_HOME_PROP) != null)
            System.setProperty(Globals.CATALINA_BASE_PROP,
                               System.getProperty(Globals.CATALINA_HOME_PROP));
        else
            System.setProperty(Globals.CATALINA_BASE_PROP,
                               System.getProperty("user.dir"));

    }


    /**
     * Set the <code>catalina.home</code> System property to the current
     * working directory if it has not been set.
     */
    private void setCatalinaHome() {

        if (System.getProperty(Globals.CATALINA_HOME_PROP) != null)
            return;
        File bootstrapJar =
            new File(System.getProperty("user.dir"), "bootstrap.jar");
        if (bootstrapJar.exists()) {
            try {
                System.setProperty
                    (Globals.CATALINA_HOME_PROP,
                     (new File(System.getProperty("user.dir"), ".."))
                     .getCanonicalPath());
            } catch (Exception e) {
                // Ignore
                System.setProperty(Globals.CATALINA_HOME_PROP,
                                   System.getProperty("user.dir"));
            }
        } else {
            System.setProperty(Globals.CATALINA_HOME_PROP,
                               System.getProperty("user.dir"));
        }

    }


    /**
     * Get the value of the catalina.home environment variable.
     */
    public static String getCatalinaHome() {
        return System.getProperty(Globals.CATALINA_HOME_PROP,
                                  System.getProperty("user.dir"));
    }


    /**
     * Get the value of the catalina.base environment variable.
     */
    public static String getCatalinaBase() {
        return System.getProperty(Globals.CATALINA_BASE_PROP, getCatalinaHome());
    }


    // Copied from ExceptionUtils since that class is not visible during start
    private static void handleThrowable(Throwable t) {
        if (t instanceof ThreadDeath) {
            throw (ThreadDeath) t;
        }
        if (t instanceof VirtualMachineError) {
            throw (VirtualMachineError) t;
        }
        // All other instances of Throwable will be silently swallowed
    }
}
