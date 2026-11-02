# Cross-Toolchain: Windows-x64-DLLs (MSVC-ABI) unter Linux bauen.
# clang-cl + lld-link aus apt.llvm.org, MSVC-CRT + Windows-SDK über xwin.
FROM debian:trixie
ARG LLVM=21
ARG XWIN=0.10.0
RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates curl wget gnupg \
      cmake ninja-build git python3 xz-utils && rm -rf /var/lib/apt/lists/*
RUN wget -qO- https://apt.llvm.org/llvm-snapshot.gpg.key | gpg --dearmor -o /usr/share/keyrings/llvm.gpg \
 && echo "deb [signed-by=/usr/share/keyrings/llvm.gpg] http://apt.llvm.org/trixie/ llvm-toolchain-trixie-${LLVM} main" > /etc/apt/sources.list.d/llvm.list \
 && apt-get update && apt-get install -y --no-install-recommends clang-${LLVM} lld-${LLVM} llvm-${LLVM} \
 && rm -rf /var/lib/apt/lists/* \
 && for t in clang clang-cl lld-link llvm-lib llvm-rc llvm-mt llvm-ar llvm-objdump llvm-readobj; do \
      ln -sf /usr/lib/llvm-${LLVM}/bin/$t /usr/local/bin/$t; done
RUN curl -fsSL https://github.com/Jake-Shadle/xwin/releases/download/${XWIN}/xwin-${XWIN}-x86_64-unknown-linux-musl.tar.gz \
    | tar xz -C /tmp && mv /tmp/xwin-*/xwin /usr/local/bin/ && rm -rf /tmp/xwin-*
# MSVC-CRT + Windows-SDK (nur x86_64), Groß-/Kleinschreibung-Symlinks für Linux
RUN xwin --accept-license --arch x86_64 splat --include-debug-libs --output /xwin && rm -rf /root/.xwin-cache
WORKDIR /src
