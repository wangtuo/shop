brokerClusterName = DefaultCluster
brokerName = broker-a
brokerId = 0
deleteWhen = 04
fileReservedTime = 48
brokerRole = ASYNC_MASTER
flushDiskType = ASYNC_FLUSH
# 本地开发中间件专用：commitlog 过期清理阈值（百分比，源码硬上限 95）。
# 注意写拒入闸门 diskSpaceWarningLevelRatio 在 RocketMQ 5.3.1 源码里【硬编码上限 0.90】
# （DefaultMessageStore$CleanCommitLogService，broker.conf 与 -D sysprop 都无法抬高），
# 且 RunningFlags 一旦标记 disk full，必须回落到 0.85 以下才恢复（迟滞）。
# 本地 macOS Docker Desktop VM 盘长期贴近 90%，为避免 commitlog 滚动时按 1GB
# 预分配新文件瞬间打穿水位，关闭预分配/预热、过期时间收到 6 小时。
# W7 已在 final-acceptance MIDDLEWARE 增加磁盘水位检查与安全回收指引。
# 生产使用云厂商托管 MQ（K8S §A 残留），由托管面扩容与磁盘策略负责。
diskMaxUsedSpaceRatio = 95
preAllocateMappedFileEnable = false
warmMapedFileEnable = false
fileReservedTime = 6
namesrvAddr = rmq-namesrv:9876
# 开发环境自动建 Topic/Group；生产必须关闭并由运维预建
autoCreateTopicEnable = true
autoCreateSubscriptionGroup = true
# 对外通告 IP：宿主进程 / 兄弟容器 / kind(K8s) Pod 三平面均经此地址回连。
# 该值必须是宿主机发布端口（18081、10911）所在、且容器侧可路由到宿主机的 IP（本机 LAN IP）。
# 由 docker-compose entrypoint 用环境变量 SHOP_BROKER_IP 替换占位符，默认值见 compose。
brokerIP1 = @BROKER_IP@
