# ZX706 音乐播放器共存试验版

以提供的 `音乐播放器_3.0.4.apk` 为基础，新增睡眠定时与每日/累计听歌记录。

安装后的桌面名称：**音乐播放器·睡眠统计**  
包名：`com.sony.walkman.highresmediaplayer.sleepstat`  
版本：`3.0.4-sleepstat1`，最低 Android 14（保留原包要求）。

打开播放器的设置，在顶部“睡眠与听歌记录”使用新增功能：

- 睡眠定时：15、30、45、60、90、120 分钟，或自定义 1–1440 分钟。到点后等待当前歌曲自然结束再暂停，可以随时取消。暂停不会停止倒计时，手动跳曲不视为自然结束。
- 播放时长记录：仅记录本共存版处于正常 PLAYING 状态的时间，暂停、快进和倒退不计；展示今日、累计及每日历史。跨午夜拆分，记录保存在本机，从首次使用开始计算，不导入原版记录。

构建和静态验证已通过，但没有连接 ZX706 实机运行。共存包使用新签名，能否加载索尼私有系统音频库、访问相关音频服务，仍需在你的 ZX706 上验证。若无法安装/启动/播放，请保留原系统应用并提供错误信息或日志，无需卸载系统原版。

定时启用期间暂停原生 gapless 预取，以保障曲尾暂停。若启用前下一曲已被原生层预载，第一次曲尾兜底可能短暂播放下一曲声音后暂停。程序非正常终止时，播放统计最多可能丢失最后约 10 秒。应用进程重启会恢复已保存统计及定时；不会把进程不存在的时间算成播放。

## 修改与验证

- 只在后台 PlaybackService 的统一状态入口、销毁入口、自然曲尾、gapless 预取/切换路径以及设置列表中加入窄范围调用。
- 保留原 Java 类命名、资源 ID、SDK 要求；重新命名 manifest 包名、provider authority、应用自定义权限和内部广播，防止与系统原版冲突。
- 原来的 1940 个资源/素材条目字节保持不变；资源表只修改 package header 的包名。
- 原耳机插孔煲机累计计时保留；本功能使用独立数据文件，记录共存版自己的播放。
- 计时内核 35 项断言通过；实际新增服务层逻辑在 JVM Android fake 环境下 42 项断言通过。它们不等价于设备/UI/native测试。
- 最终 APK 的 7 个 DEX 全部可解析、无重复类，6 个服务/界面接入点均从最终 DEX 验证存在；签名 v3、ZIP完整性与 zipalign 检查通过。
- REA 再次解析最终 APK manifest 成功。

## 源码与复现

此目录包含新增代码、定点补丁脚本和测试；原版 APK、反编译的原厂源码和工具二进制不包含在仓库中。共存试验版 APK 位于 `artifacts/`。

工具要求：JDK 21（含编译模块）、Python 3、Apktool 3.0.3、Android SDK Platform 34（ext7 r03）、Build Tools 34.0.0。工具目录布局与下列命令匹配：

```text
tools/apktool.jar
tools/sdk-platform/android-34/android.jar
tools/sdk-build/android-14/{aapt2,apksigner,zipalign,lib/d8.jar,lib/apksigner.jar}
```

```bash
python build.py --apk /path/music-player-3.0.4.apk --tools /path/tools --work /path/fresh-build
```

脚本校验原 APK 的 SHA-256，重新解包、注入、编译、打包、对齐和签名。首次构建会在本地 `signing/` 生成签名文件与密码。该目录已加入 `.gitignore`，不会上传到仓库；它不是索尼签名。已提供的 APK 使用当前工作环境保留的签名，首次自行构建时生成的新签名不能直接覆盖它。

原包 SHA-256：`e9c996b748d9440d8ae6a440a5bb8f74e4897b57c05355057a920711e3886064`。

分析工具为 REA 6.7.0、jadx-headless-mcp 0.7.1；JADX JAR 已对照 REA 文档固定 SHA-256 验证。全部分析为静态分析，未运行下载的 APK。
