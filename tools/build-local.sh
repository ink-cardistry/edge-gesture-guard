#!/system/bin/sh
# 离线/无 Gradle 环境下的本地打包脚本（本模块开发时用它做过全量验证）
#
# 需要的工具：
#   ANDROID_JAR  Android SDK platform 的 android.jar
#   AAPT2        aapt2（termux: pkg install aapt2）
#   D8_JAR       build-tools 里的 lib/d8.jar（纯 Java，可在任意平台跑）
#   XPOSED_JAR   可选，默认用仓库内 app/libs/api-82.jar
#   UBER_JAR     可选，uber-apk-signer，提供则自动签名
#   JAVA / JAVAC 可选，默认取 PATH 里的 java/javac
#
# 用法：
#   ANDROID_JAR=/path/android.jar AAPT2=/path/aapt2 D8_JAR=/path/d8.jar \
#       sh tools/build-local.sh
set -e

cd "$(dirname "$0")/.."
ROOT=$(pwd)

if [ -z "$ANDROID_JAR" ]; then echo "need ANDROID_JAR"; exit 1; fi
if [ -z "$AAPT2" ]; then echo "need AAPT2"; exit 1; fi
if [ -z "$D8_JAR" ]; then echo "need D8_JAR"; exit 1; fi
if [ -z "$JAVA" ]; then JAVA=java; fi
if [ -z "$JAVAC" ]; then JAVAC=javac; fi
if [ -z "$XPOSED_JAR" ]; then XPOSED_JAR=$ROOT/app/libs/api-82.jar; fi
# @hide 类的编译桩（TaskStackListener 等）：只参与编译，不进 APK
HIDDEN_STUB=$ROOT/app/libs/hidden-stub.jar
OUT=$ROOT/.out

echo "== 1/6 clean"
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/gen"

echo "== 2/6 生成带 package 属性的临时 manifest（Gradle 构建不需要这一步）"
python3 - "$ROOT" "$OUT" <<'PY'
import sys
root, out = sys.argv[1], sys.argv[2]
txt = open(root + "/app/src/main/AndroidManifest.xml", encoding="utf-8").read()
txt = txt.replace(
    '<manifest xmlns:android="http://schemas.android.com/apk/res/android">',
    '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="io.github.usernameNaN1234.EdgeGestureGuard">',
    1)
open(out + "/AndroidManifest-local.xml", "w", encoding="utf-8").write(txt)
PY

echo "== 3/6 编译资源"
"$AAPT2" compile --dir app/src/main/res -o "$OUT/res.zip"

VC=$(sed -n 's/.*versionCode[[:space:]]*\([0-9]*\).*/\1/p' app/build.gradle | head -1)
VN=$(sed -n 's/.*versionName[[:space:]]*"\([^"]*\)".*/\1/p' app/build.gradle | head -1)
if [ -z "$VC" ]; then VC=1; fi
if [ -z "$VN" ]; then VN=1.0.0; fi
echo "== 4/6 链接资源 + 生成 R.java (versionCode=$VC versionName=$VN)"
"$AAPT2" link -o "$OUT/base.apk" -I "$ANDROID_JAR" \
    --manifest "$OUT/AndroidManifest-local.xml" \
    --java "$OUT/gen" -A app/src/main/assets \
    --min-sdk-version 30 --target-sdk-version 35 \
    --version-code "$VC" --version-name "$VN" "$OUT/res.zip"

echo "== 5/6 编译 Java + dex"
"$JAVAC" -encoding UTF-8 -source 17 -target 17 -nowarn \
    -classpath "$ANDROID_JAR:$XPOSED_JAR:$HIDDEN_STUB" -d "$OUT/classes" \
    $(find app/src/main/java -name '*.java') "$OUT/gen/io/github/usernameNaN1234/EdgeGestureGuard/R.java"
"$JAVA" -cp "$D8_JAR" com.android.tools.r8.D8 --min-api 30 --lib "$ANDROID_JAR" \
    --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "== 6/7 打包"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
( cd "$OUT/dex" && zip -q -j "$OUT/unsigned.apk" classes.dex )

echo "== 7/7 对齐 + 签名"
python3 tools/align-apk.py "$OUT/unsigned.apk" "$OUT/aligned.apk"
KS=$ROOT/signing/edgeguard-release.jks
KS_PROPS=$ROOT/signing/keystore.properties
if [ -n "$UBER_JAR" ]; then
    if [ -f "$KS" ] && [ -f "$KS_PROPS" ]; then
        KS_PASS=$(sed -n 's/^storePassword=//p' "$KS_PROPS" | head -1)
        KS_ALIAS=$(sed -n 's/^keyAlias=//p' "$KS_PROPS" | head -1)
        KS_KEY_PASS=$(sed -n 's/^keyPassword=//p' "$KS_PROPS" | head -1)
        [ -z "$KS_ALIAS" ] && KS_ALIAS=edgeguard
        [ -z "$KS_KEY_PASS" ] && KS_KEY_PASS=$KS_PASS
        "$JAVA" -jar "$UBER_JAR" -a "$OUT/aligned.apk" -o "$OUT/signed" --skipZipAlign \
            --ks "$KS" --ksAlias "$KS_ALIAS" --ksPass "$KS_PASS" --ksKeyPass "$KS_KEY_PASS"
        echo "已签名(release): $OUT/signed/aligned-releaseSigned.apk"
    else
        "$JAVA" -jar "$UBER_JAR" -a "$OUT/aligned.apk" -o "$OUT/signed" --skipZipAlign
        echo "未找到 signing/edgeguard-release.jks，退回 debug 签名: $OUT/signed/aligned-debugSigned.apk"
    fi
else
    echo "未提供 UBER_JAR，跳过签名：$OUT/aligned.apk"
fi
