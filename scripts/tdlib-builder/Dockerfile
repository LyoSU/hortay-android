# Custom TDLib Android builder.
#
# Layered for incremental bumps:
#   1. base       — apt deps (rare changes)
#   2. sdk        — Android SDK + NDK (changes when ANDROID_NDK_VERSION bumps)
#   3. openssl    — OpenSSL prebuilt (changes when OPENSSL_VERSION bumps)
#   4. tdlib      — TDLib source + build (changes per TDLIB_REF — the hot path)
#
# Default platform is linux/amd64 because the pinned NDK (23.2) has no linux-aarch64
# host toolchain. Override with --build-arg BUILD_PLATFORM=... if you bump the NDK
# to a release that ships an aarch64 host (r25+).

# -----------------------------------------------------------------------------
ARG BUILD_PLATFORM=linux/amd64
FROM --platform=${BUILD_PLATFORM} ubuntu:24.04 AS base

RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -yq \
        default-jdk g++ git gperf make perl php-cli unzip wget ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /home

# -----------------------------------------------------------------------------
FROM base AS sdk

ARG ANDROID_NDK_VERSION=23.2.8568313

# Use upstream scripts at a pinned commit so this layer is reproducible.
ARG UPSTREAM_SCRIPTS_COMMIT=master
RUN wget -q "https://raw.githubusercontent.com/tdlib/td/${UPSTREAM_SCRIPTS_COMMIT}/example/android/check-environment.sh" \
    && wget -q "https://raw.githubusercontent.com/tdlib/td/${UPSTREAM_SCRIPTS_COMMIT}/example/android/fetch-sdk.sh" \
    && chmod +x check-environment.sh fetch-sdk.sh \
    && ./fetch-sdk.sh SDK "$ANDROID_NDK_VERSION"

# -----------------------------------------------------------------------------
FROM sdk AS openssl

ARG ANDROID_NDK_VERSION=23.2.8568313
ARG OPENSSL_VERSION=openssl-3.5.7
ARG UPSTREAM_SCRIPTS_COMMIT=master

RUN wget -q "https://raw.githubusercontent.com/tdlib/td/${UPSTREAM_SCRIPTS_COMMIT}/example/android/build-openssl.sh" \
    && chmod +x build-openssl.sh \
    && ./build-openssl.sh SDK "$ANDROID_NDK_VERSION" openssl "$OPENSSL_VERSION"

# -----------------------------------------------------------------------------
FROM openssl AS tdlib

ARG ANDROID_NDK_VERSION=23.2.8568313
ARG TDLIB_REF=master

# Bust the cache when upstream master moves; ignored when TDLIB_REF is a fixed tag/sha.
ADD "https://api.github.com/repos/tdlib/td/git/refs/heads/master" version.json

RUN git clone https://github.com/tdlib/td.git \
    && cd td \
    && git checkout "$TDLIB_REF"

# Replace the upstream serial-ABI build with our parallel version.
COPY build-tdlib-parallel.sh /home/td/example/android/build-tdlib-parallel.sh
RUN chmod +x /home/td/example/android/build-tdlib-parallel.sh

ARG ANDROID_STL=c++_static
RUN cd /home/td/example/android \
    && ./build-tdlib-parallel.sh \
        /home/SDK "$ANDROID_NDK_VERSION" /home/openssl "$ANDROID_STL"

# -----------------------------------------------------------------------------
# Output stage — `docker build --output <dir>` lifts these files onto the host.
FROM scratch AS export
COPY --from=tdlib /home/td/example/android/tdlib/tdlib.zip /
COPY --from=tdlib /home/td/example/android/tdlib/tdlib-debug.zip /
