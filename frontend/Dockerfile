# syntax=docker/dockerfile:1
FROM node:24-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
# Retries ride out a flaky connection; the cache mount keeps what was downloaded, so a rerun
# after a dropped connection doesn't start from scratch.
RUN --mount=type=cache,target=/root/.npm \
    npm ci --no-audit --no-fund --prefer-offline \
        --fetch-retries=6 --fetch-retry-mintimeout=20000 --fetch-retry-maxtimeout=180000 --fetch-timeout=600000

FROM node:24-alpine AS build
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run build

FROM node:24-alpine
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 PORT=3000 HOSTNAME=0.0.0.0
RUN addgroup -S pos && adduser -S pos -G pos
COPY --from=build --chown=pos:pos /app/.next/standalone ./
COPY --from=build --chown=pos:pos /app/.next/static ./.next/static
COPY --from=build --chown=pos:pos /app/public ./public
USER pos
EXPOSE 3000
# API_URL (the Spring Boot base URL) is read at runtime by the /api proxy route.
CMD ["node", "server.js"]
