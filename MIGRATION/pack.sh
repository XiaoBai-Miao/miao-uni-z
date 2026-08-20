#!/usr/bin/env bash
# -*- coding: utf-8 -*-
#
# miao-uni-z 迁移打包脚本
# ----------------------------------------
# 将整个项目（含源码、资源、配置、平台签名密钥 platform.jks）
# 压缩为单一归档文件，排除可重建的目录（.git / build / .gradle / .idea / .artifacts）。
#
# 用法:
#   ./pack.sh            # 在当前目录生成 miao-uni-z-migration-YYYYMMDD.tar.gz
#   ./pack.sh /path/out # 指定输出目录
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_ROOT="${SCRIPT_DIR%/MIGRATION}"   # MIGRATION 的上级即项目根
OUT_DIR="${1:-${SCRIPT_DIR}}"
DATE="$(date +%Y%m%d)"
ARCHIVE="${OUT_DIR}/miao-uni-z-migration-${DATE}.tar.gz"

echo "==> 项目根: ${PROJECT_ROOT}"
echo "==> 输出归档: ${ARCHIVE}"

# 校验关键文件存在
if [ ! -f "${PROJECT_ROOT}/app/platform.jks" ]; then
    echo "!! 警告: 未找到 ${PROJECT_ROOT}/app/platform.jks (系统签名密钥)"
    echo "!! 缺少该文件将无法对车机进行系统签名安装，请确认后重试。"
    exit 1
fi

cd "${PROJECT_ROOT}"

# 打包: 排除可重建中间产物，保留全部源码/资源/配置/密钥
tar -czf "${ARCHIVE}" \
    --exclude='./.git' \
    --exclude='./build' \
    --exclude='./.gradle' \
    --exclude='./.idea' \
    --exclude='./.artifacts' \
    --exclude='*/build' \
    --exclude='*.iml' \
    --exclude='./MIGRATION/gen_manifest.py' \
    --exclude='./MIGRATION/manifest.json' \
    .

# 校验归档内是否包含签名密钥
# 注意: 先缓存列表再 grep, 避免 grep -q 提前关闭管道导致上游 tar 收到 SIGPIPE(pipefail 下误判失败)
ARCHIVE_LIST="$(tar -tzf "${ARCHIVE}" 2>/dev/null)"
if echo "${ARCHIVE_LIST}" | grep -qE 'app/platform\.jks'; then
    echo "==> ✓ 已包含签名密钥 app/platform.jks"
else
    echo "!! 错误: 归档中缺少 platform.jks"
    exit 1
fi

SIZE="$(du -h "${ARCHIVE}" | cut -f1)"
echo "==> ✓ 打包完成: ${ARCHIVE} (${SIZE})"
echo "==> 文件数: $(echo "${ARCHIVE_LIST}" | wc -l)"
