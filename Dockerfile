# Docker 镜像构建
FROM maven:3.8.1-jdk-8-slim as builder

# SPJ checker 运行时依赖 g++（首次使用会编译 checker.cpp）
RUN apt-get update \
    && apt-get install -y --no-install-recommends g++ \
    && rm -rf /var/lib/apt/lists/*

# Copy local code to the container image.
WORKDIR /app
COPY pom.xml .
COPY src ./src
COPY scripts ./scripts

# Build a release artifact.
RUN mvn package -DskipTests

# Run the web service on container startup.
CMD ["java","-jar","/app/target/yuoj-backend-0.0.1-SNAPSHOT.jar"]
