FROM maven:3.8.6-eclipse-temurin-11 AS compile

ENV SOURCE_DIR /namespace-util
COPY src $SOURCE_DIR/src
COPY pom.xml $SOURCE_DIR
WORKDIR $SOURCE_DIR
RUN mvn package -Djar.finalName=fcrepo-namespace-util

FROM eclipse-temurin:11
VOLUME /var/namespace-util/workspace
WORKDIR /namespace-util
COPY --from=compile /namespace-util/target/fcrepo-namespace-util.jar /namespace-util
