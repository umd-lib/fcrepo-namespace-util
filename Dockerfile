FROM eclipse-temurin:11

VOLUME /var/fcrepo
WORKDIR /opt/namespace-util
COPY target/fcrepo-namespace-util-1.0-SNAPSHOT.jar .

