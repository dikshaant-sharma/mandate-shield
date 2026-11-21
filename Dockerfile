# ==========================================
# Stage 1: Build the React Frontend
# ==========================================
FROM node:22-alpine AS frontend-builder
WORKDIR /app/frontend

COPY frontend/package*.json ./
RUN npm ci

COPY frontend/ ./
RUN npm run build

# ==========================================
# Stage 2: Build the Spring Boot Backend (Java 25)
# ==========================================
FROM eclipse-temurin:25-jdk-noble AS backend-builder
WORKDIR /app

# Cache Maven wrapper and dependencies
COPY mvnw mvnw.cmd pom.xml ./
COPY .mvn .mvn
RUN ./mvnw dependency:go-offline -B || true

# Copy backend source code and build jar
COPY src ./src
RUN ./mvnw clean package -DskipTests -B

# ==========================================
# Stage 3: Runtime Image
# ==========================================
FROM eclipse-temurin:25-jre-noble AS runner
WORKDIR /app

# Run as non-privileged application user
RUN useradd -m -u 1001 appuser
USER appuser

# Copy built Spring Boot fat jar
COPY --from=backend-builder /app/target/*.jar app.jar

# Expose backend port
EXPOSE 8080

ENV JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational -Xmx512m -Xms256m"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
