#!/usr/bin/env bash
#
# 工单系统冒烟测试（部署后 / 手工验收用）
#
#   BASE=http://localhost:8080 ./scripts/smoke.sh
#   ./scripts/smoke.sh --only=health
#   ./scripts/smoke.sh --only=db
#   ./scripts/smoke.sh --only=flow
#
# 依赖：curl、docker（取用户 id 和改角色用）、项目根目录的 compose.yaml
# 说明：脚本会注册 it_smoke_* 用户；可重复执行。
#
set -uo pipefail

BASE="${BASE:-http://localhost:8080}"
ONLY=""
for arg in "$@"; do
  case "$arg" in
    --only=*) ONLY="${arg#--only=}" ;;
  esac
done

PASS=0
FAIL=0
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

ok()   { printf '  \033[32m✅\033[0m %s\n' "$1"; PASS=$((PASS + 1)); }
bad()  { printf '  \033[31m❌\033[0m %s — %s\n' "$1" "$2"; FAIL=$((FAIL + 1)); }
head1(){ printf '\n\033[1m%s\033[0m\n' "$1"; }

expect_status() { # desc want got
  if [ "$3" = "$2" ]; then ok "$1"; else bad "$1" "期望 $2，实际 $3"; fi
}

csrf_of() { # url jar
  curl -s -b "$2" -c "$2" "$1" \
    | sed -n 's/.*name="_csrf"[^>]*value="\([^"]*\)".*/\1/p' | head -1
}

dbq() { # sql
  docker compose exec -T db mysql -N -B -uticket -pticket ticket -e "$1" 2>/dev/null
}

register() { # jar username
  local jar="$1" user="$2" csrf
  csrf="$(csrf_of "$BASE/register" "$jar")"
  curl -s -o /dev/null -b "$jar" -c "$jar" \
    -d "username=$user&displayName=$user&password=password123&confirmPassword=password123&_csrf=$csrf" \
    "$BASE/register"
}

login() { # jar username [password]
  local jar="$1" user="$2" pass="${3:-password123}" csrf
  csrf="$(csrf_of "$BASE/login" "$jar")"
  curl -s -o /dev/null -w '%{http_code}' -b "$jar" -c "$jar" \
    -d "username=$user&password=$pass&_csrf=$csrf" "$BASE/login"
}

# ---------------------------------------------------------------- health
if [ -z "$ONLY" ] || [ "$ONLY" = "health" ]; then
  head1 "1. 健康检查"
  body="$(curl -s -m 5 "$BASE/actuator/health" || true)"
  case "$body" in
    *'"status":"UP"'*) ok "GET /actuator/health = UP" ;;
    *) bad "健康检查" "返回：${body:-<空>}（应用没起来？）" ;;
  esac

  code="$(curl -s -o /dev/null -w '%{http_code}' "$BASE/tickets")"
  expect_status "未登录访问 /tickets 被拦截" "302" "$code"
fi

# ---------------------------------------------------------------- db
if [ -z "$ONLY" ] || [ "$ONLY" = "db" ]; then
  head1 "2. 数据库"
  if ! dbq "select 1" >/dev/null 2>&1; then
    bad "数据库连接" "docker compose exec db 不可用（容器没起，或不在项目根目录）"
  else
    tables="$(dbq "select count(*) from information_schema.tables where table_schema='ticket'" | tr -d '[:space:]')"
    if [ "$tables" = "7" ]; then ok "表数量 = 7"; else bad "表数量" "期望 7，实际 ${tables:-0}"; fi

    cats="$(dbq "select count(*) from category where org_id=1" | tr -d '[:space:]')"
    if [ "${cats:-0}" -ge 4 ]; then ok "分类种子存在（$cats 条）"; else bad "分类种子" "期望 ≥4，实际 ${cats:-0}"; fi
  fi
fi

# ---------------------------------------------------------------- flow
if [ -z "$ONLY" ] || [ "$ONLY" = "flow" ]; then
  head1 "3. 认证与主流程"

  suffix="$(date +%s)"
  REPORTER="it_smoke_r_$suffix"
  AGENT="it_smoke_a_$suffix"
  ADMIN="it_smoke_m_$suffix"

  register "$TMP/r.jar" "$REPORTER"
  register "$TMP/a.jar" "$AGENT"
  register "$TMP/m.jar" "$ADMIN"

  # 改角色
  if dbq "update app_user set role='AGENT' where username='$AGENT'" >/dev/null 2>&1; then
    dbq "update app_user set role='ADMIN' where username='$ADMIN'" >/dev/null 2>&1
    ok "测试用户创建并分配角色"
  else
    bad "改角色" "无法访问数据库，后续流程跳过"
  fi

  expect_status "提交人登录" "302" "$(login "$TMP/r.jar" "$REPORTER")"
  expect_status "处理人登录" "302" "$(login "$TMP/a.jar" "$AGENT")"
  expect_status "管理员登录" "302" "$(login "$TMP/m.jar" "$ADMIN")"
  expect_status "错误密码被拒" "302" "$(login "$TMP/x.jar" "$REPORTER" "wrong-pass")"

  csrf="$(csrf_of "$BASE/tickets/new" "$TMP/r.jar")"
  loc="$(curl -s -o /dev/null -w '%{redirect_url}' -b "$TMP/r.jar" -c "$TMP/r.jar" \
        -d "title=冒烟测试工单&description=这是冒烟脚本创建的工单，用于验证主流程是否可用。&categoryId=1&priority=HIGH&_csrf=$csrf" \
        "$BASE/tickets")"
  TID="${loc##*/}"
  if [ -n "$TID" ] && [ "$TID" != "$loc" ]; then ok "创建工单成功（id=$TID）"; else bad "创建工单" "没有拿到重定向地址：$loc"; fi

  if [ -n "${TID:-}" ] && [ "$TID" != "${loc:-}" ]; then
    AGENT_ID="$(dbq "select id from app_user where username='$AGENT'" | tr -d '[:space:]')"

    csrf="$(csrf_of "$BASE/tickets/$TID" "$TMP/m.jar")"
    expect_status "管理员分派" "302" "$(curl -s -o /dev/null -w '%{http_code}' -b "$TMP/m.jar" -d "assigneeId=$AGENT_ID&_csrf=$csrf" "$BASE/tickets/$TID/assign")"

    csrf="$(csrf_of "$BASE/tickets/$TID" "$TMP/a.jar")"
    expect_status "处理人接单" "302" "$(curl -s -o /dev/null -w '%{http_code}' -b "$TMP/a.jar" -d "_csrf=$csrf" "$BASE/tickets/$TID/accept")"

    expect_status "处理人标记解决" "302" "$(curl -s -o /dev/null -w '%{http_code}' -b "$TMP/a.jar" -d "note=冒烟测试：已处理&_csrf=$csrf" "$BASE/tickets/$TID/resolve")"

    csrf="$(csrf_of "$BASE/tickets/$TID" "$TMP/r.jar")"
    expect_status "提交人确认关闭" "302" "$(curl -s -o /dev/null -w '%{http_code}' -b "$TMP/r.jar" -d "_csrf=$csrf" "$BASE/tickets/$TID/confirm")"

    status="$(dbq "select status from ticket where id=$TID" | tr -d '[:space:]')"
    if [ "$status" = "CLOSED" ]; then ok "最终状态 CLOSED"; else bad "最终状态" "期望 CLOSED，实际 ${status:-?}"; fi

    csrf="$(csrf_of "$BASE/tickets/$TID" "$TMP/a.jar")"
    expect_status "对已关闭工单接单被拒" "409" "$(curl -s -o /dev/null -w '%{http_code}' -b "$TMP/a.jar" -d "_csrf=$csrf" "$BASE/tickets/$TID/accept")"
  fi
fi

# ---------------------------------------------------------------- internal note
if [ -z "$ONLY" ] || [ "$ONLY" = "flow" ]; then
  head1 "4. 内部备注可见性"
  if [ -n "${TID:-}" ]; then
    marker="INTERNAL-MARKER-$RANDOM"
    csrf="$(csrf_of "$BASE/tickets/$TID" "$TMP/a.jar")"
    curl -s -o /dev/null -b "$TMP/a.jar" \
      -d "body=$marker&visibility=INTERNAL&_csrf=$csrf" "$BASE/tickets/$TID/replies"

    if curl -s -b "$TMP/r.jar" "$BASE/tickets/$TID" | grep -q "$marker"; then
      bad "内部备注对提交人不可见" "提交人页面出现了内部备注"
    else
      ok "提交人看不到内部备注"
    fi

    if curl -s -b "$TMP/a.jar" "$BASE/tickets/$TID" | grep -q "$marker"; then
      ok "处理人能看到内部备注"
    else
      bad "处理人看不到内部备注" "处理人页面没有该内容"
    fi
  fi
fi

# ---------------------------------------------------------------- summary
printf '\n通过 %d，失败 %d\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
