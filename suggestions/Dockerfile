FROM golang:1.26-alpine AS build

WORKDIR /src

COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/suggestions ./cmd/suggestions

FROM gcr.io/distroless/static-debian12:nonroot AS suggestions

COPY --from=build /out/suggestions /suggestions
USER nonroot:nonroot
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD ["/suggestions", "healthcheck"]
ENTRYPOINT ["/suggestions"]
