# W7 最终验收前 DLQ 历史残留处置（2026-09-19 21:0x）

依据 GAP_PLAN_MASTER §4「W7 最终验收证据采集前须经 admin endpoint 人工处置/清空后再跑同一套产物」。

## 处置前状态
broker 上存在 3 个历史 DLQ topic（当日 CHAOS/宿主门未新增任何 DLQ 消息）：
- %DLQ%cg_product_shipped：3 条（maxOffset=3）
- %DLQ%cg_marketing_groupbuy：3 条
- %DLQ%cg_product_aftersale：3 条

mqadmin queryMsgByOffset 全量转存见 06a-dlq-historical-disposal.txt：
9 条均为 Reconsume Times=18 的毒丸，Born 2026-09-16~18（前序 chaos/E2E 窗口），
Keys 为测试单号/团号（260917xxxxxxx），RETRY_TOPIC 指向当日消费组，无真实业务损失。

## 处置
mqadmin deleteTopic -c DefaultCluster 三个 topic（NameServer success），
处置后 `topicList | grep ^%DLQ%` 计数=0。后续 KIND 链证据采集在空 DLQ 基线上进行。
