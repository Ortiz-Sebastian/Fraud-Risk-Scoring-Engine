.PHONY: install test lint format infra-up infra-down clean

install:
	pip install -r requirements.txt

test:
	pytest

test-cov:
	pytest --cov=src --cov-report=term-missing

lint:
	ruff check src/ tests/

format:
	black src/ tests/
	ruff check --fix src/ tests/

infra-up:
	docker compose up -d

infra-down:
	docker compose down

infra-reset:
	docker compose down -v
	docker compose up -d

clean:
	find . -type d -name __pycache__ -exec rm -rf {} +
	find . -type d -name .pytest_cache -exec rm -rf {} +
	rm -rf .coverage htmlcov/
