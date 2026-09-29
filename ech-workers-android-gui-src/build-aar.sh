#!/usr/bin/env bash
# 编译 Android GUI 依赖的 ech-workers.aar（gomobile bind）
#
# 用法（在仓库根目录执行）：
#   bash ech-workers-android-gui-src/build-aar.sh
#
# 依赖：
#   go install golang.org/x/mobile/cmd/gomobile@latest
#   go install golang.org/x/mobile/cmd/gobind@latest
#   gomobile init
#
# 说明：workers.go 的 StartSocksProxy 导出函数签名一旦变更，
#       必须重新执行本脚本生成 aar，否则 Java 侧调用会与旧 aar 不匹配。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_API="${ANDROID_API:-24}"
OUT_AAR="$ROOT_DIR/ech-workers-android-gui-src/ech-workers.aar"

if ! command -v gomobile >/dev/null 2>&1; then
	echo "错误：未找到 gomobile，请先执行：" >&2
	echo "  go install golang.org/x/mobile/cmd/gomobile@latest" >&2
	echo "  gomobile init" >&2
	exit 1
fi

if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
	echo "警告：未设置 ANDROID_HOME/ANDROID_SDK_ROOT，gomobile 可能找不到 Android SDK/NDK" >&2
fi

# 若已安装 NDK 但未指定路径，自动选择最新版本
if [ -z "${ANDROID_NDK_HOME:-}" ] && [ -d "${ANDROID_HOME:-/nonexistent}/ndk" ]; then
	ANDROID_NDK_HOME="$(ls -d "${ANDROID_HOME}"/ndk/* 2>/dev/null | sort -V | tail -1)"
	export ANDROID_NDK_HOME
	echo "==> 使用 NDK: ${ANDROID_NDK_HOME}"
fi

echo "==> gomobile bind (androidapi=${ANDROID_API})"
gomobile bind \
	-target=android \
	-androidapi "${ANDROID_API}" \
	-javapkg=com.ech.workers \
	-o "${OUT_AAR}" \
	./ech-workers-android-gui-src

echo "==> 已生成: ${OUT_AAR}"
