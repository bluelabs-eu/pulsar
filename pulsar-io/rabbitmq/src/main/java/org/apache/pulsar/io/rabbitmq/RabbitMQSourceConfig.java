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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.common.base.Preconditions;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.apache.pulsar.io.core.annotations.FieldDoc;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
public class RabbitMQSourceConfig extends RabbitMQAbstractConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    @FieldDoc(
        required = true,
        defaultValue = "",
        help = "The RabbitMQ queue name from which messages should be read from or written to")
    private String queueName;

    @FieldDoc(
        required = false,
        defaultValue = "0",
        help = "Maximum number of messages that the server will deliver, 0 for unlimited")
    private int prefetchCount = 0;

    @FieldDoc(
        required = false,
        defaultValue = "false",
        help = "Set true if the settings should be applied to the entire channel rather than each consumer")
    private boolean prefetchGlobal = false;

    @FieldDoc(
            required = false,
            defaultValue = "",
            help = "Comma separated routing keys for the topic filtering"
    )
    private String routingKeys = "";

    @FieldDoc(
            required = false,
            defaultValue = "",
            help = "Exchange to bind the queue to"
    )
    private String exchange = "";

    @FieldDoc(
            required = false,
            defaultValue = "false",
            help = "Set true if the queue should be declared passively - ie to preserve durability/timeout settings")
    private boolean passive = false;

    @FieldDoc(
            required = false,
            defaultValue = "false",
            help = "Set true if the queue should be durable")
    private boolean durable = false;

    @FieldDoc(
            required = false,
            defaultValue = "false",
            help = "Set true if the queue should be exclusive")
    private boolean exclusive = false;

    @FieldDoc(
            required = false,
            defaultValue = "false",
            help = "Set true if the queue should be auto deleted")
    private boolean autoDelete = false;


    @FieldDoc(
            required = false,
            defaultValue = "false",
            help = "Set true if routing key should be parsed to produce properties and routing key"
    )
    private boolean parseRoutingKey = false;

    @FieldDoc(
            required = false,
            defaultValue = "",
            help = "Regex pattern to extract values from the RabbitMQ routing key to message properties and routing key"
    )
    private String routingKeyPattern = "";

    @FieldDoc(
            required = false,
            defaultValue = "routingKey",
            help = "Named group of the regex pattern to be used as a routing key"
    )
    private String keyGroupName = "routingKey";

    /**
     * Ideally this would be extracted directly from the routingKeyPattern, yet api to get the regex matching groups
     * is not available until JDK v20.
     */
    @FieldDoc(
            required = false,
            defaultValue = "",
            help = "Comma separated list of groups in the routingKeyPattern that should be included in the properties"
    )
    private String routingKeyGroups = "";


    @FieldDoc(
            required = false,
            defaultValue = "false",
            help = "Include connector startup time to the message properties"
    )
    private boolean includeStartupTimeInProperties = false;

    public static RabbitMQSourceConfig load(String yamlFile) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        return mapper.readValue(new File(yamlFile), RabbitMQSourceConfig.class);
    }

    public static RabbitMQSourceConfig load(Map<String, Object> map) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(mapper.writeValueAsString(map), RabbitMQSourceConfig.class);
    }

    @Override
    public void validate() {
        super.validate();
        Preconditions.checkNotNull(queueName, "queueName property not set.");
        Preconditions.checkArgument(prefetchCount >= 0, "prefetchCount must be non-negative.");

        if (!routingKeyPattern.isEmpty()) {
            try {
                Pattern.compile(routingKeyPattern);
            } catch (PatternSyntaxException exception) {
                throw new IllegalArgumentException("Regex pattern is not valid: " + routingKeyPattern, exception);
            }
        }

        if (!exchange.isEmpty()) {
            Preconditions.checkArgument(
                    routingKeys.split(",").length > 0,
                    "routing keys must be provided together with the exchange"
            );
        }
    }
}
