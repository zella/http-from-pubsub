FROM openjdk:11

COPY assembly.jar /assembly.jar

ENTRYPOINT java -jar /assembly.jar