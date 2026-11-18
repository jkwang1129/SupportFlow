# 官网镜像从源码构建，仓库无需提交 dist。
FROM node:24-alpine AS build
WORKDIR /site
COPY index.html demo.html legal.html ./
COPY assets/ ./assets/
COPY scripts/build.mjs ./scripts/build.mjs
ARG SUPPORTFLOW_API_BASE=/api
ARG SUPPORTFLOW_APP_BASE
RUN SUPPORTFLOW_API_BASE="$SUPPORTFLOW_API_BASE" SUPPORTFLOW_APP_BASE="$SUPPORTFLOW_APP_BASE" node scripts/build.mjs

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /site/dist/ /usr/share/nginx/html/
EXPOSE 80
