# Build environment for LineageOS 21 (Android 14) TV GSI. x86_64 userland (native on the laptop, Rosetta on the Mac).
FROM --platform=linux/amd64 ubuntu:22.04
ENV DEBIAN_FRONTEND=noninteractive LANG=C.UTF-8 LC_ALL=C.UTF-8 USE_CCACHE=0
RUN apt-get update && apt-get install -y --no-install-recommends \
      bc bison build-essential ca-certificates curl flex g++ git git-lfs gnupg gperf \
      imagemagick libelf-dev libssl-dev libxml2 libxml2-utils lz4 lzop python3 python3-protobuf \
      protobuf-compiler rsync schedtool squashfs-tools xsltproc zip unzip zlib1g-dev \
      libncurses5 fontconfig procps less vim-tiny openssh-client file \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSL https://storage.googleapis.com/git-repo-downloads/repo -o /usr/local/bin/repo \
    && chmod a+x /usr/local/bin/repo && ln -sf python3 /usr/bin/python \
    && git config --system user.name "z9x builder" && git config --system user.email "builder@localhost" \
    && git config --system color.ui false && git config --system safe.directory "*" && git lfs install --system
WORKDIR /src
