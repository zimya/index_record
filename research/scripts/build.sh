# 编译
javac \
  -source 8 -target 8 \
  -bootclasspath $ANDROID_HOME/platforms/android-34/android.jar \
  -classpath $ANDROID_HOME/lsposed/api-82.jar \
  -d build/classes \
  ./app/src/main/java/com/example/zhihuhook/ZhihuHook.java

# 打包APK
/Volumes/mySSD/env/android/build-tools/34.0.0/aapt package -f \
  -M AndroidManifest.xml \
  -I $ANDROID_HOME/platforms/android-34/android.jar \
  -A app/src/main/assets \
  -F build/unsigned.apk

# 把classes变成一个jar
cd build/classes && jar cf ../input.jar . && cd ../..

# 编译成dex
mkdir -p build/dex
/Volumes/mySSD/env/android/build-tools/34.0.0/d8 \
  --min-api 26 \
  --lib $ANDROID_HOME/platforms/android-34/android.jar \
  --output build/dex \
  build/input.jar

# 添加classes
cd build/dex
/Volumes/mySSD/env/android/build-tools/34.0.0/aapt add ../../build/unsigned.apk classes.dex
cd ../..

# 签名
/Volumes/mySSD/env/android/build-tools/34.0.0/apksigner sign \
  --ks debug.keystore \
  build/unsigned.apk

# 安装
adb install -r build/unsigned.apk
