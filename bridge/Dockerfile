FROM golang:1.26-alpine AS build

WORKDIR /src

COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/bridge ./cmd/bridge \
 && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/fakeinfra ./cmd/fakeinfra

FROM gcr.io/distroless/static-debian12:nonroot AS fakeinfra

COPY --from=build /out/fakeinfra /fakeinfra
USER nonroot:nonroot
ENTRYPOINT ["/fakeinfra"]

FROM gcr.io/distroless/static-debian12:nonroot AS bridge

COPY --from=build /out/bridge /bridge
USER nonroot:nonroot
EXPOSE 8081
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD ["/bridge", "healthcheck"]
ENTRYPOINT ["/bridge"]
