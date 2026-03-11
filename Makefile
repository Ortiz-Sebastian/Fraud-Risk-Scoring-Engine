.PHONY: build test clean infra-up infra-down infra-reset run-producer run-producer-normal run-producer-attack run-producer-burst run-engine run-api

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
	./gradlew :producer:run --args="--mode normal --rate 100"

run-producer-normal:
	./gradlew :producer:run --args="--mode normal --rate $(or $(RATE),200)"

run-producer-attack:
	./gradlew :producer:run --args="--mode attack --rate $(or $(RATE),500)"

run-producer-burst:
	./gradlew :producer:run --args="--mode normal --rate 2000 --duration 30"

run-engine:
	./gradlew :risk-engine:run

run-api:
	./gradlew :api:bootRun
