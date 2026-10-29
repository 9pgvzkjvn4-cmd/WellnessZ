# syntax=docker/dockerfile:1
# WellnessZ manager & HR workspace – web build served by unprivileged nginx.
# Build:  docker build -t wellnessz/manager-workspace:dev web/manager-workspace
# Run:    docker run --rm -p 8080:8080 --read-only --tmpfs /tmp wellnessz/manager-workspace:dev

FROM node:24.22.2-alpine3.24 AS build
WORKDIR /app
COPY package.json package-lock.json ./
# Optional corporate TLS-intercepting proxy CA: docker build --secret id=npm_ca,src=/path/ca.pem ...
RUN --mount=type=secret,id=npm_ca,required=false \
    if [ -f /run/secrets/npm_ca ]; then export NODE_EXTRA_CA_CERTS=/run/secrets/npm_ca; fi; \
    npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.31.7-alpine3.24
LABEL org.opencontainers.image.title="wellnessz-manager-workspace" \
      org.opencontainers.image.description="WellnessZ manager & HR workspace (static SPA, status-only)"
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY security-headers.conf /etc/nginx/wz-security-headers.conf
COPY --from=build /app/dist /usr/share/nginx/html
USER 101
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --retries=3 CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
