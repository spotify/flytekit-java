FROM gcr.io/distroless/java:8

ARG FLYTE_INTERNAL_IMAGE

COPY jflyte/target/lib /jflyte/

# plugins
COPY jflyte-aws/target/lib /jflyte/modules/jflyte-aws
COPY jflyte-google-cloud/target/lib /jflyte/modules/jflyte-google-cloud

ENV FLYTE_INTERNAL_MODULE_DIR "/jflyte/modules"
ENV FLYTE_INTERNAL_MAIN_MODULE=java-tasks

ENV FLYTE_PLATFORM_URL "CHANGEME"
ENV FLYTE_STAGING_LOCATION "CHANGEME"
ENV FLYTE_PLATFORM_INSECURE "False"

ENTRYPOINT ["java", "-Dorg.slf4j.simpleLogger.defaultLogLevel=INFO", "-Dorg.slf4j.simpleLogger.log.org.flyte=DEBUG", "-cp", "/jflyte/*", "org.flyte.jflyte.Main"]
