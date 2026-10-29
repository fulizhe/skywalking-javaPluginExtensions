ARG NODE_IMAGE=node:20-alpine
FROM ${NODE_IMAGE}

ARG NPM_REGISTRY=https://registry.npmmirror.com
WORKDIR /app

# 先装依赖(利用层缓存)
COPY package.json ./
RUN npm config set registry ${NPM_REGISTRY} && npm install --omit=dev

COPY index.js logReporter.js ./

ENV NODE_ENV=production
EXPOSE 8081
ENTRYPOINT ["node", "index.js"]
