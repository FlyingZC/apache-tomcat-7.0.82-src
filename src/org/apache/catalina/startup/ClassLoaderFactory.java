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
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;


/** 类加载器工厂
 * <p>Utility class for building class loaders for Catalina.  The factory
 * method requires the following parameters in order to build a new class
 * loader (with suitable defaults in all cases):</p>
 * <ul>
 * <li>A set of directories containing unpacked classes (and resources)
 *     that should be included in the class loader's
 *     repositories.</li>
 * <li>A set of directories containing classes and resources in JAR files.
 *     Each readable JAR file discovered in these directories will be
 *     added to the class loader's repositories.</li>
 * <li><code>ClassLoader</code> instance that should become the parent of
 *     the new class loader.</li>
 * </ul>
 *
 * @author Craig R. McClanahan
 */

public final class ClassLoaderFactory {


    private static final Log log = LogFactory.getLog(ClassLoaderFactory.class);
    
    // --------------------------------------------------------- Public Methods


    /**
     * Create and return a new class loader, based on the configuration
     * defaults and the specified directory paths:
     *
     * @param unpacked Array of pathnames to unpacked directories that should
     *  be added to the repositories of the class loader, or <code>null</code> 
     * for no unpacked directories to be considered
     * @param packed Array of pathnames to directories containing JAR files
     *  that should be added to the repositories of the class loader, 
     * or <code>null</code> for no directories of JAR files to be considered
     * @param parent Parent class loader for the new class loader, or
     *  <code>null</code> for the system class loader.
     *
     * @exception Exception if an error occurs constructing the class loader
     */
    public static ClassLoader createClassLoader(File unpacked[],
                                                File packed[],
                                                final ClassLoader parent)
        throws Exception {

        if (log.isDebugEnabled())
            log.debug("Creating new class loader");

        // Construct the "class path" for this class loader
        Set<URL> set = new LinkedHashSet<URL>();

        // Add unpacked directories
        if (unpacked != null) {
            for (int i = 0; i < unpacked.length; i++)  {
                File file = unpacked[i];
                if (!file.exists() || !file.canRead())
                    continue;
                file = new File(file.getCanonicalPath() + File.separator);
                URL url = file.toURI().toURL();
                if (log.isDebugEnabled())
                    log.debug("  Including directory " + url);
                set.add(url);
            }
        }

        // Add packed directory JAR files
        if (packed != null) {
            for (int i = 0; i < packed.length; i++) {
                File directory = packed[i];
                if (!directory.isDirectory() || !directory.exists() ||
                    !directory.canRead())
                    continue;
                String filenames[] = directory.list();
                if (filenames == null) {
                    continue;
                }
                for (int j = 0; j < filenames.length; j++) {
                    String filename = filenames[j].toLowerCase(Locale.ENGLISH);
                    if (!filename.endsWith(".jar"))
                        continue;
                    File file = new File(directory, filenames[j]);
                    if (log.isDebugEnabled())
                        log.debug("  Including jar file " + file.getAbsolutePath());
                    URL url = file.toURI().toURL();
                    set.add(url);
                }
            }
        }

        // Construct the class loader itself
        final URL[] array = set.toArray(new URL[set.size()]);
        return AccessController.doPrivileged(
                new PrivilegedAction<URLClassLoader>() {
                    @Override
                    public URLClassLoader run() {
                        if (parent == null)
                            return new URLClassLoader(array);
                        else
                            return new URLClassLoader(array, parent);
                    }
                });
    }


    /** 根据配置默认值和指定的目录路径创建并返回一个新的类加载器
     * Create and return a new class loader, based on the configuration
     * defaults and the specified directory paths:
     *
     * @param repositories List of class directories, jar files, jar directories
     *                     or URLS that should be added to the repositories of
     *                     the class loader.
     * @param parent Parent class loader for the new class loader, or
     *  <code>null</code> for the system class loader.
     *
     * @exception Exception if an error occurs constructing the class loader
     */
    public static ClassLoader createClassLoader(List<Repository> repositories,
                                                final ClassLoader parent)
        throws Exception {

        if (log.isDebugEnabled())
            log.debug("Creating new class loader");

        // Construct the "class path" for this class loader 保存类加载的路径
        Set<URL> set = new LinkedHashSet<URL>(); // 不重复且有序

        if (repositories != null) {
            for (Repository repository : repositories)  { // 遍历 repositories
                if (repository.getType() == RepositoryType.URL) { // 若是 URL 类型
                    URL url = buildClassLoaderUrl(repository.getLocation());
                    if (log.isDebugEnabled())
                        log.debug("  Including URL " + url);
                    set.add(url);
                } else if (repository.getType() == RepositoryType.DIR) {
                    File directory = new File(repository.getLocation());
                    directory = directory.getCanonicalFile();
                    if (!validateFile(directory, RepositoryType.DIR)) {
                        continue;
                    }
                    URL url = buildClassLoaderUrl(directory);
                    if (log.isDebugEnabled())
                        log.debug("  Including directory " + url);
                    set.add(url);
                } else if (repository.getType() == RepositoryType.JAR) {
                    File file=new File(repository.getLocation());
                    file = file.getCanonicalFile();
                    if (!validateFile(file, RepositoryType.JAR)) {
                        continue;
                    }
                    URL url = buildClassLoaderUrl(file); // JAR类型资源也转换成 new URL("file:/D:/test/test.jar")
                    if (log.isDebugEnabled())
                        log.debug("  Including jar file " + url);
                    set.add(url); // 将这个URL实例添加到 URL[] 集合中
                } else if (repository.getType() == RepositoryType.GLOB) {
                    File directory=new File(repository.getLocation());
                    directory = directory.getCanonicalFile();
                    if (!validateFile(directory, RepositoryType.GLOB)) {
                        continue;
                    }
                    if (log.isDebugEnabled())
                        log.debug("  Including directory glob "
                            + directory.getAbsolutePath());
                    String filenames[] = directory.list(); // 由于 GLOB 类型是 表示整个目录下所有的 Jar 包资源,仅仅是 .jar 后缀的资源.所以要遍历文件,找出所有 Jar 包资源
                    if (filenames == null) {
                        continue;
                    }
                    for (int j = 0; j < filenames.length; j++) {
                        String filename = filenames[j].toLowerCase(Locale.ENGLISH);
                        if (!filename.endsWith(".jar"))
                            continue;
                        File file = new File(directory, filenames[j]); // 对 .jar 资源的处理
                        file = file.getCanonicalFile();
                        if (!validateFile(file, RepositoryType.JAR)) {
                            continue;
                        }
                        if (log.isDebugEnabled())
                            log.debug("    Including glob jar file "
                                + file.getAbsolutePath());
                        URL url = buildClassLoaderUrl(file);
                        set.add(url);
                    }
                }
            }
        }

        // Construct the class loader itself 创建 classLoader
        final URL[] array = set.toArray(new URL[set.size()]);
        if (log.isDebugEnabled())
            for (int i = 0; i < array.length; i++) {
                log.debug("  location " + i + " is " + array[i]);
            }

        return AccessController.doPrivileged( // 访问控制器,安全机制,获取更大的权限
                new PrivilegedAction<URLClassLoader>() {
                    @Override
                    public URLClassLoader run() { // 创建并返回新的类加载器
                        if (parent == null)
                            return new URLClassLoader(array); // ClassLoaderFactory 生成的类加载器是继承于 URLClassLoader 的,URLClassLoader 构造函数需要传入URL[]数组
                        else
                            return new URLClassLoader(array, parent);
                    }
                });
    }
    /**验证本地目录是否 exists(),isDirectory(), canRead(), 验证JAR文件是否存在 且 可读*/
    private static boolean validateFile(File file,
            RepositoryType type) throws IOException {
        if (RepositoryType.DIR == type || RepositoryType.GLOB == type) {
            if (!file.exists() || !file.isDirectory() || !file.canRead()) {
                String msg = "Problem with directory [" + file +
                        "], exists: [" + file.exists() +
                        "], isDirectory: [" + file.isDirectory() +
                        "], canRead: [" + file.canRead() + "]";

                File home = new File (Bootstrap.getCatalinaHome());
                home = home.getCanonicalFile();
                File base = new File (Bootstrap.getCatalinaBase());
                base = base.getCanonicalFile();
                File defaultValue = new File(base, "lib");

                // Existence of ${catalina.base}/lib directory is optional.
                // Hide the warning if Tomcat runs with separate catalina.home
                // and catalina.base and that directory is absent.
                if (!home.getPath().equals(base.getPath())
                        && file.getPath().equals(defaultValue.getPath())
                        && !file.exists()) {
                    log.debug(msg);
                } else {
                    log.warn(msg);
                }
                return false;
            }
        } else if (RepositoryType.JAR == type) {
            if (!file.exists() || !file.canRead()) {
                log.warn("Problem with JAR file [" + file +
                        "], exists: [" + file.exists() +
                        "], canRead: [" + file.canRead() + "]");
                return false;
            }
        }
        return true;
    }


    /*
     * These two methods would ideally be in the utility class
     * org.apache.tomcat.util.buf.UriUtil but that class is not visible until
     * after the class loaders have been constructed.
     */
    private static URL buildClassLoaderUrl(String urlString) throws MalformedURLException {
        // URLs passed to class loaders may point to directories that contain
        // JARs. If these URLs are used to construct URLs for resources in a JAR
        // the URL will be used as is. It is therefore necessary to ensure that
        // the sequence "!/" is not present in a class loader URL.
        String result = urlString.replaceAll("!/", "%21/");
        return new URL(result);// RepositoryType.URL类型的资源,直接new一个URL实例即可
    }


    private static URL buildClassLoaderUrl(File file) throws MalformedURLException {
        // Could be a directory or a file
        String fileUrlString = file.toURI().toString(); // 本地文件类型的资源,需要把 File 类型 转换为 URL 类型.由于 URL 类用于网络,带有明显的协议.所以本地文件协议定为 file.即处理为 new URL("file:/D:/test/")
        fileUrlString = fileUrlString.replaceAll("!/", "%21/");
        return new URL(fileUrlString);
    }

    /**资源类型*/
    public static enum RepositoryType {
        DIR,// 表示整个目录下的资源,包括所有Class,Jar包以及其他类型资源
        GLOB,// 表示整个目录下所有的Jar包资源,仅仅是.jar后缀的资源
        JAR,// 表示单个Jar包资源
        URL// 表示从URL上获取的Jar包资源
    }
    /**该类 表示资源*/
    public static class Repository {
        private String location;
        private RepositoryType type;
        
        public Repository(String location, RepositoryType type) {
            this.location = location;
            this.type = type;
        }
        
        public String getLocation() {
            return location;
        }
        
        public RepositoryType getType() {
            return type;
        }
    }
}
