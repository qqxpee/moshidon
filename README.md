# **🚀 MoshidonQX 自动化构建手册**

本仓库提供了一套基于 GitHub Actions 的 Android 自动化构建方案。通过简单的配置，即可实现**自定义应用名称**、**独立包名**（防止安装冲突）、**多图限制破解**以及**自动化签名发布**。

## **🛠️ 操作流程**

### **1\. Fork 本仓库**

点击仓库右上角的 **Fork** 按钮，将代码同步到您的个人账号下。

### **2\. 定制专属包名 (applicationId)**

为了确保您的 App 具备唯一性且不会覆盖其他版本，请修改构建脚本中的包名：

* **文件路径**：.github/workflows/build-classic-apk.yml  
* **修改位置**：找到脚本顶部的 env 区域，修改 NEW\_APP\_ID 的值。

env:  
  NEW\_APP\_ID: "org.joinmastodon.android.yourname" \# 请替换为您专属的包名

**💡 作用说明**：

脚本会自动将 mastodon/build.gradle 中的原始 ID 替换为此值，从而在物理层面打破手机系统对旧版应用的名称和图标缓存。

### **3\. 配置 Actions 运行密钥 (Secrets)**

前往仓库的 Settings \-\> Secrets and variables \-\> Actions，配置以下三个密钥（Repository secrets）：

| 密钥名称 (Name) | 说明 (Description) |
| :---- | :---- |
| CUSTOM\_APP\_NAME | **应用名称**：支持中文，建议不超过 4 个汉字。 |
| KEYSTORE\_PASSWORD | **证书密码**：您创建 .jks 签名文件时设置的密码。 |
| KEYSTORE\_FILE | **证书 Base64 编码**：签名文件的 Base64 字符串（获取方法详见下文）。 |

#### **🔑 如何获取密钥（KEYSTORE\_FILE）？**

1. 在 **Actions** 标签页运行 generate-keystore.yml 工作流以生成新证书。  
2. 从运行日志中复制生成的 **Base64 字符串**，并将其填入 KEYSTORE\_FILE 密钥中。

### **4\. 启动自动化构建**

1. 切换到仓库的 **Actions** 标签页。  
2. 在左侧工作流列表中选择 build-classic-apk.yml。  
3. 点击 **Run workflow** 手动触发构建。  
4. 构建成功后，前往 **Releases** 页面即可下载您专属的签名版 APK。

## **✨ 技术亮点**

* **📦 包名重置**：动态修改 applicationId，支持与原版完美共存，彻底防止桌面名称缓存冲突。  
* **🖼️ 多图补丁**：自动注入代码破解限制（MAX\_ATTACHMENTS \= 12），突破原生图片上传数量瓶颈。   
* **🔄 全域更名**：脚本会自动同步修改 strings\_mo.xml 与 AndroidManifest.xml 中的应用标签，实现无死角的应用重命名。
