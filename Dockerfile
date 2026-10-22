# syntax=docker/dockerfile:1
ARG MINECRAFT_IMAGE=itzg/minecraft-server:java25@sha256:3168dfa30c35fa1f99b8edde120f934a03ee780fcf905f450a1dbd9551d719f5
FROM node:24-alpine@sha256:44fce696f38ba8c987c4db89954051000bbd05ffd8d8659ba15ed4a8602e937f AS web
WORKDIR /web
COPY web/package*.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

FROM python:3.13-alpine@sha256:2d9aefe2fef018a7eb2c13064c89c71929800fd2e5dccdbf52ea5da5bb8d929a AS pack
WORKDIR /build
COPY scripts/build_pack.py scripts/build_pack.py
COPY resource-pack/pack resource-pack/pack
RUN python3 scripts/build_pack.py /out
FROM golang:1.26-alpine@sha256:3082400e369fa24d5fc60bca20edab3f6d604e0c5a690ec66b295eff4dd87ade AS go-base
WORKDIR /src
COPY go.mod go.sum ./
RUN --mount=type=cache,target=/go/pkg/mod go mod download
RUN mkdir -p /go/pkg/mod && chmod 1777 /go/pkg/mod
FROM go-base AS dev
CMD ["go", "run", "./cmd/panel"]
FROM go-base AS build
COPY cmd ./cmd
COPY internal ./internal
COPY --from=web /web/dist ./internal/panel/static
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build CGO_ENABLED=0 go build -trimpath -o /out/panel ./cmd/panel && CGO_ENABLED=0 go build -trimpath -o /out/controller ./cmd/controller && CGO_ENABLED=0 go build -trimpath -o /out/network ./cmd/network

FROM alpine:3.22@sha256:5291449c3df73caf6ed85e649dec1b9e818b39a5d8c871e97afc13e9cd5e8fa8 AS runtime
RUN apk add --no-cache ca-certificates && addgroup -g 1000 app && adduser -D -u 1000 -G app app
USER 1000:1000
WORKDIR /app
FROM runtime AS panel
COPY --from=build /out/panel /app/panel
COPY --from=pack /out/vampyuyu-menus.zip /app/vampyuyu-menus.zip
ENV MENU_PACK_FILE=/app/vampyuyu-menus.zip
EXPOSE 8080
ENTRYPOINT ["/app/panel"]
FROM runtime AS controller
COPY --from=build /out/controller /app/controller
ENTRYPOINT ["/app/controller"]
FROM runtime AS tools
COPY --from=build /out/network /app/network
ENTRYPOINT ["/app/network"]

FROM maven:3-eclipse-temurin-25@sha256:9b5f82b7166db6112eb123ba310a9e010ec3fe95022ac0728f491121563dfbab AS lobby-build
WORKDIR /build
COPY lobby/pom.xml ./
COPY lobby/src ./src
RUN --mount=type=cache,target=/root/.m2 mvn --batch-mode package
FROM maven:3-eclipse-temurin-25@sha256:9b5f82b7166db6112eb123ba310a9e010ec3fe95022ac0728f491121563dfbab AS network-build
WORKDIR /build
COPY network-plugin/pom.xml ./
COPY network-plugin/src ./src
RUN --mount=type=cache,target=/root/.m2 mvn --batch-mode package
FROM maven:3-eclipse-temurin-25@sha256:9b5f82b7166db6112eb123ba310a9e010ec3fe95022ac0728f491121563dfbab AS arena-build
WORKDIR /build
COPY arena-plugin/pom.xml ./
COPY arena-plugin/src ./src
RUN --mount=type=cache,target=/root/.m2 mvn --batch-mode package
FROM gradle:jdk25@sha256:ca424744a6a45a575f333edea680696d980999c461be38b1574a4c70d5455f24 AS claims-build
WORKDIR /build
COPY claims-plugin/ ./
RUN --mount=type=cache,target=/home/gradle/.gradle gradle shadowJar --no-daemon --console=plain
FROM ${MINECRAFT_IMAGE} AS server
COPY --from=claims-build /build/build/libs/SimpleClaimSystem-1.13.1.jar /opt/claims/SimpleClaimSystem.jar
COPY --from=pack /out/vampyuyu-menus.sha1 /opt/vampyuyu-menus.sha1
COPY --from=network-build /build/target/network-display.jar /plugins/network-display.jar
COPY --from=arena-build /build/target/nexus-arena-engine.jar /plugins/nexus-arena-engine.jar
FROM server AS lobby
COPY --from=lobby-build /build/target/lobby-selector.jar /plugins/lobby-selector.jar

COPY infra/lobby-palace /opt/vampyuyu-lobby-palace
