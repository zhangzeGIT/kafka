/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.apache.kafka.common.network;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.Map;

import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.security.JaasUtils;
import org.apache.kafka.common.security.kerberos.KerberosShortNamer;
import org.apache.kafka.common.security.authenticator.LoginManager;
import org.apache.kafka.common.security.authenticator.SaslClientAuthenticator;
import org.apache.kafka.common.security.authenticator.SaslServerAuthenticator;
import org.apache.kafka.common.security.ssl.SslFactory;
import org.apache.kafka.common.protocol.SecurityProtocol;
import org.apache.kafka.common.KafkaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SaslChannelBuilder implements ChannelBuilder {
    private static final Logger log = LoggerFactory.getLogger(SaslChannelBuilder.class);

    // 使用的安全协议，也就是security.protocol配置项的值
    private final SecurityProtocol securityProtocol;
    // 使用的SASL机制，也就是sasl.mechanism配置项的值，这里值为PLAIN
    private final String clientSaslMechanism;
    // 标识当前是客户端还是服务端，对应的值分别是Mode.CLIENT Mode.SERVER
    private final Mode mode;
    // 枚举类型
    private final LoginType loginType;
    // 是否发送握手消息
    private final boolean handshakeRequestEnable;

    // 用于封装LoginContext的LogManager对象
    private LoginManager loginManager;
    private SslFactory sslFactory;
    // config配置信息
    private Map<String, ?> configs;
    private KerberosShortNamer kerberosShortNamer;

    public SaslChannelBuilder(Mode mode, LoginType loginType, SecurityProtocol securityProtocol, String clientSaslMechanism, boolean handshakeRequestEnable) {
        this.mode = mode;
        this.loginType = loginType;
        this.securityProtocol = securityProtocol;
        this.handshakeRequestEnable = handshakeRequestEnable;
        this.clientSaslMechanism = clientSaslMechanism;
    }

    // 核心操作就是创建LoginManager对象
    // 在LoginManager的构造方法中会创建Login对象并调用其login放方法
    public void configure(Map<String, ?> configs) throws KafkaException {
        try {
            this.configs = configs;
            boolean hasKerberos;
            if (mode == Mode.SERVER) {
                // SASL/Kerberos的相关处理
                List<String> enabledMechanisms = (List<String>) this.configs.get(SaslConfigs.SASL_ENABLED_MECHANISMS);
                hasKerberos = enabledMechanisms == null || enabledMechanisms.contains(SaslConfigs.GSSAPI_MECHANISM);
            } else {
                hasKerberos = clientSaslMechanism.equals(SaslConfigs.GSSAPI_MECHANISM);
            }

            if (hasKerberos) {
                String defaultRealm;
                try {
                    defaultRealm = JaasUtils.defaultKerberosRealm();
                } catch (Exception ke) {
                    defaultRealm = "";
                }
                @SuppressWarnings("unchecked")
                List<String> principalToLocalRules = (List<String>) configs.get(SaslConfigs.SASL_KERBEROS_PRINCIPAL_TO_LOCAL_RULES);
                if (principalToLocalRules != null)
                    kerberosShortNamer = KerberosShortNamer.fromUnparsedRules(defaultRealm, principalToLocalRules);
            }
            // 创建LoginManager对象，其中会创建DefaultLogin对象并调用其login方法
            // 并最终调用LoginContext.login方法
            this.loginManager = LoginManager.acquireLoginManager(loginType, hasKerberos, configs);

            if (this.securityProtocol == SecurityProtocol.SASL_SSL) {
                // Disable SSL client authentication as we are using SASL authentication
                // SASL/SSL的相关处理
                this.sslFactory = new SslFactory(mode, "none");
                this.sslFactory.configure(configs);
            }
        } catch (Exception e) {
            throw new KafkaException(e);
        }
    }

    // 负责创建KafkaChannel对象
    // SaslServerAuthenticator对象是完成身份认证的关键
    public KafkaChannel buildChannel(String id, SelectionKey key, int maxReceiveSize) throws KafkaException {
        try {
            SocketChannel socketChannel = (SocketChannel) key.channel();
            // 创建PlainTransportLayer对象，表示底层连接，其中封装了SocketChannel和SelectionKey
            TransportLayer transportLayer = buildTransportLayer(id, key, socketChannel);
            Authenticator authenticator;
            // 创建SaslServerAuthenticator对象，这是完成认证操作的关键
            if (mode == Mode.SERVER)
                authenticator = new SaslServerAuthenticator(id, loginManager.subject(), kerberosShortNamer,
                        socketChannel.socket().getLocalAddress().getHostName(), maxReceiveSize);
            else
                // 客户端创建的是SaslClientAuthenticator对象
                authenticator = new SaslClientAuthenticator(id, loginManager.subject(), loginManager.serviceName(),
                        socketChannel.socket().getInetAddress().getHostName(), clientSaslMechanism, handshakeRequestEnable);
            // Both authenticators don't use `PrincipalBuilder`, so we pass `null` for now. Reconsider if this changes.
            // 通过configure方法将TransportLayer最为参数传递进去
            // 在SaslServerAuthenticator中会与服务端进行通讯，完成身份认证
            // 没有身份认证使用DefaultAuthenticator.authenticate方法为空实现
            authenticator.configure(transportLayer, null, this.configs);
            return new KafkaChannel(id, transportLayer, authenticator, maxReceiveSize);
        } catch (Exception e) {
            log.info("Failed to create channel due to ", e);
            throw new KafkaException(e);
        }
    }

    public void close()  {
        if (this.loginManager != null)
            this.loginManager.release();
    }

    protected TransportLayer buildTransportLayer(String id, SelectionKey key, SocketChannel socketChannel) throws IOException {
        if (this.securityProtocol == SecurityProtocol.SASL_SSL) {
            return SslTransportLayer.create(id, key,
                sslFactory.createSslEngine(socketChannel.socket().getInetAddress().getHostName(), socketChannel.socket().getPort()));
        } else {
            return new PlaintextTransportLayer(key);
        }
    }

}
