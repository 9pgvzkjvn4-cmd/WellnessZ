# WellnessZ development container (VS Code Dev Containers / GitHub Codespaces).
# JDK 21 (Temurin), Node 24, Docker, kubectl/Helm and Terraform come from the
# features in devcontainer.json; this image adds the OS-level tools.
# Ubuntu 24.04 ships PostgreSQL 16 client packages - the same major version as
# the local stack (postgres:16.15-bookworm).
FROM mcr.microsoft.com/devcontainers/base:ubuntu-24.04

RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      postgresql-client-16 \
      jq \
      openssl \
      shellcheck \
      unzip \
 && rm -rf /var/lib/apt/lists/*
