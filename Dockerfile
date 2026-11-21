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

# Tune JVM for 512MB container constraint:
# - SerialGC has minimal GC runtime data structure overhead compared to G1 or ZGC
# - Limit heap to 128MB min / 256MB max
# - Constrain Metaspace, Code Cache and Thread Stack to keep total process footprint well below 512MB
ENV PORT=8080 \
    JAVA_OPTS="-XX:+UseSerialGC -Xms128m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=32m -Xss512k -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dserver.port=${PORT} -jar app.jar"]
