# Production image: the React app is built and served by Spring Boot, so the
# whole game runs as ONE service at ONE address. Same origin means no CORS
# setup and the WebSocket automatically uses wss:// on an https page.

# ---- 1. Build the frontend --------------------------------------------------
FROM node:22-alpine AS web
WORKDIR /web
COPY frontend/package*.json ./
RUN npm install --no-audit --no-fund
COPY frontend/ ./
# .env.production leaves the API base empty, so the app calls its own origin.
RUN npx vite build

# ---- 2. Build the backend with the frontend inside it -----------------------
FROM maven:3.9-eclipse-temurin-21 AS api
WORKDIR /api
COPY backend/pom.xml ./
# Pre-fetch dependencies in their own layer so code changes rebuild faster.
RUN mvn -q -B dependency:go-offline || true
COPY backend/src ./src
# Spring Boot serves anything in resources/static at the site root.
COPY --from=web /web/dist ./src/main/resources/static
RUN mvn -q -B -DskipTests package

# ---- 3. Run ------------------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=api /api/target/*.jar app.jar
# The platform sets PORT; application.yml reads it (default 8080).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
