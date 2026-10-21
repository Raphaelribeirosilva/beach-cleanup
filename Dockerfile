FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN sh mvnw -B -ntp dependency:go-offline
COPY src/ src/
RUN sh mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:25-jre-noble
WORKDIR /app
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid app --no-create-home app
COPY --from=build --chown=app:app /build/target/beachcleanup-0.0.1-SNAPSHOT.jar /app/app.jar
COPY --chmod=755 deploy/render-entrypoint.sh /app/entrypoint.sh
USER 10001:10001
ENV SPRING_PROFILES_ACTIVE=prod,render
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50 -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xss512k"
EXPOSE 10000
ENTRYPOINT ["/app/entrypoint.sh"]
