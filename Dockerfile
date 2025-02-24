FROM docker.io/kazhar/maven:latest as build

COPY pom.xml .
COPY src/ ./src/

RUN mvn package

FROM eclipse-temurin:17-alpine

WORKDIR /jsql
COPY --from=build /project/target/jsql-1.0-SNAPSHOT-jar-with-dependencies.jar .

ENTRYPOINT ["java", "-jar", "/jsql/jsql-1.0-SNAPSHOT-jar-with-dependencies.jar"]
#CMD ["/bin/bash"]