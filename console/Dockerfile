# Build the SPA, then serve it with nginx at /console/ (nginx.conf also proxies /api to the backend).
FROM node:24.22.0-alpine3.24 AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.30.6-alpine3.24
# conf.d/*.conf is included by the image's nginx.conf; the dev compose adds dev-tools.conf next to it.
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html/console
EXPOSE 80
