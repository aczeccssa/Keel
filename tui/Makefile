.PHONY: build run clean

BIN = target/release/keel-sample-launcher

build:
	cargo build --release

run: build
	./$(BIN)

clean:
	cargo clean
