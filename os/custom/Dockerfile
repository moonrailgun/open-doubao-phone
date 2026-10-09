FROM debian:bookworm-slim@sha256:7c7b2c966bc9ee8cedfeef67e0e279108992c77681fa595db4a9d65c06ccc587
RUN apt-get update && apt-get install -y --no-install-recommends e2fsprogs python3 curl ca-certificates openssl \
    && apt-get clean
RUN curl -fsSL 'https://android.googlesource.com/platform/external/avb/+/refs/tags/android-16.0.0_r1/avbtool.py?format=TEXT' \
    | base64 -d > /usr/local/bin/avbtool.py \
    && echo 'e5a664a38db623da00f080219bc0ee60a640a9dc4a872803616fae4938ac749b  /usr/local/bin/avbtool.py' | sha256sum -c -
WORKDIR /src
