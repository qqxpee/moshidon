🚀 MoshidonQX 自动化构建手册
本仓库提供了一套基于 GitHub Actions 的 Android 自动化构建方案。通过简单的配置，即可实现自定义应用名称、独立包名（防止安装冲突）、多图限制破解以及自动化签名发布。

🛠️ 操作流程
1. Fork 本仓库
点击右上角 Fork，将代码同步到你的个人账号下。

2. 定制你的专属包名 (applicationId)
为了确保你的 App 是唯一的且不会覆盖其他版本，请修改构建脚本中的包名：

文件路径：.github/workflows/build-classic-apk.yml。

修改位置：找到顶部的 env 区域，修改 NEW_APP_ID 的值。

YAML
env:
  NEW_APP_ID: "org.joinmastodon.android.yourname" # 换成你自己的包名
作用：脚本会自动将 mastodon/build.gradle 中的原始 ID 替换为此值，从而物理级击穿手机系统对旧版应用的名称/图标缓存。

3. 配置 Actions 运行密钥 (Secrets)
前往仓库 Settings -> Secrets and variables -> Actions，配置以下三个密钥：

CUSTOM_APP_NAME：自定义应用名称（支持中文，建议 ≤4 个字）。

KEYSTORE_PASSWORD：签名证书的密码。

KEYSTORE_FILE：签名证书的 Base64 编码字符串。

🔑 如何获取密钥？

运行 generate-keystore.yml 工作流生成新证书。

从日志中复制 Base64 字符串 填入 KEYSTORE_FILE。

4. 启动自动化构建
切换到 Actions 标签页，选择 build-classic-apk.yml。

点击 Run workflow 手动触发构建。

构建成功后，在 Releases 页面下载生成的专属 APK。

🛠️ 技术亮点
包名重置：动态修改 applicationId，支持与原版共存。

多图补丁：自动注入 MAX_ATTACHMENTS = 12 破解限制。

全域更名：深度修改 strings_mo.xml 与 AndroidManifest.xml。
