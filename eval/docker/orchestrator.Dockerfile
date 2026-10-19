FROM docker:27-cli AS docker
FROM python:3.12-slim-bookworm
RUN apt-get update && apt-get install -y --no-install-recommends bash ca-certificates git \
    && rm -rf /var/lib/apt/lists/*
COPY --from=docker /usr/local/bin/docker /usr/local/bin/docker
COPY --from=docker /usr/local/libexec/docker/cli-plugins/docker-compose /usr/local/libexec/docker/cli-plugins/docker-compose
RUN pip install --no-cache-dir harbor==0.24.0
COPY eval /opt/evaluation
ENV PYTHONPATH=/opt/evaluation HARBOR_TELEMETRY=0
WORKDIR /opt/evaluation
ENTRYPOINT ["python", "/opt/evaluation/run.py"]
