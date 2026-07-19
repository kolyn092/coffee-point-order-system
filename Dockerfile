FROM eclipse-temurin:17-jdk AS build

WORKDIR /workspace

COPY gradlew build.gradle settings.gradle ./
COPY gradle gradle

RUN chmod +x gradlew && ./gradlew --no-daemon dependencies

COPY src src

RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=build /workspace/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
