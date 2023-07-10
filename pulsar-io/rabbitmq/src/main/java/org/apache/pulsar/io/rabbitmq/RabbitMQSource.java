/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.io.rabbitmq;

import com.rabbitmq.client.*;
import com.rabbitmq.client.impl.recovery.AutorecoveringChannel;
import com.rabbitmq.client.impl.recovery.AutorecoveringConnection;
import com.rabbitmq.client.impl.recovery.QueueRecoveryListener;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Data;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.PushSource;
import org.apache.pulsar.io.core.SourceContext;
import org.apache.pulsar.io.core.annotations.Connector;
import org.apache.pulsar.io.core.annotations.IOType;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A simple connector to consume messages from a RabbitMQ queue.
 */
@Connector(
    name = "rabbitmq",
    type = IOType.SOURCE,
    help = "A simple connector to move messages from a RabbitMQ queue to a Pulsar topic",
    configClass = RabbitMQSourceConfig.class)
public class RabbitMQSource extends PushSource<byte[]> {

    private static Logger logger = LoggerFactory.getLogger(RabbitMQSource.class);

    private Connection rabbitMQConnection;
    private AutorecoveringChannel rabbitMQChannel;
    private RabbitMQSourceConfig rabbitMQSourceConfig;

    private volatile String queueName;
    private Pattern routingKeyPattern;
    private String[] propertyGroups;

    private volatile long queueRecoveryId = 0l;
    private volatile long recoveryId = 0l;


    @Override
    public void open(Map<String, Object> config, SourceContext sourceContext) throws Exception {
        rabbitMQSourceConfig = RabbitMQSourceConfig.load(config);
        rabbitMQSourceConfig.validate();

        if (rabbitMQSourceConfig.isParseRoutingKey()) {
            routingKeyPattern = Pattern.compile(rabbitMQSourceConfig.getRoutingKeyPattern());
            propertyGroups = rabbitMQSourceConfig.getRoutingKeyGroups().split(",");
        }

        ConnectionFactory connectionFactory = rabbitMQSourceConfig.createConnectionFactory();
        AutorecoveringConnection arConnection = (AutorecoveringConnection)
                connectionFactory.newConnection(rabbitMQSourceConfig.getConnectionName());

        rabbitMQConnection = arConnection;

        arConnection.addQueueRecoveryListener(new QueueRecoveryListener() {
            @Override
            public void queueRecovered(String oldName, String newName) {
                queueName = newName;
                queueRecoveryId = new Random().nextLong();
            }
        });


        logger.info("A new connection to {}:{} has been opened successfully.",
                rabbitMQConnection.getAddress().getCanonicalHostName(),
                rabbitMQConnection.getPort()
        );
        rabbitMQChannel = (AutorecoveringChannel) rabbitMQConnection.createChannel();


        rabbitMQChannel.addRecoveryListener(new RecoveryListener() {
            @Override
            public void handleRecovery(Recoverable recoverable) {
                recoveryId = new Random().nextLong();
            }

            @Override
            public void handleRecoveryStarted(Recoverable recoverable) {

            }
        });
        AMQP.Queue.DeclareOk declaration;
        if (rabbitMQSourceConfig.isPassive()) {
            declaration = rabbitMQChannel.queueDeclarePassive(rabbitMQSourceConfig.getQueueName());
        } else {
            declaration = rabbitMQChannel.queueDeclare(
                    rabbitMQSourceConfig.getQueueName(),
                    rabbitMQSourceConfig.isDurable(),
                    rabbitMQSourceConfig.isExclusive(),
                    rabbitMQSourceConfig.isAutoDelete(),
                    null);
        }
        // Queue name can be generated on the rabbitmq side, so we need to check for it instead of assuming
        // that the configured one is being used
        queueName = declaration.getQueue();

        logger.info("Setting channel.basicQos({}, {}).",
                rabbitMQSourceConfig.getPrefetchCount(),
                rabbitMQSourceConfig.isPrefetchGlobal()
        );
        rabbitMQChannel.basicQos(rabbitMQSourceConfig.getPrefetchCount(), rabbitMQSourceConfig.isPrefetchGlobal());
        com.rabbitmq.client.Consumer consumer = new RabbitMQConsumer(this, rabbitMQChannel);

        if (!rabbitMQSourceConfig.getExchange().isEmpty()) {
            String[] routingKeys = rabbitMQSourceConfig.getRoutingKeys().split(",");
            String exchange = rabbitMQSourceConfig.getExchange();
            for (String routingKey : routingKeys) {
                rabbitMQChannel.queueBind(rabbitMQSourceConfig.getQueueName(), exchange, routingKey);
            }
        }

        rabbitMQChannel.basicConsume(queueName, consumer);
        logger.info("A consumer for queue {} has been successfully started.", queueName);
    }

    @Override
    public void close() throws Exception {
        rabbitMQChannel.close();
        rabbitMQConnection.close();
    }

    private class RabbitMQConsumer extends DefaultConsumer {
        private RabbitMQSource source;

        public RabbitMQConsumer(RabbitMQSource source, Channel channel) {
            super(channel);
            this.source = source;
        }

        @Override
        public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body)
                throws IOException {


            source.consume(toMessage(envelope, properties, body));
            long deliveryTag = envelope.getDeliveryTag();
            // positively acknowledge all deliveries up to this delivery tag to reduce network traffic
            // since manual message acknowledgments are turned on by default
            this.getChannel().basicAck(deliveryTag, false);
        }

        @NotNull
        private RabbitMQRecord toMessage(Envelope envelope, AMQP.BasicProperties properties, byte[] body) {
            HashMap<String, String> pulsarProperties = new HashMap<>();
            Optional<String> routingKey = Optional.ofNullable(envelope.getRoutingKey());

            for (Map.Entry<String, Object> property : properties.getHeaders().entrySet()) {
                pulsarProperties.put(property.getKey(), property.getValue().toString());
            }
            if (rabbitMQSourceConfig.isParseRoutingKey()) {
                Matcher matcher = routingKeyPattern.matcher(envelope.getRoutingKey());

                if (matcher.find()) {
                    routingKey = Optional.ofNullable(matcher.group(rabbitMQSourceConfig.getKeyGroupName()));

                    for (String group : propertyGroups) {
                        String match = matcher.group(group);
                        if (match != null) {
                            pulsarProperties.put(group, match);
                        }
                    }
                }
            }

            if (rabbitMQSourceConfig.isIncludeConsumerTagInProperties()) {
                pulsarProperties.put("consumerTag", getConsumerTag());
            }

            if (rabbitMQSourceConfig.isIncludeQueueNameInProperties()) {
                pulsarProperties.put("amqpQueueName", queueName);
            }

            pulsarProperties.put("queueRecoveryId", Long.toString(queueRecoveryId));
            pulsarProperties.put("recoveryId", Long.toString(recoveryId));

            return new RabbitMQRecord(routingKey, body, pulsarProperties);
        }
    }

    @Data
    private static class RabbitMQRecord implements Record<byte[]> {
        private final Optional<String> key;
        private final byte[] value;
        private final Map<String, String> properties;
    }
}