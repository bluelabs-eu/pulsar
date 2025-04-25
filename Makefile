.PHONY: all build test clean

ifndef NODOCKER
SHELL := BASH_ENV=.rc /bin/bash --noprofile
endif

BUILD_TARGETS ?= pulsar-io/rabbitmq,pulsar-io/kafka-connect-adaptor,pulsar-io/debezium/core,pulsar-io/debezium/postgres

all: build test

build: ; mvn clean -pl $(BUILD_TARGETS) install -DskipTests

test: ; mvn -pl $(BUILD_TARGETS) test

clean:
	mvn clean
	rm -rf .m2
