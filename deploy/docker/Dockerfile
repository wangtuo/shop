# 通用服务镜像（8 个模块共用，构建时以 --build-arg JAR_FILE=... 注入）
# 构建上下文固定为仓库根目录：
#   docker build -f deploy/docker/Dockerfile \
#     --build-arg JAR_FILE=shop-order/target/shop-order-service.jar \
#     -t registry.example.com/shop/shop-order-service:2.0.0 .
FROM eclipse-temurin:17-jre-jammy

ARG JAR_FILE
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=60.0 -XX:InitialRAMPercentage=40.0 -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/heapdump -Djava.security.egd=file:/dev/./urandom"

# 非 root 运行（纵深防御：容器逃逸拿到的是普通用户权限）；/app 归属该用户。
# UID/GID 必须是数字且与 K8s podSecurityContext.runAsNonRoot/fsGroup(10001) 对齐：
# 命名 USER app 会让 kubelet 报 "runAsNonRoot and image has non-numeric user,
# cannot verify user is non-root"（W7 KIND_DEPLOY 实证），useradd -r 的系统 UID
# 各基础镜像漂移，故显式钉死 10001。
RUN groupadd -r -g 10001 app && useradd -r -u 10001 -g app -d /app -s /usr/sbin/nologin app \
    && mkdir -p /app /tmp/heapdump && chown -R 10001:10001 /app /tmp/heapdump
WORKDIR /app
COPY --chown=10001:10001 ${JAR_FILE} /app/app.jar
USER 10001:10001

# 关键：必须用 exec 让 JVM 替换 sh 成为容器 1 号进程——
# 若只写 ENTRYPOINT ["sh","-c","java ..."]，SIGTERM 先到达 sh（sh 不转发信号），
# JVM 收不到 SIGTERM，Spring Boot graceful shutdown / Nacos 主动注销全部失效，
# 只能等 SIGKILL 强杀（在途请求受损、消费队列重平衡）。
STOPSIGNAL SIGTERM
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar"]
