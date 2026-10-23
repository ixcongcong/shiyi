# 分发与覆盖升级

## 对外文件

安装包统一为 `时宜-版本号.apk`，例如 `时宜-0.1.2.apk`。不在文件名中添加“试用版”“界面优化”等说明。应用桌面名称保持“时宜”。

0.1.2 是当前固定签名的分发基准，包名 `cn.shiyi.reminder`，versionCode 为 3。已经安装这份 0.1.2 的用户，后续使用同一签名、更高 versionCode 的 APK，可以直接覆盖安装并保留本地数据。

0.1.0 使用早期临时调试签名，不能自动迁移成相同证书；仅首次从 0.1.0 迁移时需要先导出备份，再卸载、安装和恢复。不要将 0.1.0 当作固定签名的分发基准。

## 固定签名

`distribution/signing.json` 固定包名、公开证书 SHA-256 指纹以及最近分发版本。它不包含密钥或密码。私钥不可换新；修改 APK 文件名不会改变签名。

签名资料存放在开发环境的私有目录，并单独备份到用户 Google Drive 根目录的“时宜签名备份（勿分享）”中。上传前、后都检查该备份仅当前用户本人可见。它与准备分享给其他人的“时宜”安装包文件夹分开；不将密钥或密码放入公开仓库、CI 产物或安装包目录。

仓库现有 GitHub 集成没有管理 Actions secrets 的权限，因此不在 CI 中随机生成用于对外分发的密钥。CI 生成的调试包仅供开发；对外分发经过固定私钥签名的 release APK。

## 打包步骤

1. 更新 `app/build.gradle.kts` 中的 `versionName` 和 `versionCode`；每次实际发布必须递增版本号与 versionCode，包名保持不变。
2. 通过 CI 的单元测试、Lint 与 release 构建，取回该次构建的未签名 release APK 并核对构建 SHA 与产物校验和。
3. 使用 JDK 17 或以上和已核验的 apksig 8.7.3 JAR 执行：

```sh
python scripts/package_release.py /path/app-release-unsigned.apk \
  --keystore /private/shiyi-signing.jks \
  --password-file /private/shiyi-signing-password \
  --apksig /tools/apksig-8.7.3.jar
```

脚本读取当前构建版本，输出 `dist/时宜-版本号.apk` 与校验和。签名前比较既有证书指纹，签名后验证 APK 签名；不匹配时拒绝输出分发包，不自动生成替代密钥。

4. 将 APK 放入 Google Drive 的“时宜”安装包目录，只分享 APK。分发完成后更新 `distribution/signing.json` 的最近分发版本；证书指纹保持不变。
5. 使用上一分发版本进行覆盖安装检查，确认事项、历史、权限及后续提醒仍保留。

## 当前验证

已用固定密钥重新打包 0.1.2，并确认与已分发的 APK 字节一致；固定证书校验与错误证书拒绝输出均通过。文件改名不需要重新安装。后续真实升级仍需按第 5 步做一次覆盖安装验证。
