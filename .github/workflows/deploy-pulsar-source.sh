#!/bin/bash
set -euxo pipefail

PULSAR_VERSION=3.0.4
PULSAR_DESTINATION_TOPIC="persistent://${PULSAR_TENANT}/offer/betradar-feed"

ls -lh ${HOME}/${PULSAR_OAUTH_KEY_FILE}

function pulsar-admin() {
    PULSAR_VERSION=${PULSAR_VERSION:-3.0.4}
    DOCKER_IMAGE=ghcr.io/bluelabs-eu/pulsar-admin:${PULSAR_VERSION}
    DOCKER_RUN_OPTS="--rm -i --network host -v /etc/passwd:/etc/passwd:ro -v /etc/group:/etc/group:ro -u $(id -u):$(id -g) -v ${HOME}/${PULSAR_OAUTH_KEY_FILE}:${HOME}/${PULSAR_OAUTH_KEY_FILE} -v ${HOME}/scoreboard-pulsar-function.jar:${HOME}/scoreboard-pulsar-function.jar"
    docker run ${DOCKER_RUN_OPTS} ${DOCKER_IMAGE} "$@"
}

case ${PULSAR_ADMIN_ACTION} in
    get)
        pulsar-admin \
            --admin-url ${PULSAR_ADMIN_SERVICE_URL} \
            --auth-plugin org.apache.pulsar.client.impl.auth.oauth2.AuthenticationOAuth2 \
            --auth-params "{\"privateKey\":\"${HOME}/${PULSAR_OAUTH_KEY_FILE}\", \"issuerUrl\":\"${PULSAR_ISSUER_URL}\", \"audience\":\"urn:sn:pulsar:${PULSAR_ORG}:${PULSAR_INSTANCE}\"}" \
            source ${PULSAR_ADMIN_ACTION} \
            --tenant ${PULSAR_TENANT} \
            --namespace ${PULSAR_NAMESPACE} \
            --name ${PULSAR_SOURCE_NAME}-${BL_OPERATOR}
        ;;
    delete)
        pulsar-admin \
            --admin-url ${PULSAR_ADMIN_SERVICE_URL} \
            --auth-plugin org.apache.pulsar.client.impl.auth.oauth2.AuthenticationOAuth2 \
            --auth-params "{\"privateKey\":\"${HOME}/${PULSAR_OAUTH_KEY_FILE}\", \"issuerUrl\":\"${PULSAR_ISSUER_URL}\", \"audience\":\"urn:sn:pulsar:${PULSAR_ORG}:${PULSAR_INSTANCE}\"}" \
            source ${PULSAR_ADMIN_ACTION} \
            --tenant ${PULSAR_TENANT} \
            --namespace ${PULSAR_NAMESPACE} \
            --name ${PULSAR_SOURCE_NAME}-${BL_OPERATOR}
        ;;
    *)
        # deploy function
        pulsar-admin \
            --admin-url ${PULSAR_ADMIN_SERVICE_URL} \
            --auth-plugin org.apache.pulsar.client.impl.auth.oauth2.AuthenticationOAuth2 \
            --auth-params "{\"privateKey\":\"${HOME}/${PULSAR_OAUTH_KEY_FILE}\", \"issuerUrl\":\"${PULSAR_ISSUER_URL}\", \"audience\":\"urn:sn:pulsar:${PULSAR_ORG}:${PULSAR_INSTANCE}\"}" \
            source ${PULSAR_ADMIN_ACTION} \
            --tenant ${PULSAR_TENANT} \
            --namespace ${PULSAR_NAMESPACE} \
            --classname "org.apache.pulsar.io.rabbitmq.RabbitMQSource" \
            --archive ${HOME}/pulsar-io-rabbitmq-3.3.3.nar \
            --name ${PULSAR_SOURCE_NAME}-${BL_OPERATOR}
            --parallelism ${PULSAR_SOURCE_PARALLELISM} \
            --destination-topic-name ${PULSAR_DESTINATION_TOPIC}
            --source-config "{\"configs\":{\"host\":\"${BETRADAR_AMQP_HOST}\",\"port\":\"5671\",\"virtualHost\":\"${BETRADAR_AMQP_VIRTUAL_HOST}\",\"username\":\"${BETRADAR_AMQP_USERNAME}\",\"password\":\"\",\"queueName\":\"\",\"connectionName\":\"${BETRADAR_AMQP_CONNECTION_NAME}\",\"ssl\":\"true\",\"requestedChannelMax\":\"0\",\"requestedFrameMax\":\"0\",\"connectionTimeout\":\"60000\",\"handshakeTimeout\":\"10000\",\"requestedHeartbeat\":\"60\",\"prefetchCount\":\"0\",\"prefetchGlobal\":\"false\",\"durable\":\"false\",\"exclusive\":\"true\",\"autoDelete\":\"true\",\"exchangeName\":\"unifiedfeed\"}}"
        ;;
esac
