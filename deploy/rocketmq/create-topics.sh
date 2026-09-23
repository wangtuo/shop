#!/usr/bin/env bash
# 预建全部业务 Topic（生产环境应关闭 autoCreateTopicEnable，Topic 由运维预建）。
# RocketMQ 5.x gRPC PushConsumer 订阅不会触发自动建 Topic，故应用启动前必须先执行本脚本。
# 幂等：updateTopic 重复执行无副作用。
set -uo pipefail
BROKER_CONTAINER=${BROKER_CONTAINER:-shop-rmq-broker}
NAMESRV=${NAMESRV:-rmq-namesrv:9876}
BROKER_ADDR=${BROKER_ADDR:-rmq-broker:10911}

TOPICS=(
  shop_order_created shop_order_cancelled shop_order_paid shop_order_shipped
  shop_order_confirmed shop_order_completed
  shop_order_pay_timeout shop_order_auto_confirm shop_order_aftersale_window
  shop_pay_result shop_refund_success shop_stock_warning
  shop_clearing_register shop_clearing_settle shop_clearing_reverse
  shop_aftersale_changed shop_aftersale_timeout
  shop_seckill_event shop_groupbuy_event shop_presale_event shop_points_changed
  shop_user_registered shop_comment_created
  shop_withdraw_result shop_deposit_alert
  # 退款冲正瀑布穿仓事件（ClearingReverseService 发出，settlement 侧追缴消费者订阅）。
  # 曾漏建：生产关闭 autoCreateTopic 后该资金事件必然投递失败，见 AUDIT_MQ_CONSISTENCY P0-1。
  shop_refund_shortfall
)

for t in "${TOPICS[@]}"; do
  docker exec "$BROKER_CONTAINER" sh mqadmin updateTopic \
    -n "$NAMESRV" -b "$BROKER_ADDR" -t "$t" >/dev/null 2>&1 \
    && echo "  [OK] topic $t" || echo "  [FAIL] topic $t"
done
echo "共 ${#TOPICS[@]} 个 Topic。"

# 消费者组同样必须预建：RocketMQ 5.x gRPC PushConsumer 不会自动创建订阅组，
# 缺失时启动报 PushConsumerImpl FAILED / Task was cancelled，应用上下文退出。
#
# C17/Z3 重试与死信显式化：5.x gRPC PushConsumerBuilder 无重试次数 setter，
# 消费行为只能在此声明（Java 侧 shop.mq.consume-max-attempts 已 @Deprecated 仅回显）：
#   -r 16  retryMaxTimes=16：业务回调返回 FAILURE 最多重投 16 次
#   -q 1    retryQueueNums=1：重试/死信队列开启，第 17 次投递进入 %DLQ%{group}
# 注意：mqadmin updateSubGroup 的 -d 是 consumeBroadcastEnable（广播模式开关），
# 【不是】DLQ 开关——若按字面加 "-d 1" 会把全部消费组切成广播模式（无集群重平衡、
# 无 broker 重试/DLQ 语义），故以 -q 1 落实 C17「重试 16 次 + 开启 DLQ」的本意。
# R4-19：变量名【禁止】用 GROUPS——它是 bash 保留只读数组（当前用户的 OS GID 列表），
# 业务组数组被静默丢弃，循环实际拿 16 个数字 GID 去建组（本机 16 个 OS 组），
# 真实消费组一个都没建成；历史上靠 broker autoCreateSubscriptionGroup=true 兜底才未爆。
# 故必须用 MQ_GROUPS 这类非保留名，且用普通变量名 TOPICS（无冲突）。
MQ_GROUPS=(
  cg_user_order_paid cg_user_order_cancel cg_user_refund cg_user_comment_created
  cg_product_order_created cg_product_order_paid cg_product_order_cancel cg_product_aftersale
  cg_marketing_order_created cg_marketing_order_paid cg_marketing_order_cancelled cg_marketing_presale_cancel cg_marketing_user_registered cg_marketing_groupbuy cg_marketing_seckill
  cg_order_paid cg_order_refund cg_order_aftersale cg_order_pay_timeout cg_order_auto_confirm cg_order_aftersale_window
  cg_pay_timeout_query
  cg_sett_paid cg_sett_confirmed cg_sett_completed cg_sett_refund cg_sett_shortfall
  cg_aftersale_shipped cg_aftersale_confirmed cg_aftersale_refund_success cg_aftersale_timeout
  cg_order_groupbuy cg_product_shipped cg_sett_deposit_pay
)
echo "共 ${#MQ_GROUPS[@]} 个消费组。"
for g in "${MQ_GROUPS[@]}"; do
  docker exec "$BROKER_CONTAINER" sh mqadmin updateSubGroup \
    -n "$NAMESRV" -b "$BROKER_ADDR" -g "$g" -r 16 -q 1 >/dev/null 2>&1 \
    && echo "  [OK] group $g (retryMaxTimes=16, retryQueueNums=1)" \
    || echo "  [FAIL] group $g 重试/DLQ 参数未生效，需人工排查"
done

# ============================================================================
# DLQ 巡检（C17/Z4 台面最小版）：列出全部 %DLQ% 死信 topic 及积压条数。
# 纯打印，绝不自动重放——死信处理走 framework OutboxAdminEndpoint/消费 RUNBOOK。
# 有任意积压时以非 0 退出，供 ha-check.sh / 运维 cron 判定告警。
# 用法：source 本文件后单独调用 inspect_dlq，或直接执行本脚本（建组后自动巡检）。
# ----------------------------------------------------------------------------
inspect_dlq() {
  echo "DLQ 巡检（%DLQ% topic 与积压，只读不重放）："
  local list
  list=$(docker exec "$BROKER_CONTAINER" sh mqadmin topicList -n "$NAMESRV" 2>/dev/null) \
    || { echo "  [FAIL] mqadmin topicList 不可用，DLQ 巡检未能完成"; return 2; }
  local dlqs
  dlqs=$(printf '%s\n' "$list" | grep '%DLQ%' || true)
  if [ -z "$dlqs" ]; then
    echo "  [OK] 当前无任何 %DLQ% 死信 topic（尚无毒丸/超限消息）"
    return 0
  fi
  local total=0
  local t backlog
  while IFS= read -r t; do
    [ -z "$t" ] && continue
    # topicStatus 列：brokerName qid minOffset maxOffset lastTimestamp；积压=maxOffset-minOffset
    backlog=$(docker exec "$BROKER_CONTAINER" sh mqadmin topicStatus \
      -n "$NAMESRV" -t "$t" 2>/dev/null \
      | awk 'NR>1 && $4 ~ /^[0-9]+$/ { s += $4 - $3 } END { print s + 0 }')
    backlog=${backlog:-0}
    echo "  [DLQ] $t 积压 ${backlog} 条"
    total=$((total + backlog))
  done <<EOF
$dlqs
EOF
  if [ "$total" -gt 0 ]; then
    echo "  [FAIL] %DLQ% 合计积压 ${total} 条：不自动重放，请按 RUNBOOK 经 admin endpoint 人工处置"
    return 1
  fi
  echo "  [OK] 存在 %DLQ% topic 但积压为 0"
  return 0
}

inspect_dlq
