# syntax=docker/dockerfile:1
# One image per web app, built from the repository root because both apps use shared/:
#   docker build -f web.Dockerfile --build-arg APP=frontend-ctl .   (administration, /ctl)
#   docker build -f web.Dockerfile --build-arg APP=frontend-cas .   (the till, /cas)
#   ... APP=frontend-buy (the shop, /buy), frontend-app (the mobile app page, /app), frontend-nav (the portal, /)
ARG APP=frontend-ctl

FROM node:24-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
COPY shared/package.json shared/
COPY frontend-ctl/package.json frontend-ctl/
COPY frontend-cas/package.json frontend-cas/
COPY frontend-buy/package.json frontend-buy/
COPY frontend-app/package.json frontend-app/
COPY frontend-nav/package.json frontend-nav/
# Retries ride out a flaky connection; the cache mount keeps what was downloaded, so a rerun
# after a dropped connection doesn't start from scratch.
RUN --mount=type=cache,target=/root/.npm \
    npm ci --no-audit --no-fund --prefer-offline \
        --fetch-retries=6 --fetch-retry-mintimeout=20000 --fetch-retry-maxtimeout=180000 --fetch-timeout=600000

FROM node:24-alpine AS build
ARG APP
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY package.json package-lock.json ./
COPY shared shared
COPY ${APP} ${APP}
# Not every app has files of its own to serve; the image copies the folder either way.
RUN mkdir -p ${APP}/public
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run build -w ${APP}

FROM node:24-alpine
ARG APP
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 PORT=3000 HOSTNAME=0.0.0.0
RUN addgroup -S pos && adduser -S pos -G pos
WORKDIR /app
COPY --from=build --chown=pos:pos /app/${APP}/.next/standalone ./
COPY --from=build --chown=pos:pos /app/${APP}/.next/static ./${APP}/.next/static
COPY --from=build --chown=pos:pos /app/${APP}/public ./${APP}/public
WORKDIR /app/${APP}
USER pos
EXPOSE 3000
# The browser reaches the API at /api on the same address: the proxy in front (Caddy, nginx, Apache)
# sends it to the backend, so the app itself needs no API address.
CMD ["node", "server.js"]
