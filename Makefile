.PHONY: build test clean infra-up infra-down infra-reset run-producer run-engine run-api

build:
	./gradlew build

test:
	./gradlew test

clean:
	./gradlew clean

infra-up:
	docker compose up -d

infra-down:
	docker compose down

infra-reset:
	docker compose down -v
	docker compose up -d

run-producer:
	./gradlew :producer:run

run-engine:
	./gradlew :risk-engine:run

run-api:
	./gradlew :api:bootRun
