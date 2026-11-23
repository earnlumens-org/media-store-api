# Stage 1: Build the application
# Pinned to the Temurin release that fixes CVE-2026-114938; bump on each patch release.
FROM eclipse-temurin:21.0.13.1_2-jdk-jammy AS build
WORKDIR /workspace

# Copy necessary files for dependency resolution
COPY build.gradle settings.gradle gradlew ./
COPY gradle gradle

# Copy source code
COPY src src

# Grant execution rights to gradlew and build the JAR
RUN chmod +x gradlew && ./gradlew bootJar -x test

# Stage 2: Create the runtime image
FROM eclipse-temurin:21.0.13.1_2-jre-jammy
VOLUME /tmp
WORKDIR /app

# Copy the built jar from the build stage
COPY --from=build /workspace/build/libs/*.jar app.jar

# Expose port 8080 (Google Cloud Run default)
EXPOSE 8080

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
