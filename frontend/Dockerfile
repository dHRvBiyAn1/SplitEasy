# syntax=docker/dockerfile:1
FROM node:26-alpine AS build
WORKDIR /build
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY . .
RUN npm run build -- --configuration production

FROM caddy:2-alpine
RUN addgroup -S -g 10001 app && adduser -S -D -H -u 10001 -G app app \
    && chown -R app:app /data /config \
    # Docker permits unprivileged low ports; strip the file capability for cap_drop=ALL.
    && setcap -r /usr/bin/caddy
COPY Caddyfile /etc/caddy/Caddyfile
COPY --from=build /build/dist/frontend/browser/ /usr/share/caddy/
USER app
EXPOSE 80 443
HEALTHCHECK --interval=10s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8081/health || exit 1
