# 项目详情

这是一个由jetpack compose编写的全自动记录社交媒体中浏览记录的软件。技术栈是Kotlin、Lsposed、ObjectBox、Jsoup、commonMark。原理是hook App中的okhttp响应，将数据缓存进cache私有目录，然后打开app时通过广播获取宿主App的私有目录数据，存进ObjectBox数据库中。启用这个App需要申请Lsposed作用域，并且大概率需要root权限。

# 目录结构

./research 下面是抓包得到的响应数据，用于分析
./app/src/test/java/me/melon/record 是App测试
./app/src/main/java/me/melon/record 是App源码

# 如何自动化验证

先`adb devices`查看是否链接手机。链接了手机的话，我的手机一定存在root权限。你可以先用`adb shell su -c 'ls -la /data/user/0/com.twitter.android'`查看私有目录数据，然后可以通过`adb shell monkey`来启动各种App，观察cache文件夹中保存的bin、meta文件。

# 逆向环境

大部分情况下你不需要逆向，但如果有必要的话，macOS环境下配置了jadx、strings、ghidraRun等工具可以调用。Apk可以从手机中直接获取。
