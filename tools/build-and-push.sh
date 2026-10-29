#!/system/bin/sh
# 构建 + 自动推送到 GitHub（本机 github.com:443 被拦，只能走 api.github.com）
#
# 用法（工作区是 noexec，必须用 sh 调用）：
#   sh tools/build-and-push.sh
#
# 需要：
#   $HOME/.eg-git-cred   600 权限，内容一行：https://x-access-token:<TOKEN>@github.com
#   python3
set -e
cd "$(dirname "$0")/.."
ROOT=$(pwd)
SDK=/storage/emulated/0/dsh/toolchains/android-sdk
HOME_DIR=$HOME

VN=$(sed -n 's/.*versionName "\([^"]*\)".*/\1/p' app/build.gradle | head -1)
[ -z "$VN" ] && VN=0.0.0
echo "== building v$VN"

ANDROID_JAR=$SDK/platform/android-35/android.jar \
AAPT2=$HOME_DIR/bin/aapt2 \
D8_JAR=$SDK/bt/android-15/lib/d8.jar \
UBER_JAR=$HOME_DIR/.re/uber.apk-signer.jar \
JAVA=$HOME_DIR/bin/java \
JAVAC=$HOME_DIR/.build/j.sh \
sh tools/build-local.sh

if [ -f .out/signed/aligned-signed.apk ]; then
  cp .out/signed/aligned-signed.apk "dist/EdgeGestureGuard-$VN-release.apk"
  echo "== apk(release 签名): dist/EdgeGestureGuard-$VN-release.apk"
else
  cp .out/signed/aligned-debugSigned.apk "dist/EdgeGestureGuard-$VN-debug.apk"
  echo "== apk(debug 签名): dist/EdgeGestureGuard-$VN-debug.apk"
fi

git add -A
if git diff --cached --quiet; then
  echo "== nothing to commit"
else
  git -c user.name=ink-cardistry \
      -c user.email=95966676+ink-cardistry@users.noreply.github.com \
      commit -q -m "build: v$VN"
  echo "== committed v$VN"
fi

[ -f "$HOME_DIR/.eg-git-cred" ] || { echo "missing $HOME_DIR/.eg-git-cred (token)"; exit 1; }
GH_TOKEN=$(sed -E 's#https://x-access-token:([^@]+)@github.com#\1#' "$HOME_DIR/.eg-git-cred")
export GH_TOKEN
export GH_REPO="${GH_REPO:-usernameNaN1234/EdgeGestureGuard}"
export GH_RELEASE="${GH_RELEASE:-1}"
python3 tools/push-via-api.py
