# Use OpenJDK 21 as the base image
FROM openjdk:21-jdk-slim

# Set the working directory
WORKDIR /app

# Copy everything from the repo to the container
COPY . .

# Make gradlew executable (important!)
RUN chmod +x ./gradlew

# Build the project
RUN ./gradlew build

# Expose the port Render will assign
EXPOSE 8080

# Start the server
CMD ["java", "-jar", "build/libs/webhook-server.jar"]
