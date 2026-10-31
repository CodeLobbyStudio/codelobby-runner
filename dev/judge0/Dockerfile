FROM mrkushalsm/judge0@sha256:d60605ad448453ac3754629bc7121d379cf691e0c091aabbc03ff2886b2626b2 AS toolchains
USER root
COPY vendor /tmp/vendor
RUN cd /tmp/vendor && sha256sum -c checksums.txt && \
    mkdir -p /usr/local/codelobby/jdk21 && \
    tar -xzf OpenJDK21U-jdk_x64_linux_hotspot_21.0.13_9.tar.gz --strip-components=1 -C /usr/local/codelobby/jdk21 && \
    tar -xJf Python-3.12.16.tar.xz -C /tmp && \
    cd /tmp/Python-3.12.16 && \
    ./configure --prefix=/usr/local/codelobby/python3.12 --with-ensurepip=no && \
    make -j2 && make install && \
    /usr/local/codelobby/python3.12/bin/python3.12 -c 'import ssl, sqlite3, bz2, lzma, ctypes; import sys; print(sys.version)' && \
    /usr/local/codelobby/jdk21/bin/java -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xmx64m -version

FROM mrkushalsm/judge0@sha256:d60605ad448453ac3754629bc7121d379cf691e0c091aabbc03ff2886b2626b2
USER root
COPY --from=toolchains /usr/local/codelobby /usr/local/codelobby
COPY image/modern-languages.rb /api/db/languages/codelobby_modern.rb
COPY image/report-confirmed-oom.rb /api/config/initializers/codelobby_confirmed_oom.rb
COPY image/bounded-workspace.rb /api/config/initializers/codelobby_bounded_workspace.rb
COPY image/compilation-budget.rb /api/config/initializers/zz_codelobby_compilation_budget.rb
COPY image/start-without-env-log.sh image/workspace-helper.sh image/reset-compile-cgroup.sh image/java-with-budget.sh /usr/local/bin/
COPY image/isolate-with-workspace.sh /opt/codelobby-bin/isolate
RUN printf '\nrequire_relative "codelobby_modern"\n' >> /api/db/languages/active.rb && \
    chmod 0755 /usr/local/bin/start-without-env-log.sh /usr/local/bin/workspace-helper.sh \
        /usr/local/bin/reset-compile-cgroup.sh /usr/local/bin/java-with-budget.sh /opt/codelobby-bin/isolate
LABEL org.opencontainers.image.title="CodeLobby Judge0 local development" \
      org.opencontainers.image.version="1.0.0-local"
USER judge0
CMD ["/bin/bash", "/usr/local/bin/start-without-env-log.sh", "server"]
