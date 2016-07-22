/*
 * Copyright (c) 2012-2015 The original author or authors
 * ------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 * The Eclipse Public License is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * The Apache License v2.0 is available at
 * http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */
package io.moquette.server;

import com.hazelcast.config.ClasspathXmlConfig;
import com.hazelcast.config.Config;
import com.hazelcast.config.FileSystemXmlConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastInstanceNotActiveException;
import com.hazelcast.core.ITopic;
import io.moquette.BrokerConstants;
import io.moquette.interception.HazelcastInterceptHandler;
import io.moquette.interception.HazelcastMsg;
import io.moquette.interception.InterceptHandler;
import io.moquette.parser.proto.messages.PublishMessage;
import io.moquette.server.config.MemoryConfig;
import io.moquette.spi.impl.SimpleMessaging;
import io.moquette.server.config.FilesystemConfig;
import io.moquette.server.config.IConfig;
import io.moquette.server.netty.NettyAcceptor;
import io.moquette.spi.impl.ProtocolProcessor;
import io.moquette.spi.security.IAuthenticator;
import io.moquette.spi.security.IAuthorizator;
import io.moquette.spi.security.ISslContextCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * Launch a  configured version of the server.
 * @author andrea
 */
public class Server {
    
    private static final Logger LOG = LoggerFactory.getLogger(Server.class);
    
    private ServerAcceptor m_acceptor;

    private volatile boolean m_initialized;

    private ProtocolProcessor m_processor;

    private HazelcastInstance hazelcastInstance;

    private SimpleMessaging m_messaging;


    public static void main(String[] args) throws IOException {
        final Server server = new Server();
        server.startServer();
        System.out.println("Server started, version 0.9-SNAPSHOT");
        //Bind  a shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                server.stopServer();
            }
        });
    }
    
    /**
     * Starts Moquette bringing the configuration from the file 
     * located at m_config/moquette.conf
     */
    public void startServer() throws IOException {
        final IConfig config = new FilesystemConfig();
        startServer(config);
    }

    /**
     * Starts Moquette bringing the configuration from the given file
     */
    public void startServer(File configFile) throws IOException {
        LOG.info("Using m_config file: " + configFile.getAbsolutePath());
        final IConfig config = new FilesystemConfig(configFile);
        startServer(config);
    }
    
    /**
     * Starts the server with the given properties.
     * 
     * Its suggested to at least have the following properties:
     * <ul>
     *  <li>port</li>
     *  <li>password_file</li>
     * </ul>
     */
    public void startServer(Properties configProps) throws IOException {
        final IConfig config = new MemoryConfig(configProps);
        startServer(config);
    }

    /**
     * Starts Moquette bringing the configuration files from the given Config implementation.
     */
    public void startServer(IConfig config) throws IOException {
        startServer(config, null);
    }

    /**
     * Starts Moquette with config provided by an implementation of IConfig class and with the
     * set of InterceptHandler.
     * */
    public void startServer(IConfig config, List<? extends InterceptHandler> handlers) throws IOException {
        startServer(config, handlers, null, null, null);
    }

    public void startServer(IConfig config, List<? extends InterceptHandler> handlers,
                            ISslContextCreator sslCtxCreator, IAuthenticator authenticator,
                            IAuthorizator authorizator) throws IOException {
        if (handlers == null) {
            handlers = Collections.emptyList();
        }

        final String handlerProp = System.getProperty(BrokerConstants.INTERCEPT_HANDLER_PROPERTY_NAME);
        if (handlerProp != null) {
            config.setProperty(BrokerConstants.INTERCEPT_HANDLER_PROPERTY_NAME, handlerProp);
        }
        LOG.info("Persistent store file: " + config.getProperty(BrokerConstants.PERSISTENT_STORE_PROPERTY_NAME));
        if (config.getProperty(BrokerConstants.INTERCEPT_HANDLER_PROPERTY_NAME) !=null && config.getProperty(BrokerConstants.INTERCEPT_HANDLER_PROPERTY_NAME).equals(HazelcastInterceptHandler.class.getCanonicalName())) {
            if (config.getProperty(BrokerConstants.HAZELCAST_CONFIGURATION) !=null){
                Config hzconfig = null;
                if (this.getClass().getClassLoader().getResource(config.getProperty(BrokerConstants.HAZELCAST_CONFIGURATION)) != null) {
                    hzconfig = new ClasspathXmlConfig(config.getProperty(BrokerConstants.HAZELCAST_CONFIGURATION));
                } else {
                    hzconfig = new FileSystemXmlConfig(config.getProperty(BrokerConstants.HAZELCAST_CONFIGURATION));
                }
                LOG.info(String.format("starting server with hazelcast configuration file : %s",hzconfig));
                hazelcastInstance = Hazelcast.newHazelcastInstance(hzconfig);
            } else {
                LOG.info("starting server with hazelcast default file");
                hazelcastInstance = Hazelcast.newHazelcastInstance();
            }
            listenOnHazelCastMsg();
        }
        m_messaging = SimpleMessaging.getInstance();
        final ProtocolProcessor processor = m_messaging.init(config, handlers, authenticator, authorizator, this);

        if (sslCtxCreator == null) {
            sslCtxCreator = new DefaultMoquetteSslContextCreator(config);
        }

        m_acceptor = new NettyAcceptor();
        m_acceptor.initialize(processor, config, sslCtxCreator);
        m_processor = processor;
        m_initialized = true;
    }

    private void listenOnHazelCastMsg() {
        HazelcastInstance hz = getHazelcastInstance();
        ITopic<HazelcastMsg> topic = hz.getTopic("moquette");
        topic.addMessageListener(new HazelcastListener(this));
    }

    public HazelcastInstance getHazelcastInstance(){
        return hazelcastInstance;
    }

    /**
     * Use the broker to publish a message. It's intended for embedding applications.
     * It can be used only after the server is correctly started with startServer.
     *
     * @param msg the message to forward.
     * @throws IllegalStateException if the server is not yet started
     * */
    public void internalPublish(PublishMessage msg) {
        if (!m_initialized) {
            throw new IllegalStateException("Can't publish on a server is not yet started");
        }
        m_processor.internalPublish(msg);
    }
    
    public void stopServer() {
    	LOG.info("Server stopping...");
        m_acceptor.close();
        m_messaging.shutdown();
        m_initialized = false;
        if (hazelcastInstance != null) {
            try {
                hazelcastInstance.shutdown();
            } catch (HazelcastInstanceNotActiveException e) {
                LOG.info("hazelcast already shutdown");
            }
        }
        LOG.info("Server stopped");
    }

    public boolean addInterceptHandler(InterceptHandler interceptHandler) {
        if (!m_initialized) {
            throw new IllegalStateException("Can't register interceptors on a server that is not yet started");
        }
        return m_processor.addInterceptHandler(interceptHandler);
    }

    public boolean removeInterceptHandler(InterceptHandler interceptHandler) {
        if (!m_initialized) {
            throw new IllegalStateException("Can't deregister interceptors from a server that is not yet started");
        }
        return m_processor.removeInterceptHandler(interceptHandler);
    }

}
