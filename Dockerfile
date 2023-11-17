FROM maven:3.8.6-eclipse-temurin-8 AS compile

ENV SOURCE_DIR /namespace-util
COPY src $SOURCE_DIR/src
COPY pom.xml $SOURCE_DIR
WORKDIR $SOURCE_DIR
RUN mvn package -Djar.finalName=fcrepo-namespace-util

FROM eclipse-temurin:8
VOLUME /workspace
WORKDIR /namespace-util
COPY --from=compile /namespace-util/target/fcrepo-namespace-util.jar /namespace-util
