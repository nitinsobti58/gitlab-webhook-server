# Use OpenJDK 21 slim as base
FROM eclipse-temurin:21-jdk-jammy

# Set the working directory
WORKDIR /app

# Copy Gradle wrapper and build files first (better caching)
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .

# Make gradlew executable
RUN chmod +x ./gradlew

# Pre-fetch dependencies (so Docker doesn't rebuild this layer every time)
RUN ./gradlew dependencies --no-daemon

# Now copy the rest of the source code
COPY src src

#  Build the fat JAR using Shadow plugin
RUN ./gradlew shadowJar --no-daemon

# Expose the port Render provides (default $PORT)
EXPOSE 8080

# Start the fat JAR
CMD ["java", "-jar", "build/libs/webhook-server-all.jar"]

