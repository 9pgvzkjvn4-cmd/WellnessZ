# IntiMed developer targets
.PHONY: build test run demo kit content docs docker
build:   ; ./mvnw -B package -DskipTests
test:    ; ./mvnw -B verify
run:     ; INTIMED_HOME=$${INTIMED_HOME:-./intimed-data} java -jar intimed-platform/target/intimed-platform.jar
demo:    ; INTIMED_HOME=$${INTIMED_HOME:-./intimed-data} java -jar intimed-platform/target/intimed-platform.jar --spring.profiles.active=demo
kit:     ; INTIMED_HOME=$${INTIMED_HOME:-./intimed-data} ./security-test-kit/run-kit.sh
content: ; python3 tools/content/generate.py   # then re-sign drivers with your publisher key: java -jar intimed-platform/target/intimed-platform.jar sdk sign-all --key KEY intimed-platform/src/main/resources/drivers
docs:    ; python3 tools/content/gen_docs.py
docker:  ; docker buildx build --platform linux/arm64,linux/amd64 -f deploy/docker/Dockerfile -t intimed/platform:2.0.0-local .
