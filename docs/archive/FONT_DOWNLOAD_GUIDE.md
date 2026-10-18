# 字体下载指南

## 需要的字体文件

请手动下载以下字体文件并放入 `app/src/main/res/font/` 目录：

### 1. Righteous（标题字体）
- **下载链接**: https://fonts.google.com/specimen/Righteous
- 点击 "Download family" 按钮
- 解压 ZIP 文件
- 将 `Righteous-Regular.ttf` 重命名为 `righteous.ttf`
- 放入 `app/src/main/res/font/` 目录

### 2. Poppins（正文字体）
- **下载链接**: https://fonts.google.com/specimen/Poppins
- 点击 "Download family" 按钮
- 解压 ZIP 文件
- 从 `static/` 目录中提取以下文件并重命名：
  - `Poppins-Light.ttf` → `poppins_light.ttf`
  - `Poppins-Regular.ttf` → `poppins_regular.ttf`
  - `Poppins-Medium.ttf` → `poppins_medium.ttf`
  - `Poppins-SemiBold.ttf` → `poppins_semibold.ttf`
  - `Poppins-Bold.ttf` → `poppins_bold.ttf`
- 放入 `app/src/main/res/font/` 目录

## 最终文件列表

下载完成后，`app/src/main/res/font/` 目录应包含：

```
font/
├── righteous.ttf
├── poppins_light.ttf
├── poppins_regular.ttf
├── poppins_medium.ttf
├── poppins_semibold.ttf
└── poppins_bold.ttf
```

## 备用下载方式

如果 Google Fonts 访问困难，可以尝试：

1. **字体天下**: https://www.fonts.net.cn/
2. **FontSquirrel**: https://www.fontsquirrel.com/
3. **GitHub 镜像**: 搜索 "google fonts mirror"

## 注意事项

- 文件名必须全部小写，使用下划线分隔
- 确保文件扩展名是 `.ttf`（TrueType Font）
- 不要使用空格或特殊字符
- 下载完成后运行构建验证字体是否正确加载
