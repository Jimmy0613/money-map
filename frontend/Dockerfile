FROM node:22.22.0-bookworm-slim AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
ARG NEXT_PUBLIC_KAKAO_MAP_APP_KEY
ENV NEXT_PUBLIC_KAKAO_MAP_APP_KEY=$NEXT_PUBLIC_KAKAO_MAP_APP_KEY
ENV BACKEND_INTERNAL_URL=http://backend:8080
RUN npm run build
FROM node:22.22.0-bookworm-slim
WORKDIR /app
ENV NODE_ENV=production
ENV HOSTNAME=0.0.0.0
COPY --from=build --chown=node:node /app/.next/standalone ./
COPY --from=build --chown=node:node /app/.next/static ./.next/static
USER node
EXPOSE 3000
CMD ["node","server.js"]
