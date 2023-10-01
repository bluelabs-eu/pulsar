.PHONY: all build test clean

ifndef NODOCKER
SHELL := BASH_ENV=.rc /bin/bash --noprofile
endif

all: build test

build: ; mvn clean -pl pulsar-io/rabbitmq install -DskipTests

test: ; mvn -pl pulsar-io/rabbitmq test

clean:
	mvn clean
	rm -rf .m2
