.PHONY: all fmt lint test build-host build-android devcycle clean

all: devcycle

fmt:
	uv run ruff format .

lint:
	uv run ruff check .
	uv run mypy src

test:
	uv run pytest
	cd android && ./gradlew testDebugUnitTest

build-host:
	uv sync

build-android:
	cd android && ./gradlew assembleDebug

devcycle: fmt lint test build-host build-android
	@echo "==> Devcycle complete!"

clean:
	rm -rf _scratch/ .ruff_cache/ .mypy_cache/
	cd android && ./gradlew clean

