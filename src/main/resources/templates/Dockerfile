# Multi-stage build for smallest image size
FROM eclipse-temurin:21-jdk-alpine as builder

# Install Maven
RUN apk add --no-cache maven

WORKDIR /app
COPY . .

# Build the application
RUN mvn clean package -DskipTests

# Runtime stage - much smaller
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
# Copy only the built JAR from builder stage
COPY --from=builder /app/target/*.jar app.jar

# Run the application
CMD ["java", "-jar", "app.jar"]