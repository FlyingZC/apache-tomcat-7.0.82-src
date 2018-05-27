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


/**使用digester创建Engine实例
 * <p><strong>RuleSet</strong> for processing the contents of a
 * Engine definition element.  This <code>RuleSet</code> does NOT include
 * any rules for nested Host elements, which should be added via instances of
 * <code>HostRuleSet</code>.</p>
 *
 * @author Craig R. McClanahan
 */
public class EngineRuleSet extends RuleSetBase {


    // ----------------------------------------------------- Instance Variables


    /**
     * The matching pattern prefix to use for recognizing our elements.
     */
    protected String prefix = null;


    // ------------------------------------------------------------ Constructor


    /**
     * Construct an instance of this <code>RuleSet</code> with the default
     * matching pattern prefix.
     */
    public EngineRuleSet() {

        this("");

    }


    /**
     * Construct an instance of this <code>RuleSet</code> with the specified
     * matching pattern prefix.
     *
     * @param prefix Prefix for matching pattern rules (including the
     *  trailing slash character)
     */
    public EngineRuleSet(String prefix) {

        super();
        this.namespaceURI = null;
        this.prefix = prefix;

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
        
        digester.addObjectCreate(prefix + "Engine",
                                 "org.apache.catalina.core.StandardEngine",
                                 "className");// 创建Engine实例
        digester.addSetProperties(prefix + "Engine");// 设置属性
        digester.addRule(prefix + "Engine",
                         new LifecycleListenerRule
                         ("org.apache.catalina.startup.EngineConfig",
                          "engineConfigClass"));// 为Engine添加一个 生命周期监听器EngineConfig.这个监听器类是在创建时默认添加的,并非由server.xml配置.该监听器用于打印Engine启动和停止的日志
        digester.addSetNext(prefix + "Engine",
                            "setContainer",
                            "org.apache.catalina.Container");// 通过setContainer()将其添加到Service实例

        //Cluster configuration start.为Engine添加集群配置
        digester.addObjectCreate(prefix + "Engine/Cluster",
                                 null, // MUST be specified in the element
                                 "className");
        digester.addSetProperties(prefix + "Engine/Cluster");
        digester.addSetNext(prefix + "Engine/Cluster",
                            "setCluster",
                            "org.apache.catalina.Cluster");
        //Cluster configuration end
        // 为Engine添加生命周期监听器.可由server.xml配置.默认未指定
        digester.addObjectCreate(prefix + "Engine/Listener",
                                 null, // MUST be specified in the element
                                 "className");
        digester.addSetProperties(prefix + "Engine/Listener");
        digester.addSetNext(prefix + "Engine/Listener",
                            "addLifecycleListener",
                            "org.apache.catalina.LifecycleListener");

        // 为Engine添加Realm安全配置
        digester.addRuleSet(new RealmRuleSet(prefix + "Engine/"));
        // 为Engine添加拦截器Value
        digester.addObjectCreate(prefix + "Engine/Valve",
                                 null, // MUST be specified in the element
                                 "className");
        digester.addSetProperties(prefix + "Engine/Valve");
        digester.addSetNext(prefix + "Engine/Valve",
                            "addValve",
                            "org.apache.catalina.Valve");

    }


}
