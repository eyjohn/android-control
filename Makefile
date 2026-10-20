.PHONY: all fmt lint test build-host build-android install install-android devcycle clean

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

install:
	uv tool install --force .

install-android:
	adb install -r android/app/build/outputs/apk/debug/app-debug.apk

devcycle: fmt lint test build-host build-android
	@echo "==> Devcycle complete!"

clean:
	rm -rf _scratch/ .ruff_cache/ .mypy_cache/
	cd android && ./gradlew clean

