FROM openjdk:21-jdk-slim
WORKDIR /app
COPY . .
RUN ./gradlew build
CMD ["java", "-jar", "build/libs/webhook-server.jar"]
