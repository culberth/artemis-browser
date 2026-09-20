# artemis-browser as a container image.
#
# Built from an already-packaged jar rather than compiling inside the image: this project resolves
# Maven dependencies through a local Nexus configured in Maven's own conf/settings.xml, which a
# build container would not have. scripts/build-image.ps1 runs `mvn package` first and then this.
#
# A JRE, not a JDK: the app targets release 21 even though the JDK on the build machine is 26, so
# 21 is what it needs to run.
FROM eclipse-temurin:21-jre-alpine

# Runs as a non-root UID with a real home directory. The home matters because ConnectionStore falls
# back to ${user.home} when artemis.connections-file is unset — the chart sets that property
# explicitly, but an image that only behaves when its caller remembers to is a trap.
RUN addgroup -S -g 1000 artemis && adduser -S -u 1000 -G artemis -h /home/artemis artemis \
    && mkdir -p /data && chown artemis:artemis /data

WORKDIR /app
COPY --chown=artemis:artemis target/artemis-browser-*.jar /app/artemis-browser.jar

USER 1000

# Loopback only, exactly as the shipped application.properties has it. The image therefore refuses
# to serve anything it was not deliberately configured to serve: a Deployment that forgets to set a
# login and TLS fails ReachabilityGuard at startup rather than quietly exposing the broker. The
# chart supplies the rest.
EXPOSE 8443
ENTRYPOINT ["java", "-jar", "/app/artemis-browser.jar"]
