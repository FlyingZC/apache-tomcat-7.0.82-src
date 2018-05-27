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


import org.apache.tomcat.util.digester.Digester;
import org.apache.tomcat.util.digester.RuleSetBase;


/**
 * <p><strong>RuleSet</strong> for processing the contents of a
 * Context definition element.</p>
 *
 * @author Craig R. McClanahan
 */
public class ContextRuleSet extends RuleSetBase {


    // ----------------------------------------------------- Instance Variables


    /**
     * The matching pattern prefix to use for recognizing our elements.
     */
    protected String prefix = null;


    /**
     * Should the context be created.
     */
    protected boolean create = true;


    // ------------------------------------------------------------ Constructor


    /**
     * Construct an instance of this <code>RuleSet</code> with the default
     * matching pattern prefix.
     */
    public ContextRuleSet() {

        this("");

    }


    /**
     * Construct an instance of this <code>RuleSet</code> with the specified
     * matching pattern prefix.
     *
     * @param prefix Prefix for matching pattern rules (including the
     *  trailing slash character)
     */
    public ContextRuleSet(String prefix) {

        super();
        this.namespaceURI = null;
        this.prefix = prefix;

    }


    /**
     * Construct an instance of this <code>RuleSet</code> with the specified
     * matching pattern prefix.
     *
     * @param prefix Prefix for matching pattern rules (including the
     *  trailing slash character)
     */
    public ContextRuleSet(String prefix, boolean create) {

        super();
        this.namespaceURI = null;
        this.prefix = prefix;
        this.create = create;

    }


    // --------------------------------------------------------- Public Methods


    /**
     * <p>Add the set of Rule instances defined in this RuleSet to the
     * specified <code>Digester</code> instance, associating them with
     * our namespace URI (if any).  This method should only be called
     * by a Digester instance.</p>
     *
     * @param digester Digester instance to which the new Rule instances
     *  should be added.
     */
    @Override
    public void addRuleInstances(Digester digester) {
        // Context配置并非来源一处,除了server.xml配置.还可以指定配置,由HostConfig自动扫描部署目录,以context.xml文件为基础进行解析创建.(eclipse启动tomcat时,context配置会配动态更新到server.xml中)
        if (create) {// context实例化.由于context来源有多处.所以根据create属性不同而有所区别.通过server.xml配置context时,create为true,此时需要创建Context实例.
            digester.addObjectCreate(prefix + "Context",
                    "org.apache.catalina.core.StandardContext", "className");
            digester.addSetProperties(prefix + "Context");
        } else {// 通过HostConfig自动创建Context时,create为false.此时仅需要解析子节点即可
            digester.addRule(prefix + "Context", new SetContextPropertiesRule());
        }

        if (create) {
            digester.addRule(prefix + "Context",
                             new LifecycleListenerRule
                                 ("org.apache.catalina.startup.ContextConfig",
                                  "configClass"));// 为context添加生命周期监听器ContextCofig,用于详细配置Context,如解析web.xml等
            digester.addSetNext(prefix + "Context",
                                "addChild",
                                "org.apache.catalina.Container");
        }
        digester.addCallMethod(prefix + "Context/InstanceListener",
                               "addInstanceListener", 0);
        // 为Context添加生命周期监听器
        digester.addObjectCreate(prefix + "Context/Listener",
                                 null, // MUST be specified in the element
                                 "className");
        digester.addSetProperties(prefix + "Context/Listener");
        digester.addSetNext(prefix + "Context/Listener",
                            "addLifecycleListener",
                            "org.apache.catalina.LifecycleListener");
        // 为Context指定类加载器.默认是WebappLoader
        digester.addObjectCreate(prefix + "Context/Loader",
                            "org.apache.catalina.loader.WebappLoader",
                            "className");
        digester.addSetProperties(prefix + "Context/Loader");
        digester.addSetNext(prefix + "Context/Loader",
                            "setLoader",
                            "org.apache.catalina.Loader");
        // 为Context添加会话管理器,默认是session.StandardManager
        digester.addObjectCreate(prefix + "Context/Manager",
                                 "org.apache.catalina.session.StandardManager",
                                 "className");
        digester.addSetProperties(prefix + "Context/Manager");
        digester.addSetNext(prefix + "Context/Manager",
                            "setManager",
                            "org.apache.catalina.Manager");
        // 为管理器指定会话存方式 和 会话标识生成器
        digester.addObjectCreate(prefix + "Context/Manager/Store",
                                 null, // MUST be specified in the element
                                 "className");
        digester.addSetProperties(prefix + "Context/Manager/Store");
        digester.addSetNext(prefix + "Context/Manager/Store",
                            "setStore",
                            "org.apache.catalina.Store");
        // 为session.StandardManager指定 会话标识生成器
        digester.addObjectCreate(prefix + "Context/Manager/SessionIdGenerator",
                                 "org.apache.catalina.util.StandardSessionIdGenerator",
                                 "className");
        digester.addSetProperties(prefix + "Context/Manager/SessionIdGenerator");
        digester.addSetNext(prefix + "Context/Manager/SessionIdGenerator",
                            "setSessionIdGenerator",
                            "org.apache.catalina.SessionIdGenerator");
        // 为Context添加初始化参数,可以在context.xml中添加初始化参数,以实现所有web应用中的复用,而不必每个web应用重复配置.
        digester.addObjectCreate(prefix + "Context/Parameter",
                                 "org.apache.catalina.deploy.ApplicationParameter");
        digester.addSetProperties(prefix + "Context/Parameter");
        digester.addSetNext(prefix + "Context/Parameter",
                            "addApplicationParameter",
                            "org.apache.catalina.deploy.ApplicationParameter");
        // 为Context添加安全配置
        digester.addRuleSet(new RealmRuleSet(prefix + "Context/"));
        // 为Context添加Web资源配置
        digester.addObjectCreate(prefix + "Context/Resources",
                                 "org.apache.naming.resources.FileDirContext",
                                 "className");
        digester.addSetProperties(prefix + "Context/Resources");
        digester.addSetNext(prefix + "Context/Resources",
                            "setResources",
                            "javax.naming.directory.DirContext");
        // 为Context添加资源链接.
        digester.addObjectCreate(prefix + "Context/ResourceLink",
                "org.apache.catalina.deploy.ContextResourceLink");
        digester.addSetProperties(prefix + "Context/ResourceLink");
        digester.addRule(prefix + "Context/ResourceLink",
                new SetNextNamingRule("addResourceLink",
                        "org.apache.catalina.deploy.ContextResourceLink"));
        // 为Context添加value
        digester.addObjectCreate(prefix + "Context/Valve",
                                 null, // MUST be specified in the element
                                 "className");
        digester.addSetProperties(prefix + "Context/Valve");
        digester.addSetNext(prefix + "Context/Valve",
                            "addValve",
                            "org.apache.catalina.Valve");
        // 为Context添加 守护资源配置
        digester.addCallMethod(prefix + "Context/WatchedResource",
                               "addWatchedResource", 0);// WatchedResource标签用于为Context添加监视资源.当这些资源发生变更时,web应用会被重新加载,默认为WEB-INF/web.xml

        digester.addCallMethod(prefix + "Context/WrapperLifecycle",
                               "addWrapperLifecycle", 0);// WrapperLifecycle为Context添加一个生命周期监听器类,此类的实例条件到Context/Wrapper上

        digester.addCallMethod(prefix + "Context/WrapperListener",
                               "addWrapperListener", 0);// WrapperListener为context添加一个容器监听器类(ContainerListener),同样添加到Wrapper上

        digester.addObjectCreate(prefix + "Context/JarScanner",
                                 "org.apache.tomcat.util.scan.StandardJarScanner",
                                 "className");// JarScanner用于为Context添加一个Jar扫描器.扫描Web应用和类加载器层级的jar包,主要用于TLD和web-fragment.xml扫描.
        digester.addSetProperties(prefix + "Context/JarScanner");
        digester.addSetNext(prefix + "Context/JarScanner",
                            "setJarScanner",
                            "org.apache.tomcat.JarScanner");

    }

}
