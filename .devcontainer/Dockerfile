FROM ghcr.io/cirruslabs/android-sdk:35

USER root

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        make \
        libusb-1.0-0-dev \
        shellcheck \
    && rm -rf /var/lib/apt/lists/*
