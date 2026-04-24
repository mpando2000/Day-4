FROM maven:3.9-eclipse-temurin-17-alpine AS build
WORKDIR /app
COPY . .
ARG MODULE
RUN mvn -pl ${MODULE} -am package -DskipTests -q

FROM eclipse-temurin:17-jre-alpine
ARG MODULE
COPY --from=build /app/${MODULE}/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
