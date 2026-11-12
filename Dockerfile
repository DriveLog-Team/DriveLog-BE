FROM ubuntu:22.04 AS speech
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ca-certificates curl cmake build-essential
RUN mkdir /src && curl -fsSL --retry 3 https://codeload.github.com/ggml-org/whisper.cpp/tar.gz/4979e04f5dcaccb36057e059bbaed8a2f5288315 | tar xz -C /src --strip-components=1 && \
    cmake -S /src -B /build -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DGGML_NATIVE=OFF -DGGML_AVX512=OFF -DWHISPER_BUILD_TESTS=OFF && \
    cmake --build /build --target whisper-cli -j2
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends python3 python3-pip ffmpeg fonts-noto-cjk libgomp1 && rm -rf /var/lib/apt/lists/*
ENV OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1 OPENVINO_TELEMETRY_OPTOUT=1
RUN python3 -m pip install --no-cache-dir openvino==2025.3.0 numpy==1.26.4
COPY media/fetch_privacy_models.py media/OPEN_MODEL_ZOO_LICENSE /opt/road-models/
RUN python3 /opt/road-models/fetch_privacy_models.py /opt/road-models
COPY --from=speech /build/bin/whisper-cli /usr/local/bin/
RUN groupadd --gid 10001 drivelog && useradd --uid 10001 --gid drivelog --no-create-home drivelog
WORKDIR /app
COPY --chown=10001:10001 target/drivelog-api.jar /app/app.jar
COPY media/*.py /app/media/
ENV PYTHONDONTWRITEBYTECODE=1
USER 10001:10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
